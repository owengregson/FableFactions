package dev.fablemc.factions.kernel.reduce;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import dev.fablemc.factions.kernel.audit.FactionAuditAction;
import dev.fablemc.factions.kernel.config.BakedTables;
import dev.fablemc.factions.kernel.config.PowerConfig;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.ChestRules;
import dev.fablemc.factions.kernel.rules.ClaimRules;
import dev.fablemc.factions.kernel.rules.DisbandRules;
import dev.fablemc.factions.kernel.rules.EconomyRules;
import dev.fablemc.factions.kernel.rules.FactionAggregates;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.rules.InviteRules;
import dev.fablemc.factions.kernel.rules.MergeRules;
import dev.fablemc.factions.kernel.rules.MoneyMath;
import dev.fablemc.factions.kernel.rules.NameRules;
import dev.fablemc.factions.kernel.rules.PowerMath;
import dev.fablemc.factions.kernel.rules.PrefRules;
import dev.fablemc.factions.kernel.rules.RelationRules;
import dev.fablemc.factions.kernel.rules.RoleRules;
import dev.fablemc.factions.kernel.rules.TravelRules;
import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.ChestTable;
import dev.fablemc.factions.kernel.state.EscrowTable;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.Home;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.MergeTable;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.kernel.state.RelationKind;
import dev.fablemc.factions.kernel.state.Warp;
import dev.fablemc.factions.kernel.state.WarpTable;
import dev.fablemc.factions.kernel.state.ZoneStats;

/**
 * The single mutation point of the kernel: {@code (state, envelope) -> (state', effects)}
 * (CONTRACTS §2, proposal-C §4.4). Replaces the Wave-1 placeholder.
 *
 * <p>PURE: no IO, no clock, no Bukkit, no statics. All nondeterminism comes from the envelope
 * ({@code epochMillis}, {@code tick}, {@code rngSeed}). New UUIDs are drawn from a per-envelope
 * {@link SplittableRandom} seeded by {@code rngSeed} — never {@code UUID.randomUUID} — which makes
 * replay byte-identical. Runs only on the {@code fable-kernel} writer thread.
 *
 * <p>Every {@link Intent} record has a branch (see {@link #apply}); an unrecognised intent throws,
 * which the writer's per-intent try/catch (AM-9) turns into {@code Rejected(INTERNAL_ERROR)}.
 *
 * <p><b>Paged bulk ops (AM-5).</b> The landed {@code Effect} vocabulary had no continuation record,
 * so Wave 2a added exactly one: {@link Effect.ContinuationRequested}. When a paged intent (disband /
 * unclaim-all / merge / tax-sweep / zone / retag) has more work, the reducer emits a
 * {@code ContinuationRequested(next)}; the {@code WriterThread} must inspect the outcome's effects
 * and re-enqueue {@code next} on the system lane (behind already-queued intents), publishing the
 * intermediate snapshot between pages. Each page touches at most {@link #PAGE_SIZE} entities.
 */
public final class Reducer {

    private Reducer() {
    }

    /** Max entities touched per AM-5 page (≤1024 invariant). */
    public static final int PAGE_SIZE = 1024;

    private static final int NO_HANDLE = FactionHandle.WILDERNESS;
    private static final String[] NO_ARGS = new String[0];

    /** Applies one intent envelope to the state. Never throws for domain reasons. */
    public static Outcome apply(KernelState state, IntentEnvelope envelope) {
        Ctx c = new Ctx(state, envelope);
        Intent i = envelope.intent();

        // Keep the state's tick in step with the envelope so snapshot power reads settle correctly.
        if (state.tick() != envelope.tick()) {
            c.state = c.state.withVersionTick(state.version(), envelope.tick());
        }

        if (i instanceof Intent.CreateFaction x) {
            c.createFaction(x);
        } else if (i instanceof Intent.DisbandFaction x) {
            c.disbandFaction(x);
        } else if (i instanceof Intent.RenameFaction x) {
            c.renameFaction(x);
        } else if (i instanceof Intent.SetDescription x) {
            c.setDescription(x);
        } else if (i instanceof Intent.SetMotd x) {
            c.setMotd(x);
        } else if (i instanceof Intent.TransferOwnership x) {
            c.transferOwnership(x);
        } else if (i instanceof Intent.SendMergeRequest x) {
            c.sendMergeRequest(x);
        } else if (i instanceof Intent.AcceptMergeRequest x) {
            c.acceptMergeRequest(x);
        } else if (i instanceof Intent.JoinFaction x) {
            c.joinFaction(x);
        } else if (i instanceof Intent.LeaveFaction x) {
            c.leaveFaction(x);
        } else if (i instanceof Intent.KickMember x) {
            c.kickMember(x);
        } else if (i instanceof Intent.SendInvite x) {
            c.sendInvite(x);
        } else if (i instanceof Intent.RevokeInvite x) {
            c.revokeInvite(x);
        } else if (i instanceof Intent.DeclineInvite x) {
            c.declineInvite(x);
        } else if (i instanceof Intent.DeclineAllInvites x) {
            c.declineAllInvites(x);
        } else if (i instanceof Intent.PromoteMember x) {
            c.changeRank(x.faction(), x.actor(), x.target(), true);
        } else if (i instanceof Intent.DemoteMember x) {
            c.changeRank(x.faction(), x.actor(), x.target(), false);
        } else if (i instanceof Intent.CreateRole x) {
            c.createRole(x);
        } else if (i instanceof Intent.RenameRole x) {
            c.renameRole(x);
        } else if (i instanceof Intent.SetRolePriority x) {
            c.setRolePriority(x);
        } else if (i instanceof Intent.SetRolePrefix x) {
            c.setRolePrefix(x);
        } else if (i instanceof Intent.DeleteRole x) {
            c.deleteRole(x);
        } else if (i instanceof Intent.AssignRole x) {
            c.assignRole(x);
        } else if (i instanceof Intent.ClaimChunks x) {
            c.claimChunks(x);
        } else if (i instanceof Intent.UnclaimChunks x) {
            c.unclaimChunks(x);
        } else if (i instanceof Intent.UnclaimAll x) {
            c.unclaimAllPage(x.faction(), x.actor());
        } else if (i instanceof Intent.AdminClaimChunks x) {
            c.adminClaimChunks(x);
        } else if (i instanceof Intent.AdminUnclaimChunks x) {
            c.adminUnclaimChunks(x);
        } else if (i instanceof Intent.SetZoneChunks x) {
            c.zonePage(x.zoneOrdinal(), x.worldIdx(), x.keys(), 0, x.actor());
        } else if (i instanceof Intent.RemoveZoneChunk x) {
            c.removeZoneChunk(x);
        } else if (i instanceof Intent.DeclareRelation x) {
            c.declareRelation(x);
        } else if (i instanceof Intent.RecordDeath x) {
            c.recordDeath(x);
        } else if (i instanceof Intent.PowerTick x) {
            c.powerTick(x);
        } else if (i instanceof Intent.BuyPower x) {
            c.buyPower(x);
        } else if (i instanceof Intent.AdminPowerSet x) {
            c.adminPower(x.target(), PowerMath.SRC_ADMIN_SET, x.amount(), x.actor(), x.reason());
        } else if (i instanceof Intent.AdminPowerAdd x) {
            c.adminPower(x.target(), PowerMath.SRC_ADMIN_ADD, x.amount(), x.actor(), x.reason());
        } else if (i instanceof Intent.AdminPowerRemove x) {
            c.adminPower(x.target(), PowerMath.SRC_ADMIN_REMOVE, x.amount(), x.actor(), x.reason());
        } else if (i instanceof Intent.AdminPowerReset x) {
            c.adminPower(x.target(), PowerMath.SRC_ADMIN_RESET, 0.0, x.actor(), x.reason());
        } else if (i instanceof Intent.SetPowerFrozen x) {
            c.setPowerFrozen(x);
        } else if (i instanceof Intent.CreditBank x) {
            c.creditBank(x);
        } else if (i instanceof Intent.RequestBankWithdrawal x) {
            c.requestBankWithdrawal(x);
        } else if (i instanceof Intent.SettleEscrow x) {
            c.settleEscrow(x);
        } else if (i instanceof Intent.TransferBank x) {
            c.transferBank(x);
        } else if (i instanceof Intent.TaxSweep x) {
            c.taxSweepPage(x.tick(), 0);
        } else if (i instanceof Intent.SetHome x) {
            c.setHome(x);
        } else if (i instanceof Intent.UnsetHome x) {
            c.unsetHome(x);
        } else if (i instanceof Intent.SetWarp x) {
            c.setWarp(x);
        } else if (i instanceof Intent.DeleteWarp x) {
            c.deleteWarp(x);
        } else if (i instanceof Intent.SetWarpPassword x) {
            c.setWarpPassword(x);
        } else if (i instanceof Intent.SetWarpCost x) {
            c.setWarpCost(x);
        } else if (i instanceof Intent.CreateChest x) {
            c.createChest(x);
        } else if (i instanceof Intent.DeleteChest x) {
            c.deleteChest(x);
        } else if (i instanceof Intent.CommitChestContents x) {
            c.commitChestContents(x);
        } else if (i instanceof Intent.SetFactionFlag x) {
            c.setFactionFlag(x);
        } else if (i instanceof Intent.SetNotifyPref x) {
            c.setNotifyPref(x);
        } else if (i instanceof Intent.SetLocale x) {
            c.setLocale(x);
        } else if (i instanceof Intent.SetAutoTerritoryMode x) {
            c.setAutoTerritoryMode(x);
        } else if (i instanceof Intent.SetTerritoryTitles x) {
            c.setTerritoryTitles(x);
        } else if (i instanceof Intent.SetFly x) {
            c.setFly(x);
        } else if (i instanceof Intent.SetOverriding x) {
            c.setOverriding(x);
        } else if (i instanceof Intent.SetShield x) {
            c.setShield(x);
        } else if (i instanceof Intent.ClearShield x) {
            c.clearShield(x);
        } else if (i instanceof Intent.PlayerConnected x) {
            c.playerConnected(x);
        } else if (i instanceof Intent.PlayerDisconnected x) {
            c.playerDisconnected(x);
        } else if (i instanceof Intent.AckInbox x) {
            c.ackInbox(x);
        } else if (i instanceof Intent.SwapConfig x) {
            c.swapConfig(x);
        } else if (i instanceof Intent.SeedPredefined x) {
            c.seedPredefined(x);
        } else if (i instanceof Intent.ImportBaseline x) {
            c.importBaseline(x);
        } else if (i instanceof Intent.DisbandPage x) {
            c.disbandPage(x);
        } else if (i instanceof Intent.UnclaimAllPage x) {
            c.unclaimAllPage(x.faction(), x.actor());
        } else if (i instanceof Intent.MergePage x) {
            c.mergePage(x);
        } else if (i instanceof Intent.TaxSweepPage x) {
            c.taxSweepPage(x.tick(), x.cursor());
        } else if (i instanceof Intent.ZonePage x) {
            c.zonePage(x.zoneOrdinal(), x.worldIdx(), x.keys(), x.cursor(), x.actor());
        } else if (i instanceof Intent.RetagPage x) {
            c.retagPage(x.cursor());
        } else {
            throw new IllegalStateException("unhandled intent: " + i.getClass().getName());
        }
        return new Outcome(c.state, c.effects);
    }

    /** The reducer's result: the next state plus the ordered effects it produced. */
    public record Outcome(KernelState next, List<Effect> effects) {
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Working context (confined to one apply() call — never published while mutable).
    // ═══════════════════════════════════════════════════════════════════════════════════════

    private static final class Ctx {
        KernelState state;
        final long seq;
        final long epochMillis;
        final int tick;
        final Origin origin;
        final List<Effect> effects = new ArrayList<>(8);
        private SplittableRandom rng;
        private final long rngSeed;

        Ctx(KernelState state, IntentEnvelope env) {
            this.state = state;
            this.seq = env.seq();
            this.epochMillis = env.epochMillis();
            this.tick = env.tick();
            this.origin = env.origin();
            this.rngSeed = env.rngSeed();
        }

        // ── primitives ──────────────────────────────────────────────────────────────────

        private UUID newUuid() {
            if (rng == null) {
                rng = new SplittableRandom(rngSeed);
            }
            return new UUID(rng.nextLong(), rng.nextLong());
        }

        private void reject(ReasonCode reason) {
            effects.add(new Effect.Rejected(seq, origin, reason, NO_ARGS));
        }

        private void reject(ReasonCode reason, String... args) {
            effects.add(new Effect.Rejected(seq, origin, reason, args));
        }

        private void audit(int factionHandle, UUID actor, FactionAuditAction action, String detail) {
            effects.add(new Effect.AuditRecorded(seq, origin, factionHandle, actor, action, detail));
        }

        private void notify(UUID target, String key, String... args) {
            effects.add(new Effect.Notify(seq, origin, target, MessageKey.of(key), args));
        }

        private void notifyFaction(int factionHandle, String key, String... args) {
            effects.add(new Effect.NotifyFaction(seq, origin, factionHandle, Effect.MEMBERS_ALL,
                    MessageKey.of(key), args));
        }

        private void continuation(Intent next) {
            effects.add(new Effect.ContinuationRequested(seq, origin, next));
        }

        private PowerConfig power() {
            return state.config().power();
        }

        private BakedTables baked() {
            return state.config().baked();
        }

        private Faction resolve(int handle) {
            return state.factions().resolve(handle);
        }

        private int memberOrd(UUID player) {
            return state.members().get(player);
        }

        /** Faction handle of a member ordinal, or WILDERNESS. */
        private int memberFactionHandle(int ord) {
            return ord < 0 ? NO_HANDLE : state.ledger().factionHandle(ord);
        }

        /** Ensures a ledger row + directory mapping for {@code player}; returns the ordinal. */
        private int ensureMember(UUID player, String name) {
            int ord = memberOrd(player);
            if (ord >= 0 && state.ledger().has(ord)) {
                return ord;
            }
            ord = state.ledger().nextOrdinal();
            state = state.withLedger(state.ledger().withNewMember(ord, player, name == null ? "" : name));
            state = state.withMembers(state.members().withMapping(player, ord));
            return ord;
        }

        private void replaceFaction(Faction f) {
            state = state.withFactions(state.factions().replace(f.idx(), f));
        }

        // ── lifecycle ───────────────────────────────────────────────────────────────────

        void createFaction(Intent.CreateFaction c) {
            ReasonCode nameErr = NameRules.validate(c.name(), state.factionNames());
            if (nameErr != null) {
                reject(nameErr);
                return;
            }
            int existingOrd = memberOrd(c.owner());
            if (existingOrd >= 0 && state.ledger().has(existingOrd)
                    && memberFactionHandle(existingOrd) != NO_HANDLE) {
                reject(ReasonCode.ALREADY_IN_FACTION);
                return;
            }
            int ord = state.factions().nextFreeOrdinal();
            int handle = FactionHandle.handle(state.factions().generationAt(ord), ord);
            UUID id = newUuid();
            Rank[] ranks = defaultRanks();
            int ownerRankIdx = RoleRules.ownerRankIndex(ranks);
            String tag = c.name();
            Faction f = new Faction(ord, id, c.name(), NameIndex.fold(c.name()), c.owner(), "", "",
                    epochMillis, 0.0, 0.0, 0L,
                    RelationEdges.NO_ORDINALS, RelationEdges.NO_KINDS,
                    RelationEdges.NO_ORDINALS, RelationEdges.NO_KINDS,
                    null, Faction.NO_SHIELD, 0, false, ranks, 0, 0.0, (long) tick,
                    FactionClaimList.empty(), tag, tag);
            state = state.withFactions(state.factions().withFaction(ord, f));
            state = state.withFactionNames(state.factionNames().with(NameIndex.fold(c.name()), ord));

            int ownerOrd = ensureMember(c.owner(), "");
            state = state.withLedger(state.ledger()
                    .withFactionHandle(ownerOrd, handle)
                    .withRankIdx(ownerOrd, ownerRankIdx)
                    .withJoinedAt(ownerOrd, epochMillis));

            effects.add(new Effect.FactionCreated(seq, origin, handle, id, c.name()));
        }

        private Rank[] defaultRanks() {
            String op = state.config().role().defaultOwnerPrefix();
            String ofp = state.config().role().defaultOfficerPrefix();
            String mp = state.config().role().defaultMemberPrefix();
            return new Rank[] {
                    new Rank(Rank.NAME_MEMBER, Rank.NAME_MEMBER, emptyToNull(mp), Rank.PRIORITY_MEMBER),
                    new Rank(Rank.NAME_OFFICER, Rank.NAME_OFFICER, emptyToNull(ofp), Rank.PRIORITY_OFFICER),
                    new Rank(Rank.NAME_OWNER, Rank.NAME_OWNER, emptyToNull(op), Rank.PRIORITY_OWNER)
            };
        }

        private static String emptyToNull(String s) {
            return (s == null || s.trim().isEmpty()) ? null : s.trim();
        }

        void renameFaction(Intent.RenameFaction c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            ReasonCode fmt = NameRules.validateFormat(c.newName());
            if (fmt != null) {
                reject(fmt);
                return;
            }
            String newFold = NameIndex.fold(c.newName());
            if (!newFold.equals(f.nameFolded()) && state.factionNames().contains(newFold)) {
                reject(ReasonCode.NAME_TAKEN);
                return;
            }
            String oldName = f.name();
            String tag = c.newName();
            Faction nf = FactionEdit.withName(f, c.newName(), newFold, tag, tag);
            replaceFaction(nf);
            if (!newFold.equals(f.nameFolded())) {
                state = state.withFactionNames(
                        state.factionNames().without(f.nameFolded()).with(newFold, f.idx()));
            }
            effects.add(new Effect.FactionRenamed(seq, origin, c.faction(), oldName, c.newName()));
        }

        void setDescription(Intent.SetDescription c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            String d = c.description() == null ? "" : c.description();
            if (d.length() > 250) {
                reject(ReasonCode.DESCRIPTION_TOO_LONG);
                return;
            }
            replaceFaction(FactionEdit.withDescription(f, d));
            effects.add(new Effect.DescriptionChanged(seq, origin, c.faction(), d));
        }

        void setMotd(Intent.SetMotd c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            String m = c.motd() == null ? "" : c.motd();
            replaceFaction(FactionEdit.withMotd(f, m));
            effects.add(new Effect.MotdChanged(seq, origin, c.faction(), m));
            audit(c.faction(), c.actor(), FactionAuditAction.MOTD_SET, m);
        }

        void transferOwnership(Intent.TransferOwnership c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            int newOwnerOrd = memberOrd(c.newOwner());
            if (newOwnerOrd < 0 || !state.ledger().has(newOwnerOrd)
                    || FactionHandle.ordinal(memberFactionHandle(newOwnerOrd)) != f.idx()) {
                reject(ReasonCode.NOT_MEMBER);
                return;
            }
            Rank[] ranks = f.ranks();
            int ownerRankIdx = RoleRules.ownerRankIndex(ranks);
            int officerRankIdx = RoleRules.officerRankIndex(ranks);
            if (ownerRankIdx < 0 || officerRankIdx < 0) {
                reject(ReasonCode.ROLE_FAILED);
                return;
            }
            UUID oldOwner = f.ownerId();
            int oldOwnerOrd = oldOwner == null ? -1 : memberOrd(oldOwner);
            if (oldOwnerOrd >= 0 && state.ledger().has(oldOwnerOrd)) {
                state = state.withLedger(state.ledger().withRankIdx(oldOwnerOrd, officerRankIdx));
                effects.add(new Effect.RankChanged(seq, origin, c.faction(), oldOwner, officerRankIdx));
            }
            state = state.withLedger(state.ledger().withRankIdx(newOwnerOrd, ownerRankIdx));
            replaceFaction(FactionEdit.withOwner(f, c.newOwner()));
            effects.add(new Effect.RankChanged(seq, origin, c.faction(), c.newOwner(), ownerRankIdx));
            effects.add(new Effect.OwnershipTransferred(seq, origin, c.faction(), oldOwner, c.newOwner()));
        }

        // ── membership / invites ──────────────────────────────────────────────────────────

        void joinFaction(Intent.JoinFaction c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            int ord = memberOrd(c.player());
            if (ord >= 0 && state.ledger().has(ord) && memberFactionHandle(ord) != NO_HANDLE) {
                reject(ReasonCode.ALREADY_IN_FACTION);
                return;
            }
            InviteTable.Invite invite = null;
            if (c.viaInviteId() == Intent.OPEN_JOIN) {
                if (!f.flag(Faction.FLAG_OPEN,
                        state.config().flagDefaults().defaultOf(Faction.FLAG_OPEN))) {
                    reject(ReasonCode.NO_INVITE_PENDING);
                    return;
                }
            } else {
                invite = state.invites().byId(c.viaInviteId());
                if (invite == null || invite.factionOrdinal() != f.idx()
                        || !invite.invitee().equals(c.player())
                        || !InviteRules.isActive(invite, state.config().limits().invitesTtlHours(),
                                epochMillis)) {
                    reject(ReasonCode.NOT_INVITED);
                    return;
                }
            }
            int max = state.config().limits().maxMembers();
            if (max > 0 && FactionAggregates.memberCount(state, c.faction()) >= max) {
                reject(ReasonCode.FACTION_FULL);
                return;
            }
            int newOrd = ensureMember(c.player(), "");
            int defaultRankIdx = RoleRules.defaultRankIndex(f.ranks());
            state = state.withLedger(state.ledger()
                    .withFactionHandle(newOrd, c.faction())
                    .withRankIdx(newOrd, Math.max(0, defaultRankIdx))
                    .withJoinedAt(newOrd, epochMillis));
            // Accept = remove the invite atomically with the join (TOCTOU-free).
            if (invite != null) {
                state = state.withInvites(state.invites().remove(invite.id()));
                effects.add(new Effect.InviteRemoved(seq, origin, c.faction(), c.player(),
                        Effect.INVITE_ACCEPTED));
            }
            effects.add(new Effect.MemberJoined(seq, origin, c.faction(), c.player()));
            notifyFaction(c.faction(), "member.player-joined", c.player().toString());
        }

        void leaveFaction(Intent.LeaveFaction c) {
            int ord = memberOrd(c.player());
            if (ord < 0 || !state.ledger().has(ord)
                    || FactionHandle.ordinal(memberFactionHandle(ord)) != FactionHandle.ordinal(c.faction())) {
                reject(ReasonCode.NOT_IN_FACTION);
                return;
            }
            state = state.withLedger(state.ledger().withFactionHandle(ord, NO_HANDLE).withRankIdx(ord, 0));
            effects.add(new Effect.MemberLeft(seq, origin, c.faction(), c.player(), false));
        }

        void kickMember(Intent.KickMember c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            if (c.actor().equals(c.target())) {
                reject(ReasonCode.CANNOT_KICK_SELF);
                return;
            }
            int actorOrd = memberOrd(c.actor());
            int targetOrd = memberOrd(c.target());
            if (actorOrd < 0 || FactionHandle.ordinal(memberFactionHandle(actorOrd)) != f.idx()) {
                reject(ReasonCode.NOT_IN_FACTION);
                return;
            }
            if (targetOrd < 0 || FactionHandle.ordinal(memberFactionHandle(targetOrd)) != f.idx()) {
                reject(ReasonCode.NOT_MEMBER);
                return;
            }
            if (c.target().equals(f.ownerId())) {
                reject(ReasonCode.CANNOT_KICK_LEADER);
                return;
            }
            Rank actorRank = rankOf(f, actorOrd);
            Rank targetRank = rankOf(f, targetOrd);
            if (!RoleRules.canManage(actorRank, targetRank)) {
                reject(ReasonCode.MUST_BE_OFFICER);
                return;
            }
            state = state.withLedger(state.ledger().withFactionHandle(targetOrd, NO_HANDLE)
                    .withRankIdx(targetOrd, 0));
            effects.add(new Effect.MemberLeft(seq, origin, c.faction(), c.target(), true));
            audit(c.faction(), c.actor(), FactionAuditAction.MEMBER_KICK, c.target().toString());
        }

        private Rank rankOf(Faction f, int memberOrdinal) {
            int idx = state.ledger().rankIdx(memberOrdinal);
            Rank[] ranks = f.ranks();
            return (idx >= 0 && idx < ranks.length) ? ranks[idx] : null;
        }

        void sendInvite(Intent.SendInvite c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            int inviterOrd = memberOrd(c.inviter());
            if (inviterOrd < 0 || FactionHandle.ordinal(memberFactionHandle(inviterOrd)) != f.idx()) {
                reject(ReasonCode.NOT_IN_FACTION);
                return;
            }
            Rank inviterRank = rankOf(f, inviterOrd);
            if (inviterRank == null || !inviterRank.isOfficerOrAbove()) {
                reject(ReasonCode.MUST_BE_OFFICER);
                return;
            }
            if (state.invites().has(f.idx(), c.invitee())) {
                reject(ReasonCode.ALREADY_INVITED);
                return;
            }
            long id = seq;
            state = state.withInvites(state.invites().add(
                    new InviteTable.Invite(id, f.idx(), c.inviter(), c.invitee(), epochMillis)));
            effects.add(new Effect.InviteCreated(seq, origin, c.faction(), c.invitee(), id));
            notify(c.invitee(), "invite.received", f.name());
        }

        void revokeInvite(Intent.RevokeInvite c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            InviteTable.Invite in = state.invites().find(f.idx(), c.invitee());
            if (in == null) {
                reject(ReasonCode.NOT_INVITED);
                return;
            }
            state = state.withInvites(state.invites().remove(in.id()));
            effects.add(new Effect.InviteRemoved(seq, origin, c.faction(), c.invitee(),
                    Effect.INVITE_REVOKED));
        }

        void declineInvite(Intent.DeclineInvite c) {
            Faction f = resolve(c.faction());
            int ord = f == null ? FactionHandle.ordinal(c.faction()) : f.idx();
            InviteTable.Invite in = state.invites().find(ord, c.invitee());
            if (in == null) {
                reject(ReasonCode.NOT_INVITED);
                return;
            }
            state = state.withInvites(state.invites().remove(in.id()));
            effects.add(new Effect.InviteRemoved(seq, origin, c.faction(), c.invitee(),
                    Effect.INVITE_DECLINED));
        }

        void declineAllInvites(Intent.DeclineAllInvites c) {
            InviteTable.Invite[] mine = state.invites().forInvitee(c.invitee());
            if (mine.length == 0) {
                return;
            }
            for (InviteTable.Invite in : mine) {
                state = state.withInvites(state.invites().remove(in.id()));
                effects.add(new Effect.InviteRemoved(seq, origin,
                        state.factions().handleOf(in.factionOrdinal()), c.invitee(),
                        Effect.INVITE_DECLINED));
            }
        }

        // ── roles ─────────────────────────────────────────────────────────────────────────

        void changeRank(int factionHandle, UUID actor, UUID target, boolean promote) {
            Faction f = resolve(factionHandle);
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            int actorOrd = memberOrd(actor);
            int targetOrd = memberOrd(target);
            if (actorOrd < 0 || FactionHandle.ordinal(memberFactionHandle(actorOrd)) != f.idx()) {
                reject(ReasonCode.NOT_IN_FACTION);
                return;
            }
            if (targetOrd < 0 || FactionHandle.ordinal(memberFactionHandle(targetOrd)) != f.idx()) {
                reject(ReasonCode.NOT_MEMBER);
                return;
            }
            Rank actorRank = rankOf(f, actorOrd);
            Rank targetRank = rankOf(f, targetOrd);
            if (!RoleRules.canManage(actorRank, targetRank)) {
                reject(ReasonCode.MUST_BE_OFFICER);
                return;
            }
            int newIdx = RoleRules.stepRank(f.ranks(), state.ledger().rankIdx(targetOrd), promote);
            if (newIdx < 0 || !RoleRules.canManage(actorRank, f.ranks()[newIdx])) {
                reject(ReasonCode.ROLE_FAILED);
                return;
            }
            state = state.withLedger(state.ledger().withRankIdx(targetOrd, newIdx));
            effects.add(new Effect.RankChanged(seq, origin, factionHandle, target, newIdx));
            audit(factionHandle, actor,
                    promote ? FactionAuditAction.MEMBER_PROMOTE : FactionAuditAction.MEMBER_DEMOTE,
                    target.toString());
        }

        void createRole(Intent.CreateRole c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            if (!state.config().role().customEnabled()) {
                reject(ReasonCode.ROLE_FEATURE_DISABLED);
                return;
            }
            if (Rank.isProtectedBuiltin(c.name()) || RoleRules.indexByName(f.ranks(), c.name()) >= 0) {
                reject(ReasonCode.ROLE_NAME_TAKEN);
                return;
            }
            if (!RoleRules.validCustomPriority(state.config().role(), c.priority())) {
                reject(ReasonCode.ROLE_PRIORITY_OUT_OF_RANGE);
                return;
            }
            int actorOrd = memberOrd(c.actor());
            Rank actorRank = actorOrd >= 0 ? rankOf(f, actorOrd) : null;
            if (actorRank == null || actorRank.priority() <= c.priority()) {
                reject(ReasonCode.ROLE_ACTOR_RANK_INSUFFICIENT);
                return;
            }
            if (!RoleRules.withinCustomRoleLimit(state.config().role(), f.ranks())) {
                reject(ReasonCode.ROLE_LIMIT_REACHED);
                return;
            }
            String prefix = c.prefix();
            if (prefix != null && !prefix.isEmpty() && !state.config().role().prefixesEnabled()) {
                reject(ReasonCode.ROLE_PREFIX_DISABLED);
                return;
            }
            String roleId = newUuid().toString();
            Rank[] ranks = append(f.ranks(), new Rank(roleId, c.name(), emptyToNull(prefix), c.priority()));
            replaceFaction(FactionEdit.withRanks(f, ranks));
            effects.add(new Effect.RoleCreated(seq, origin, c.faction(), roleId, c.name(), c.priority()));
            audit(c.faction(), c.actor(), FactionAuditAction.ROLE_CREATE, c.name());
        }

        void renameRole(Intent.RenameRole c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            if (!state.config().role().overridesEnabled() && !state.config().role().customEnabled()) {
                reject(ReasonCode.ROLE_FEATURE_DISABLED);
                return;
            }
            int idx = RoleRules.indexByName(f.ranks(), c.oldName());
            if (idx < 0 || Rank.isProtectedBuiltin(c.oldName())) {
                reject(ReasonCode.ROLE_FAILED);
                return;
            }
            if (RoleRules.indexByName(f.ranks(), c.newName()) >= 0) {
                reject(ReasonCode.ROLE_NAME_TAKEN);
                return;
            }
            Rank[] ranks = f.ranks().clone();
            Rank old = ranks[idx];
            ranks[idx] = new Rank(old.id(), c.newName(), old.prefix(), old.priority());
            replaceFaction(FactionEdit.withRanks(f, ranks));
            effects.add(new Effect.RoleRenamed(seq, origin, c.faction(), old.id(), c.oldName(),
                    c.newName()));
            audit(c.faction(), c.actor(), FactionAuditAction.ROLE_RENAME, c.newName());
        }

        void setRolePriority(Intent.SetRolePriority c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            int idx = RoleRules.indexByName(f.ranks(), c.roleName());
            if (idx < 0 || Rank.isProtectedBuiltin(c.roleName())) {
                reject(ReasonCode.ROLE_FAILED);
                return;
            }
            if (!RoleRules.validCustomPriority(state.config().role(), c.priority())) {
                reject(ReasonCode.ROLE_PRIORITY_OUT_OF_RANGE);
                return;
            }
            Rank[] ranks = f.ranks().clone();
            Rank old = ranks[idx];
            ranks[idx] = new Rank(old.id(), old.name(), old.prefix(), c.priority());
            replaceFaction(FactionEdit.withRanks(f, ranks));
            effects.add(new Effect.RoleRePrioritized(seq, origin, c.faction(), old.id(), c.priority()));
            audit(c.faction(), c.actor(), FactionAuditAction.ROLE_PRIORITY_SET, c.roleName());
        }

        void setRolePrefix(Intent.SetRolePrefix c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            if (!state.config().role().prefixesEnabled()) {
                reject(ReasonCode.ROLE_PREFIX_DISABLED);
                return;
            }
            int idx = RoleRules.indexByName(f.ranks(), c.roleName());
            if (idx < 0) {
                reject(ReasonCode.ROLE_FAILED);
                return;
            }
            int maxLen = state.config().role().maxPrefixLength();
            String prefix = emptyToNull(c.prefix());
            if (prefix != null && maxLen > 0 && prefix.length() > maxLen) {
                reject(ReasonCode.ROLE_FAILED);
                return;
            }
            Rank[] ranks = f.ranks().clone();
            Rank old = ranks[idx];
            ranks[idx] = new Rank(old.id(), old.name(), prefix, old.priority());
            replaceFaction(FactionEdit.withRanks(f, ranks));
            effects.add(new Effect.RolePrefixSet(seq, origin, c.faction(), old.id(),
                    prefix == null ? "" : prefix));
            audit(c.faction(), c.actor(), FactionAuditAction.ROLE_PREFIX_SET, c.roleName());
        }

        void deleteRole(Intent.DeleteRole c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            int idx = RoleRules.indexByName(f.ranks(), c.roleName());
            if (idx < 0 || Rank.isProtectedBuiltin(c.roleName())) {
                reject(ReasonCode.ROLE_FAILED);
                return;
            }
            // Refuse if any member currently uses the rank.
            if (rankInUse(f.idx(), idx)) {
                reject(ReasonCode.ROLE_FAILED);
                return;
            }
            String roleId = f.ranks()[idx].id();
            Rank[] ranks = removeAt(f.ranks(), idx);
            // Shift down any member rankIdx above the removed slot.
            reindexRanksAfterRemoval(f.idx(), idx);
            replaceFaction(FactionEdit.withRanks(f, ranks));
            effects.add(new Effect.RoleDeleted(seq, origin, c.faction(), roleId));
            audit(c.faction(), c.actor(), FactionAuditAction.ROLE_DELETE, c.roleName());
        }

        void assignRole(Intent.AssignRole c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            int targetOrd = memberOrd(c.target());
            if (targetOrd < 0 || FactionHandle.ordinal(memberFactionHandle(targetOrd)) != f.idx()) {
                reject(ReasonCode.NOT_MEMBER);
                return;
            }
            int roleIdx = RoleRules.indexByName(f.ranks(), c.roleName());
            if (roleIdx < 0) {
                reject(ReasonCode.ROLE_FAILED);
                return;
            }
            int actorOrd = memberOrd(c.actor());
            Rank actorRank = actorOrd >= 0 ? rankOf(f, actorOrd) : null;
            if (!RoleRules.canManage(actorRank, f.ranks()[roleIdx])) {
                reject(ReasonCode.ROLE_ACTOR_RANK_INSUFFICIENT);
                return;
            }
            state = state.withLedger(state.ledger().withRankIdx(targetOrd, roleIdx));
            effects.add(new Effect.RoleAssigned(seq, origin, c.faction(), c.target(),
                    f.ranks()[roleIdx].id()));
            audit(c.faction(), c.actor(), FactionAuditAction.ROLE_ASSIGN, c.target().toString());
        }

        private boolean rankInUse(int factionOrd, int rankIdx) {
            PlayerLedger l = state.ledger();
            int hw = l.highWater();
            for (int i = 0; i < hw; i++) {
                if (l.has(i) && FactionHandle.ordinal(l.factionHandle(i)) == factionOrd
                        && l.rankIdx(i) == rankIdx) {
                    return true;
                }
            }
            return false;
        }

        private void reindexRanksAfterRemoval(int factionOrd, int removedIdx) {
            PlayerLedger l = state.ledger();
            int hw = l.highWater();
            for (int i = 0; i < hw; i++) {
                if (l.has(i) && FactionHandle.ordinal(l.factionHandle(i)) == factionOrd
                        && l.rankIdx(i) > removedIdx) {
                    l = l.withRankIdx(i, l.rankIdx(i) - 1);
                }
            }
            state = state.withLedger(l);
        }

        // ── claims ──────────────────────────────────────────────────────────────────────

        void claimChunks(Intent.ClaimChunks c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            int actorOrd = memberOrd(c.player());
            if (actorOrd < 0 || FactionHandle.ordinal(memberFactionHandle(actorOrd)) != f.idx()) {
                reject(ReasonCode.NOT_IN_FACTION);
                return;
            }
            int claimed = 0;
            ReasonCode firstErr = null;
            long[] keys = c.keys();
            for (long key : keys) {
                Faction cur = resolve(c.faction());
                ClaimRules.ClaimDecision dec = ClaimRules.validateClaim(state, c.faction(),
                        c.worldIdx(), key, cur.landCount(), tick, epochMillis);
                if (!dec.ok()) {
                    if (firstErr == null) {
                        firstErr = dec.reason();
                    }
                    continue;
                }
                applyClaim(c.faction(), c.worldIdx(), key, dec.victimHandle());
                claimed++;
            }
            if (claimed == 0 && firstErr != null) {
                reject(firstErr);
            }
        }

        /** Applies one validated claim, maintaining atlas + reverse index + landCount + victim. */
        private void applyClaim(int factionHandle, int worldIdx, long key, int victimHandle) {
            int prevOwner = state.claims().ownerAt(worldIdx, key);
            if (victimHandle != NO_HANDLE) {
                Faction victim = resolve(victimHandle);
                if (victim != null) {
                    replaceFaction(FactionEdit.withLand(victim, victim.landCount() - 1,
                            victim.claims().remove(worldIdx, key)));
                }
                prevOwner = victimHandle;
            }
            state = state.withClaims(state.claims().withClaim(worldIdx, key, factionHandle));
            Faction f = resolve(factionHandle);
            replaceFaction(FactionEdit.withLand(f, f.landCount() + 1, f.claims().add(worldIdx, key)));
            effects.add(new Effect.ClaimSet(seq, origin, worldIdx, key, factionHandle, prevOwner));
        }

        void unclaimChunks(Intent.UnclaimChunks c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            int unclaimed = 0;
            ReasonCode firstErr = null;
            for (long key : c.keys()) {
                ReasonCode err = ClaimRules.validateUnclaim(state, c.faction(), c.worldIdx(), key);
                if (err != null) {
                    if (firstErr == null) {
                        firstErr = err;
                    }
                    continue;
                }
                applyUnclaim(c.faction(), c.worldIdx(), key);
                unclaimed++;
            }
            if (unclaimed == 0 && firstErr != null) {
                reject(firstErr);
            }
        }

        private void applyUnclaim(int factionHandle, int worldIdx, long key) {
            state = state.withClaims(state.claims().withoutClaim(worldIdx, key));
            Faction f = resolve(factionHandle);
            if (f != null) {
                replaceFaction(FactionEdit.withLand(f, f.landCount() - 1,
                        f.claims().remove(worldIdx, key)));
            }
            effects.add(new Effect.ClaimRemoved(seq, origin, worldIdx, key, factionHandle));
        }

        void adminClaimChunks(Intent.AdminClaimChunks c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            for (long key : c.keys()) {
                int existing = state.claims().ownerAt(c.worldIdx(), key);
                if (existing != NO_HANDLE) {
                    // Admin claim only fills unclaimed land (parity); skip already-owned.
                    continue;
                }
                applyClaim(c.faction(), c.worldIdx(), key, NO_HANDLE);
            }
        }

        void adminUnclaimChunks(Intent.AdminUnclaimChunks c) {
            for (long key : c.keys()) {
                int owner = state.claims().ownerAt(c.worldIdx(), key);
                if (owner == NO_HANDLE || FactionHandle.ordinal(owner) != FactionHandle.ordinal(c.faction())) {
                    continue;
                }
                applyUnclaim(c.faction(), c.worldIdx(), key);
            }
        }

        // ── zones ─────────────────────────────────────────────────────────────────────────

        void zonePage(int zoneOrdinal, int worldIdx, long[] keys, int cursor, UUID actor) {
            if (zoneOrdinal != FactionHandle.SAFEZONE_ORDINAL
                    && zoneOrdinal != FactionHandle.WARZONE_ORDINAL) {
                reject(ReasonCode.FLAG_INVALID);
                return;
            }
            int zoneHandle = FactionHandle.handle(state.factions().generationAt(zoneOrdinal), zoneOrdinal);
            int end = Math.min(keys.length, cursor + PAGE_SIZE);
            for (int i = cursor; i < end; i++) {
                setZoneChunk(zoneOrdinal, zoneHandle, worldIdx, keys[i]);
            }
            if (end < keys.length) {
                continuation(new Intent.ZonePage(zoneOrdinal, worldIdx, keys, end, actor));
            }
        }

        private void setZoneChunk(int zoneOrdinal, int zoneHandle, int worldIdx, long key) {
            int prev = state.claims().ownerAt(worldIdx, key);
            if (prev == zoneHandle) {
                return;
            }
            if (prev != NO_HANDLE) {
                int prevOrd = FactionHandle.ordinal(prev);
                if (FactionHandle.isNormalOrdinal(prevOrd)) {
                    Faction victim = resolve(prev);
                    if (victim != null) {
                        replaceFaction(FactionEdit.withLand(victim, victim.landCount() - 1,
                                victim.claims().remove(worldIdx, key)));
                    }
                } else {
                    // moving from the other zone
                    state = adjustZoneStats(prevOrd, -1);
                }
            }
            state = state.withClaims(state.claims().withClaim(worldIdx, key, zoneHandle));
            state = adjustZoneStats(zoneOrdinal, +1);
            effects.add(new Effect.ZoneSet(seq, origin, zoneOrdinal, worldIdx, key, prev));
        }

        private KernelState adjustZoneStats(int zoneOrdinal, int delta) {
            ZoneStats z = state.zones();
            if (zoneOrdinal == FactionHandle.SAFEZONE_ORDINAL) {
                return state.withZones(z.withSafezoneDelta(delta));
            }
            if (zoneOrdinal == FactionHandle.WARZONE_ORDINAL) {
                return state.withZones(z.withWarzoneDelta(delta));
            }
            return state;
        }

        void removeZoneChunk(Intent.RemoveZoneChunk c) {
            // Only the SAFEZONE/WARZONE sentinels are zones; a normal ordinal here would strip an
            // atlas claim without decrementing that faction's landCount (aggregate desync).
            if (c.zoneOrdinal() != FactionHandle.SAFEZONE_ORDINAL
                    && c.zoneOrdinal() != FactionHandle.WARZONE_ORDINAL) {
                reject(ReasonCode.FLAG_INVALID);
                return;
            }
            int owner = state.claims().ownerAt(c.worldIdx(), c.key());
            if (owner == NO_HANDLE || FactionHandle.ordinal(owner) != c.zoneOrdinal()) {
                return;
            }
            state = state.withClaims(state.claims().withoutClaim(c.worldIdx(), c.key()));
            state = adjustZoneStats(c.zoneOrdinal(), -1);
            effects.add(new Effect.ZoneRemoved(seq, origin, c.zoneOrdinal(), c.worldIdx(), c.key()));
        }

        // ── relations ──────────────────────────────────────────────────────────────────────

        void declareRelation(Intent.DeclareRelation c) {
            if (!RelationRules.isValidFactionRelation(c.kind())) {
                reject(ReasonCode.RELATION_SET_FAILED);
                return;
            }
            Faction a = resolve(c.actorFaction());
            Faction b = resolve(c.targetFaction());
            if (a == null || b == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            if (a.idx() == b.idx()) {
                reject(ReasonCode.RELATION_SELF);
                return;
            }
            int actorOrd = memberOrd(c.actor());
            if (actorOrd < 0 || FactionHandle.ordinal(memberFactionHandle(actorOrd)) != a.idx()) {
                reject(ReasonCode.NOT_IN_FACTION);
                return;
            }
            Rank actorRank = rankOf(a, actorOrd);
            if (actorRank == null || !actorRank.isOfficerOrAbove()) {
                reject(ReasonCode.MUST_BE_OFFICER);
                return;
            }
            byte kind = (byte) c.kind();
            int aOrd = a.idx();
            int bOrd = b.idx();
            byte prevWishAB = a.relationDeclared(bOrd);
            if (!RelationRules.withinRelationLimit(state.config().relation(), a.relOutKind(),
                    a.relOut().length, prevWishAB, kind)) {
                reject(ReasonCode.RELATION_SET_FAILED);
                return;
            }
            byte prevEff = a.relationEffective(bOrd);

            // Outgoing wish A→B.
            RelationEdges.Edges outAB = RelationEdges.with(a.relOut(), a.relOutKind(),
                    a.relOut().length, bOrd, kind);
            byte wishBA;
            if (RelationRules.isBilateral(c.kind())) {
                RelationEdges.Edges outBA = RelationEdges.with(b.relOut(), b.relOutKind(),
                        b.relOut().length, aOrd, kind);
                b = FactionEdit.withRelations(b, outBA.ordinals(), outBA.kinds(), b.relEff(),
                        b.relEffKind());
                wishBA = kind == RelationKind.NEUTRAL ? RelationKind.NEUTRAL : kind;
            } else {
                wishBA = b.relationDeclared(aOrd);
            }
            a = FactionEdit.withRelations(a, outAB.ordinals(), outAB.kinds(), a.relEff(), a.relEffKind());

            byte newEff = RelationRules.effectiveKind(kind, wishBA);
            RelationEdges.Edges effAB = RelationEdges.with(a.relEff(), a.relEffKind(),
                    a.relEff().length, bOrd, newEff);
            a = FactionEdit.withRelations(a, a.relOut(), a.relOutKind(), effAB.ordinals(), effAB.kinds());
            RelationEdges.Edges effBA = RelationEdges.with(b.relEff(), b.relEffKind(),
                    b.relEff().length, aOrd, newEff);
            b = FactionEdit.withRelations(b, b.relOut(), b.relOutKind(), effBA.ordinals(), effBA.kinds());

            state = state.withFactions(state.factions().replace(aOrd, a).replace(bOrd, b));

            effects.add(new Effect.RelationDeclared(seq, origin, c.actorFaction(), c.targetFaction(),
                    c.kind()));
            if (newEff != prevEff) {
                effects.add(new Effect.RelationEffective(seq, origin, c.actorFaction(),
                        c.targetFaction(), newEff, prevEff));
            }
            audit(c.actorFaction(), c.actor(), FactionAuditAction.RELATION_CHANGE,
                    RelationKind.name(kind) + " with " + b.name());
            if (c.kind() == RelationKind.ENEMY
                    || (newEff == RelationKind.ALLY) || (newEff == RelationKind.TRUCE)) {
                effects.add(new Effect.Broadcast(seq, origin, Effect.SCOPE_SERVER,
                        MessageKey.of("relations.announce"),
                        new String[] {a.name(), b.name(), RelationKind.name((byte) c.kind())}));
            }
        }

        // ── power ─────────────────────────────────────────────────────────────────────────

        private void settleMember(int ord, boolean online) {
            PowerConfig pc = power();
            double settled = PowerMath.settle(pc, online, state.ledger().powerBase(ord),
                    state.ledger().powerAsOfTick(ord), state.ledger().powerFrozen(ord), tick);
            state = state.withLedger(state.ledger().withPower(ord, settled, tick));
        }

        private PowerMath.PowerResult applyPower(int ord, int source, double baseDelta,
                                                 boolean bypassFreeze, int worldIdx, int zoneCtx,
                                                 String reason) {
            boolean online = state.online().contains(ord);
            settleMember(ord, online);
            double before = state.ledger().powerBase(ord);
            boolean frozen = state.ledger().powerFrozen(ord);
            PowerMath.PowerResult r = PowerMath.apply(power(), baked(), before, frozen, source,
                    baseDelta, bypassFreeze, worldIdx, zoneCtx, reason);
            if (r.changed()) {
                state = state.withLedger(state.ledger().withPower(ord, r.after(), tick));
            }
            return r;
        }

        private int zoneCtx(int worldIdx, long key, int deadFactionHandle) {
            int owner = state.claims().ownerAt(worldIdx, key);
            if (owner == NO_HANDLE) {
                return PowerMath.ZONE_WILDERNESS;
            }
            int oOrd = FactionHandle.ordinal(owner);
            if (oOrd == FactionHandle.SAFEZONE_ORDINAL) {
                return PowerMath.ZONE_SAFEZONE;
            }
            if (oOrd == FactionHandle.WARZONE_ORDINAL) {
                return PowerMath.ZONE_WARZONE;
            }
            if (deadFactionHandle != NO_HANDLE && oOrd == FactionHandle.ordinal(deadFactionHandle)) {
                return PowerMath.ZONE_OWN_CLAIMED;
            }
            return PowerMath.ZONE_ENEMY_CLAIMED;
        }

        void recordDeath(Intent.RecordDeath d) {
            PowerConfig pc = power();
            int deadOrd = memberOrd(d.dead());
            int deadFactionHandle = deadOrd >= 0 && state.ledger().has(deadOrd)
                    ? memberFactionHandle(deadOrd) : NO_HANDLE;
            int zone = zoneCtx(d.worldIdx(), d.chunkKey(), deadFactionHandle);
            if (state.config().zones().safeZoneEnabled() && zone == PowerMath.ZONE_SAFEZONE) {
                return; // no power change in safezone
            }
            // Grace: the kernel has no server-start clock, so referenceStart = 0 (grace never
            // blocks — documented deviation). The formula itself is pinned in PowerMathTest.
            if (PowerMath.inGracePeriod(pc, epochMillis, 0L)) {
                return;
            }
            double victimBefore = 0.0;
            if (deadOrd >= 0 && state.ledger().has(deadOrd)) {
                int prevStreak = state.ledger().deathStreak(deadOrd);
                long lastDeath = state.ledger().lastDeathAt(deadOrd);
                int streak = PowerMath.nextStreak(pc, prevStreak, lastDeath, epochMillis);
                double loss = PowerMath.deathLoss(pc, streak);
                state = state.withLedger(state.ledger().withDeath(deadOrd, streak, epochMillis));
                PowerMath.PowerResult r = applyPower(deadOrd, PowerMath.SRC_DEATH, loss, false,
                        d.worldIdx(), zone, "DEATH");
                victimBefore = r.before();
                if (r.changed()) {
                    effects.add(new Effect.PowerChanged(seq, origin, d.dead(), r.before(), r.after(),
                            PowerMath.SRC_DEATH, r.reasonCode()));
                }
                effects.add(new Effect.DeathStreakAdvanced(seq, origin, d.dead(), streak));
                if (streak > 0) {
                    notify(d.dead(), "power.death-streak-penalty",
                            Integer.toString(streak + 1), fmt1(Math.abs(r.effectiveDelta())));
                } else {
                    notify(d.dead(), "power.lost-on-death", fmt1(Math.abs(r.effectiveDelta())));
                }
            }
            UUID killer = d.killer();
            if (killer != null && pc.gainOnKillEnabled()) {
                int killerOrd = memberOrd(killer);
                if (killerOrd >= 0 && state.ledger().has(killerOrd)) {
                    boolean online = state.online().contains(killerOrd);
                    settleMember(killerOrd, online);
                    double killerPower = state.ledger().powerBase(killerOrd);
                    double gain = PowerMath.killGain(pc, victimBefore, killerPower);
                    PowerMath.PowerResult kr = applyPower(killerOrd, PowerMath.SRC_KILL, gain, false,
                            d.worldIdx(), zone, "KILL");
                    if (kr.changed()) {
                        effects.add(new Effect.PowerChanged(seq, origin, killer, kr.before(),
                                kr.after(), PowerMath.SRC_KILL, kr.reasonCode()));
                        notify(killer, "power.kill-gained", fmt1(Math.abs(kr.effectiveDelta())));
                    }
                }
            }
        }

        void powerTick(Intent.PowerTick t) {
            // Lazy accrual means online members regen continuously via powerAt; the tick advances
            // the state tick (done in apply()) and recomputes raidable on the (here: all-normal)
            // faction set — the reference's O(dirty) raidable pass.
            recomputeRaidableAll();
        }

        private void recomputeRaidableAll() {
            FactionArena arena = state.factions();
            int hw = arena.highWater();
            for (int ord = FactionHandle.FIRST_NORMAL_ORDINAL; ord < hw; ord++) {
                Faction f = arena.at(ord);
                if (f == null || !f.isNormal()) {
                    continue;
                }
                double totalPower = FactionAggregates.totalPower(state, f, tick, epochMillis);
                int maxLand = ClaimRules.computeMaxLand(state.config().land(), totalPower);
                boolean nowRaidable = f.landCount() > maxLand;
                if (nowRaidable != f.raidable()) {
                    Faction nf = FactionEdit.withRaidable(f, nowRaidable);
                    state = state.withFactions(state.factions().replace(ord, nf));
                    arena = state.factions();
                    effects.add(new Effect.RaidableChanged(seq, origin,
                            state.factions().handleOf(ord), nowRaidable));
                    notifyFaction(state.factions().handleOf(ord),
                            nowRaidable ? "raidable.became-raidable" : "raidable.no-longer-raidable");
                }
            }
        }

        void buyPower(Intent.BuyPower c) {
            PowerConfig pc = power();
            if (!pc.buyEnabled()) {
                reject(ReasonCode.POWER_BUY_DISABLED);
                return;
            }
            if (!(c.points() > 0.0) || c.points() > pc.buyMaxPerPurchase()) {
                reject(ReasonCode.POWER_BUY_INVALID_AMOUNT);
                return;
            }
            int ord = ensureMember(c.player(), "");
            PowerMath.PowerResult r = applyPower(ord, PowerMath.SRC_BUY, c.points(), false, -1,
                    PowerMath.ZONE_WILDERNESS, "BUY");
            if (r.blockedByFreeze()) {
                reject(ReasonCode.POWER_FROZEN);
                effects.add(new Effect.EscrowRefund(seq, origin, c.escrowId(), c.player(), c.cost()));
                return;
            }
            double delivered = Math.max(0.0, r.effectiveDelta());
            if (r.changed()) {
                effects.add(new Effect.PowerChanged(seq, origin, c.player(), r.before(), r.after(),
                        PowerMath.SRC_BUY, r.reasonCode()));
            }
            if (delivered + PowerMath.NO_CHANGE_EPSILON < c.points()) {
                double refund = MoneyMath.round2((c.points() - delivered) * pc.buyCostPerPoint());
                if (refund > 0.0) {
                    effects.add(new Effect.EscrowRefund(seq, origin, c.escrowId(), c.player(), refund));
                }
            }
        }

        void adminPower(UUID target, int source, double amount, UUID actor, String reason) {
            int ord = ensureMember(target, "");
            double baseDelta;
            if (source == PowerMath.SRC_ADMIN_SET) {
                settleMember(ord, state.online().contains(ord));
                baseDelta = amount - state.ledger().powerBase(ord);
            } else if (source == PowerMath.SRC_ADMIN_REMOVE) {
                baseDelta = -Math.abs(amount);
            } else if (source == PowerMath.SRC_ADMIN_RESET) {
                settleMember(ord, state.online().contains(ord));
                baseDelta = power().maxPower() - state.ledger().powerBase(ord);
            } else { // ADMIN_ADD
                baseDelta = amount;
            }
            boolean bypass = power().freezeAllowAdminBypass();
            PowerMath.PowerResult r = applyPower(ord, source, baseDelta, bypass, -1,
                    PowerMath.ZONE_WILDERNESS, PowerMath.sourceName(source)
                            + (reason == null || reason.isEmpty() ? "" : ":" + reason));
            if (r.changed()) {
                effects.add(new Effect.PowerChanged(seq, origin, target, r.before(), r.after(),
                        source, r.reasonCode()));
            }
        }

        void setPowerFrozen(Intent.SetPowerFrozen c) {
            int ord = ensureMember(c.target(), "");
            // Freeze toggle is an epoch boundary: settle before flipping the flag.
            settleMember(ord, state.online().contains(ord));
            state = state.withLedger(state.ledger().withPowerFrozen(ord, c.frozen()));
            effects.add(new Effect.PowerFrozenChanged(seq, origin, c.target(), c.frozen()));
        }

        // ── economy ────────────────────────────────────────────────────────────────────────

        void creditBank(Intent.CreditBank c) {
            Faction f = resolve(c.faction());
            double amount = MoneyMath.round2(c.amount());
            if (f == null) {
                // Faction vanished after the Vault withdraw — refund the wallet (AM-7).
                effects.add(new Effect.EscrowRefund(seq, origin, c.escrowId(), c.actor(), amount));
                return;
            }
            double balance = MoneyMath.round2(f.bank() + amount);
            replaceFaction(FactionEdit.withBank(f, balance));
            effects.add(new Effect.BankChanged(seq, origin, c.faction(), amount, balance,
                    Effect.TX_DEPOSIT, c.actor(), NO_HANDLE, "Player deposit"));
            audit(c.faction(), c.actor(), FactionAuditAction.BANK_DEPOSIT, fmt2(amount));
        }

        void requestBankWithdrawal(Intent.RequestBankWithdrawal c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            double amount = MoneyMath.round2(c.amount());
            ReasonCode err = EconomyRules.validateWithdraw(state.config().economy(), f.bank(), amount);
            if (err != null) {
                reject(err);
                return;
            }
            double balance = MoneyMath.round2(f.bank() - amount);
            replaceFaction(FactionEdit.withBank(f, balance));
            long escrowId = seq;
            state = state.withEscrows(state.escrows().open(new EscrowTable.Escrow(escrowId,
                    EscrowTable.KIND_WITHDRAW, c.actor(), f.idx(), amount, epochMillis)));
            effects.add(new Effect.BankChanged(seq, origin, c.faction(), -amount, balance,
                    Effect.TX_WITHDRAW, c.actor(), NO_HANDLE, "Player withdraw"));
            effects.add(new Effect.PayoutRequested(seq, origin, escrowId, c.actor(), amount));
            audit(c.faction(), c.actor(), FactionAuditAction.BANK_WITHDRAW, fmt2(amount));
        }

        void settleEscrow(Intent.SettleEscrow c) {
            EscrowTable.Escrow e = state.escrows().byId(c.escrowId());
            if (e == null) {
                return;
            }
            state = state.withEscrows(state.escrows().settle(c.escrowId()));
            if (c.outcome() == Intent.ESCROW_FAILED) {
                if (e.kind() == EscrowTable.KIND_WITHDRAW) {
                    // Vault deposit failed — re-credit the bank (conservation).
                    Faction f = state.factions().at(e.factionOrdinal());
                    if (f != null) {
                        double balance = MoneyMath.round2(f.bank() + e.amount());
                        replaceFaction(FactionEdit.withBank(f, balance));
                        effects.add(new Effect.BankChanged(seq, origin,
                                state.factions().handleOf(e.factionOrdinal()), e.amount(), balance,
                                Effect.TX_DEPOSIT, e.player(), NO_HANDLE, "Withdraw rollback"));
                    }
                } else {
                    effects.add(new Effect.EscrowRefund(seq, origin, e.id(), e.player(), e.amount()));
                }
            }
        }

        void transferBank(Intent.TransferBank c) {
            Faction from = resolve(c.from());
            Faction to = resolve(c.to());
            if (from == null || to == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            // A self-transfer would double-apply the same slot (credit computed from the pre-debit
            // balance overwrites the debit), fabricating money — reject before touching the bank.
            if (from.idx() == to.idx()) {
                reject(ReasonCode.INVALID_AMOUNT);
                return;
            }
            double amount = MoneyMath.round2(c.amount());
            ReasonCode err = EconomyRules.validateTransfer(state.config().economy(), from.bank(), amount);
            if (err != null) {
                reject(err);
                return;
            }
            double fromBal = MoneyMath.round2(from.bank() - amount);
            double toBal = MoneyMath.round2(to.bank() + amount);
            replaceFaction(FactionEdit.withBank(from, fromBal));
            replaceFaction(FactionEdit.withBank(resolve(c.to()), toBal));
            effects.add(new Effect.BankChanged(seq, origin, c.from(), -amount, fromBal,
                    Effect.TX_TRANSFER, c.actor(), c.to(), "Transfer out"));
            effects.add(new Effect.BankChanged(seq, origin, c.to(), amount, toBal,
                    Effect.TX_TRANSFER, c.actor(), c.from(), "Transfer in"));
            audit(c.from(), c.actor(), FactionAuditAction.BANK_TRANSFER, fmt2(amount));
        }

        void taxSweepPage(int sweepTick, int cursor) {
            FactionArena arena = state.factions();
            int hw = arena.highWater();
            int processed = 0;
            int ord = cursor;
            for (; ord < hw && processed < PAGE_SIZE; ord++) {
                Faction f = arena.at(ord);
                if (f == null || !f.isNormal()) {
                    continue;
                }
                processed++;
                double tax = dev.fablemc.factions.kernel.rules.TaxMath
                        .taxFor(state.config().economy(), f.bank());
                if (tax <= 0.0) {
                    continue;
                }
                double newBank = dev.fablemc.factions.kernel.rules.TaxMath.bankAfter(f.bank(), tax);
                Faction nf = FactionEdit.withBank(f, newBank);
                state = state.withFactions(state.factions().replace(ord, nf));
                arena = state.factions();
                effects.add(new Effect.TaxCharged(seq, origin, state.factions().handleOf(ord), tax,
                        newBank));
                effects.add(new Effect.BankChanged(seq, origin, state.factions().handleOf(ord), -tax,
                        newBank, Effect.TX_TAX, null, NO_HANDLE, "Periodic bank tax"));
                notifyFaction(state.factions().handleOf(ord), "bank.tax-charged", fmt2(tax));
            }
            if (ord < hw) {
                continuation(new Intent.TaxSweepPage(sweepTick, ord));
            }
        }

        // ── homes / warps / chests ───────────────────────────────────────────────────────

        void setHome(Intent.SetHome c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            replaceFaction(FactionEdit.withHome(f,
                    new Home(c.worldIdx(), c.x(), c.y(), c.z(), c.yaw(), c.pitch())));
            effects.add(new Effect.HomeSet(seq, origin, c.faction()));
        }

        void unsetHome(Intent.UnsetHome c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            replaceFaction(FactionEdit.withHome(f, null));
            effects.add(new Effect.HomeCleared(seq, origin, c.faction()));
        }

        void setWarp(Intent.SetWarp c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            ReasonCode err = TravelRules.validateSetWarp(state.config(), state.warps(), f.idx(), c.name());
            if (err != null) {
                reject(err);
                return;
            }
            Warp existing = state.warps().get(f.idx(), c.name());
            long createdAt = existing == null ? epochMillis : existing.createdAt();
            String password = existing == null ? null : existing.password();
            double useCost = existing == null ? 0.0 : existing.useCost();
            Warp w = new Warp(c.name(), c.worldIdx(), c.x(), c.y(), c.z(), c.yaw(), c.pitch(),
                    c.creator(), createdAt, password, useCost);
            state = state.withWarps(state.warps().set(f.idx(), w));
            effects.add(new Effect.WarpSet(seq, origin, c.faction(), c.name()));
        }

        void deleteWarp(Intent.DeleteWarp c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            if (TravelRules.requireWarp(state.warps(), f.idx(), c.name()) != null) {
                reject(ReasonCode.WARP_NOT_FOUND);
                return;
            }
            state = state.withWarps(state.warps().delete(f.idx(), c.name()));
            effects.add(new Effect.WarpDeleted(seq, origin, c.faction(), c.name()));
        }

        void setWarpPassword(Intent.SetWarpPassword c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            Warp w = state.warps().get(f.idx(), c.name());
            if (w == null) {
                reject(ReasonCode.WARP_NOT_FOUND);
                return;
            }
            String pw = (c.password() == null || c.password().isEmpty()) ? null : c.password();
            Warp nw = new Warp(w.name(), w.worldIdx(), w.x(), w.y(), w.z(), w.yaw(), w.pitch(),
                    w.creator(), w.createdAt(), pw, w.useCost());
            state = state.withWarps(state.warps().set(f.idx(), nw));
            effects.add(new Effect.WarpPasswordSet(seq, origin, c.faction(), c.name(), pw == null));
        }

        void setWarpCost(Intent.SetWarpCost c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            Warp w = state.warps().get(f.idx(), c.name());
            if (w == null) {
                reject(ReasonCode.WARP_NOT_FOUND);
                return;
            }
            double cost = TravelRules.clampCost(c.cost());
            Warp nw = new Warp(w.name(), w.worldIdx(), w.x(), w.y(), w.z(), w.yaw(), w.pitch(),
                    w.creator(), w.createdAt(), w.password(), cost);
            state = state.withWarps(state.warps().set(f.idx(), nw));
            effects.add(new Effect.WarpCostSet(seq, origin, c.faction(), c.name(), cost));
        }

        void createChest(Intent.CreateChest c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            ReasonCode err = ChestRules.validateCreate(state.config(), state.chests(), f.idx(), c.name());
            if (err != null) {
                reject(err);
                return;
            }
            state = state.withChests(state.chests().set(f.idx(),
                    new ChestRef(c.name(), ChestRef.EMPTY_BLOB, epochMillis)));
            effects.add(new Effect.ChestCreated(seq, origin, c.faction(), c.name()));
        }

        void deleteChest(Intent.DeleteChest c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            if (!ChestRules.exists(state.chests(), f.idx(), c.name())) {
                return;
            }
            state = state.withChests(state.chests().delete(f.idx(), c.name()));
            effects.add(new Effect.ChestDeleted(seq, origin, c.faction(), c.name()));
        }

        void commitChestContents(Intent.CommitChestContents c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            ChestRef existing = state.chests().get(f.idx(), c.name());
            if (existing == null) {
                return;
            }
            state = state.withChests(state.chests().set(f.idx(),
                    new ChestRef(existing.name(), c.blobRef(), existing.createdAt())));
            effects.add(new Effect.ChestContentsChanged(seq, origin, c.faction(), c.name(), c.blobRef()));
        }

        // ── flags / prefs / session ─────────────────────────────────────────────────────

        void setFactionFlag(Intent.SetFactionFlag c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            if (c.flag() < 0 || c.flag() >= Faction.FLAG_COUNT) {
                reject(ReasonCode.FLAG_INVALID);
                return;
            }
            if (!c.byAdmin() && !state.config().flagDefaults().editableOf(c.flag())) {
                reject(ReasonCode.FLAG_NOT_EDITABLE);
                return;
            }
            long bits = Faction.withFlag(f.flagBits(), c.flag(), c.value());
            replaceFaction(FactionEdit.withFlagBits(f, bits));
            effects.add(new Effect.FlagChanged(seq, origin, c.faction(), c.flag(), c.value()));
        }

        void setNotifyPref(Intent.SetNotifyPref c) {
            int ord = memberOrd(c.player());
            if (ord < 0 || !state.ledger().has(ord)) {
                reject(ReasonCode.PLAYER_NOT_FOUND);
                return;
            }
            if (!PrefRules.isValidPrefBit(c.prefBit())) {
                reject(ReasonCode.FLAG_INVALID);
                return;
            }
            int bits = PlayerLedger.withPref(state.ledger().prefsBits(ord), c.prefBit(), c.on());
            state = state.withLedger(state.ledger().withPrefsBits(ord, bits));
            effects.add(new Effect.PrefChanged(seq, origin, c.player(), c.prefBit(), c.on()));
        }

        void setLocale(Intent.SetLocale c) {
            int ord = ensureMember(c.player(), "");
            int locale = PrefRules.clampLocale(c.localeIdx());
            state = state.withLedger(state.ledger().withLocaleIdx(ord, locale));
            effects.add(new Effect.LocaleChanged(seq, origin, c.player(), locale));
        }

        void setAutoTerritoryMode(Intent.SetAutoTerritoryMode c) {
            int ord = ensureMember(c.player(), "");
            int mode = PrefRules.clampAutoMode(c.mode());
            int bits = PlayerLedger.withAutoMode(state.ledger().prefsBits(ord), mode);
            state = state.withLedger(state.ledger().withPrefsBits(ord, bits));
            effects.add(new Effect.AutoModeChanged(seq, origin, c.player(), mode));
        }

        void setTerritoryTitles(Intent.SetTerritoryTitles c) {
            int ord = ensureMember(c.player(), "");
            int bits = PlayerLedger.withPref(state.ledger().prefsBits(ord),
                    PlayerLedger.PREF_TERRITORY_TITLES, c.on());
            state = state.withLedger(state.ledger().withPrefsBits(ord, bits));
            effects.add(new Effect.PrefChanged(seq, origin, c.player(),
                    PlayerLedger.PREF_TERRITORY_TITLES, c.on()));
        }

        void setFly(Intent.SetFly c) {
            int ord = ensureMember(c.player(), "");
            int bits = PlayerLedger.withPref(state.ledger().prefsBits(ord), PlayerLedger.PREF_FLY, c.on());
            state = state.withLedger(state.ledger().withPrefsBits(ord, bits));
            effects.add(new Effect.FlyChanged(seq, origin, c.player(), c.on()));
        }

        void setOverriding(Intent.SetOverriding c) {
            int ord = ensureMember(c.player(), "");
            int bits = PlayerLedger.withPref(state.ledger().prefsBits(ord),
                    PlayerLedger.PREF_OVERRIDING, c.on());
            state = state.withLedger(state.ledger().withPrefsBits(ord, bits));
            effects.add(new Effect.OverrideChanged(seq, origin, c.player(), c.on()));
        }

        void setShield(Intent.SetShield c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            if (!state.config().land().warShieldEnabled()) {
                reject(ReasonCode.SHIELD_FEATURE_DISABLED);
                return;
            }
            if (c.startHour() < 0 || c.startHour() > 23) {
                reject(ReasonCode.SHIELD_INVALID_HOUR);
                return;
            }
            int maxDur = state.config().land().warShieldMaxDurationHours();
            if (c.durationHours() < 1 || c.durationHours() > maxDur) {
                reject(ReasonCode.SHIELD_INVALID_DURATION);
                return;
            }
            replaceFaction(FactionEdit.withShield(f, c.startHour(), c.durationHours()));
            effects.add(new Effect.ShieldChanged(seq, origin, c.faction(), c.startHour(),
                    c.durationHours()));
        }

        void clearShield(Intent.ClearShield c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            replaceFaction(FactionEdit.withShield(f, Faction.NO_SHIELD, 0));
            effects.add(new Effect.ShieldChanged(seq, origin, c.faction(), Faction.NO_SHIELD, 0));
        }

        void playerConnected(Intent.PlayerConnected c) {
            int ord = ensureMember(c.player(), c.name());
            if (c.name() != null && !c.name().isEmpty()) {
                state = state.withLedger(state.ledger().withNameLast(ord, c.name()));
            }
            // Offline→online transition is an epoch boundary: settle the offline accrual first.
            settleMember(ord, false);
            state = state.withOnline(state.online().with(ord));
            state = state.withLedger(state.ledger().withLastActivity(ord, epochMillis));
            effects.add(new Effect.SessionStarted(seq, origin, c.player(), epochMillis));
        }

        void playerDisconnected(Intent.PlayerDisconnected c) {
            int ord = memberOrd(c.player());
            if (ord < 0 || !state.ledger().has(ord)) {
                return;
            }
            // Online→offline transition: settle the online accrual before leaving the set.
            settleMember(ord, true);
            state = state.withOnline(state.online().without(ord));
            state = state.withLedger(state.ledger().withLastActivity(ord, epochMillis));
            effects.add(new Effect.SessionEnded(seq, origin, c.player(), epochMillis));
        }

        void ackInbox(Intent.AckInbox c) {
            state = state.withInbox(state.inbox().removeIds(c.entryIds()));
            effects.add(new Effect.InboxDelivered(seq, origin, c.player(), c.entryIds()));
        }

        // ── system ─────────────────────────────────────────────────────────────────────

        void swapConfig(Intent.SwapConfig c) {
            state = state.withConfig(c.config());
            effects.add(new Effect.ConfigSwapped(seq, origin, "reload"));
            continuation(new Intent.RetagPage(0));
        }

        void retagPage(int cursor) {
            FactionArena arena = state.factions();
            int hw = arena.highWater();
            int processed = 0;
            int ord = cursor;
            for (; ord < hw && processed < PAGE_SIZE; ord++) {
                Faction f = arena.at(ord);
                if (f == null || !f.isNormal()) {
                    continue;
                }
                processed++;
                String tag = f.name();
                if (!tag.equals(f.tagLegacy()) || !tag.equals(f.tagMini())) {
                    Faction nf = FactionEdit.withName(f, f.name(), f.nameFolded(), tag, tag);
                    state = state.withFactions(state.factions().replace(ord, nf));
                    arena = state.factions();
                }
            }
            if (ord < hw) {
                continuation(new Intent.RetagPage(ord));
            }
        }

        void seedPredefined(Intent.SeedPredefined c) {
            // Predefined seeding is folded into CreateFaction upstream (:core); no kernel state
            // change here beyond acknowledging the intent.
        }

        void importBaseline(Intent.ImportBaseline c) {
            // Boot migration is performed by the :core baseline loader before the writer starts;
            // in the kernel this is a no-op marker.
        }

        // ── disband (paged AM-5) ──────────────────────────────────────────────────────────

        void disbandFaction(Intent.DisbandFaction c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                reject(ReasonCode.FACTION_NOT_FOUND);
                return;
            }
            continuation(new Intent.DisbandPage(c.faction(), 0, 0, c.byAdmin(), c.actor()));
        }

        void disbandPage(Intent.DisbandPage c) {
            Faction f = resolve(c.faction());
            if (f == null) {
                return; // already gone
            }
            int ord = f.idx();
            if (c.phase() == 0) {
                int removed = removeUpToPageClaims(f.idx(), FactionHandle.WILDERNESS);
                Faction after = resolve(c.faction());
                if (after != null && after.landCount() > 0) {
                    continuation(new Intent.DisbandPage(c.faction(), 0, 0, c.byAdmin(), c.actor()));
                } else {
                    continuation(new Intent.DisbandPage(c.faction(), 1, 0, c.byAdmin(), c.actor()));
                }
                if (removed == 0) {
                    // nothing to do this page; fall straight through handled above
                }
            } else if (c.phase() == 1) {
                int moved = clearMembersPage(ord);
                if (moved == PAGE_SIZE && FactionAggregates.memberCount(state, c.faction()) > 0) {
                    continuation(new Intent.DisbandPage(c.faction(), 1, 0, c.byAdmin(), c.actor()));
                } else {
                    continuation(new Intent.DisbandPage(c.faction(), 2, 0, c.byAdmin(), c.actor()));
                }
            } else {
                // Final page: scrub inbound references, release name, free ordinal (generation bump LAST).
                state = state.withFactions(DisbandRules.scrubRelations(state.factions(), ord));
                state = state.withInvites(state.invites().removeIf(in -> in.factionOrdinal() == ord));
                state = state.withMergeRequests(state.mergeRequests().removeInvolving(ord));
                state = state.withWarps(state.warps().removeFaction(ord));
                state = state.withChests(state.chests().removeFaction(ord));
                refundFactionEscrows(ord);
                state = state.withFactionNames(state.factionNames().without(f.nameFolded()));
                state = state.withFactions(state.factions().freed(ord));
                effects.add(new Effect.FactionDisbanded(seq, origin, c.faction(), f.name()));
            }
        }

        /** Removes up to a page of {@code factionOrd}'s claims (via the atlas scan); returns count. */
        private int removeUpToPageClaims(int factionOrd, int unusedNewOwner) {
            List<long[]> batch = collectFactionClaims(factionOrd, PAGE_SIZE);
            for (long[] wk : batch) {
                int world = (int) wk[0];
                long key = wk[1];
                state = state.withClaims(state.claims().withoutClaim(world, key));
                Faction f = state.factions().at(factionOrd);
                if (f != null) {
                    state = state.withFactions(state.factions().replace(factionOrd,
                            FactionEdit.withLand(f, f.landCount() - 1, f.claims().remove(world, key))));
                }
                effects.add(new Effect.ClaimRemoved(seq, origin, world, key,
                        state.factions().handleOf(factionOrd)));
            }
            return batch.size();
        }

        /** Reassigns up to a page of {@code factionOrd}'s claims to {@code newOwnerHandle}; returns count. */
        private int reassignUpToPageClaims(int factionOrd, int newOwnerHandle, int newOwnerOrd) {
            List<long[]> batch = collectFactionClaims(factionOrd, PAGE_SIZE);
            for (long[] wk : batch) {
                int world = (int) wk[0];
                long key = wk[1];
                state = state.withClaims(state.claims().withClaim(world, key, newOwnerHandle));
                Faction src = state.factions().at(factionOrd);
                if (src != null) {
                    state = state.withFactions(state.factions().replace(factionOrd,
                            FactionEdit.withLand(src, src.landCount() - 1, src.claims().remove(world, key))));
                }
                Faction dst = state.factions().at(newOwnerOrd);
                if (dst != null) {
                    state = state.withFactions(state.factions().replace(newOwnerOrd,
                            FactionEdit.withLand(dst, dst.landCount() + 1, dst.claims().add(world, key))));
                }
                effects.add(new Effect.ClaimSet(seq, origin, world, key, newOwnerHandle,
                        state.factions().handleOf(factionOrd)));
            }
            return batch.size();
        }

        private List<long[]> collectFactionClaims(int factionOrd, int limit) {
            ArrayList<long[]> out = new ArrayList<>();
            int[] count = {0};
            state.claims().forEachClaim((worldIdx, chunkKey, ownerHandle) -> {
                if (count[0] < limit && FactionHandle.ordinal(ownerHandle) == factionOrd) {
                    out.add(new long[] {worldIdx, chunkKey});
                    count[0]++;
                }
            });
            return out;
        }

        /** Clears the faction of up to a page of members; returns how many were cleared. */
        private int clearMembersPage(int factionOrd) {
            PlayerLedger l = state.ledger();
            int hw = l.highWater();
            int cleared = 0;
            for (int i = 0; i < hw && cleared < PAGE_SIZE; i++) {
                if (l.has(i) && FactionHandle.ordinal(l.factionHandle(i)) == factionOrd) {
                    UUID uuid = l.uuid(i);
                    l = l.withFactionHandle(i, NO_HANDLE).withRankIdx(i, 0);
                    effects.add(new Effect.MemberLeft(seq, origin,
                            state.factions().handleOf(factionOrd), uuid, false));
                    cleared++;
                }
            }
            state = state.withLedger(l);
            return cleared;
        }

        private int migrateMembersPage(int fromOrd, int toHandle, int toDefaultRankIdx) {
            PlayerLedger l = state.ledger();
            int hw = l.highWater();
            int moved = 0;
            for (int i = 0; i < hw && moved < PAGE_SIZE; i++) {
                if (l.has(i) && FactionHandle.ordinal(l.factionHandle(i)) == fromOrd) {
                    UUID uuid = l.uuid(i);
                    l = l.withFactionHandle(i, toHandle).withRankIdx(i, toDefaultRankIdx)
                            .withJoinedAt(i, epochMillis);
                    effects.add(new Effect.MemberJoined(seq, origin, toHandle, uuid));
                    moved++;
                }
            }
            state = state.withLedger(l);
            return moved;
        }

        private void refundFactionEscrows(int factionOrd) {
            EscrowTable escrows = state.escrows();
            ArrayList<EscrowTable.Escrow> toRefund = new ArrayList<>();
            escrows.forEach(e -> {
                if (e.factionOrdinal() == factionOrd) {
                    toRefund.add(e);
                }
            });
            for (EscrowTable.Escrow e : toRefund) {
                state = state.withEscrows(state.escrows().settle(e.id()));
                effects.add(new Effect.EscrowRefund(seq, origin, e.id(), e.player(), e.amount()));
            }
        }

        // ── unclaim-all (paged AM-5) ────────────────────────────────────────────────────

        void unclaimAllPage(int factionHandle, UUID actor) {
            Faction f = resolve(factionHandle);
            if (f == null) {
                return;
            }
            removeUpToPageClaims(f.idx(), FactionHandle.WILDERNESS);
            Faction after = resolve(factionHandle);
            if (after != null && after.landCount() > 0) {
                continuation(new Intent.UnclaimAllPage(factionHandle, 0, actor));
            }
        }

        // ── merge (paged AM-5) ────────────────────────────────────────────────────────

        void sendMergeRequest(Intent.SendMergeRequest c) {
            ReasonCode err = MergeRules.validateSend(state, c.sender(), c.target());
            if (err != null) {
                reject(err);
                return;
            }
            Faction sender = resolve(c.sender());
            Faction target = resolve(c.target());
            state = state.withMergeRequests(state.mergeRequests().add(
                    new MergeTable.MergeRequest(seq, sender.idx(), target.idx(), c.actor(), epochMillis)));
            effects.add(new Effect.MergeRequested(seq, origin, c.sender(), c.target()));
            audit(c.sender(), c.actor(), FactionAuditAction.MERGE_REQUEST, target.name());
        }

        void acceptMergeRequest(Intent.AcceptMergeRequest c) {
            ReasonCode err = MergeRules.validateAccept(state, c.sender(), c.target());
            if (err != null) {
                reject(err);
                return;
            }
            continuation(new Intent.MergePage(c.sender(), c.target(), 0, 0, c.actor()));
        }

        void mergePage(Intent.MergePage c) {
            Faction sender = resolve(c.sender());
            Faction target = resolve(c.target());
            if (sender == null || target == null) {
                return; // one side vanished; abort quietly
            }
            int senderOrd = sender.idx();
            int targetOrd = target.idx();
            if (c.phase() == 0) {
                int moved = reassignUpToPageClaims(senderOrd, c.target(), targetOrd);
                Faction after = resolve(c.sender());
                if (after != null && after.landCount() > 0) {
                    continuation(new Intent.MergePage(c.sender(), c.target(), 0, 0, c.actor()));
                } else {
                    // reassign warps in one shot (low volume), then members
                    reassignWarps(senderOrd, targetOrd);
                    continuation(new Intent.MergePage(c.sender(), c.target(), 1, 0, c.actor()));
                }
                if (moved < 0) {
                    return;
                }
            } else if (c.phase() == 1) {
                int defaultRankIdx = Math.max(0, RoleRules.defaultRankIndex(target.ranks()));
                int moved = migrateMembersPage(senderOrd, c.target(), defaultRankIdx);
                if (moved == PAGE_SIZE && FactionAggregates.memberCount(state, c.sender()) > 0) {
                    continuation(new Intent.MergePage(c.sender(), c.target(), 1, 0, c.actor()));
                } else {
                    continuation(new Intent.MergePage(c.sender(), c.target(), 2, 0, c.actor()));
                }
            } else {
                // Final page: move bank, scrub sender, free it, emit MergeCompleted.
                double bankMoved = MoneyMath.round2(sender.bank());
                Faction tgt = resolve(c.target());
                if (tgt != null && bankMoved != 0.0) {
                    replaceFaction(FactionEdit.withBank(tgt, MoneyMath.round2(tgt.bank() + bankMoved)));
                }
                state = state.withFactions(DisbandRules.scrubRelations(state.factions(), senderOrd));
                state = state.withInvites(
                        state.invites().removeIf(in -> in.factionOrdinal() == senderOrd));
                state = state.withMergeRequests(state.mergeRequests().removeInvolving(senderOrd));
                state = state.withChests(state.chests().removeFaction(senderOrd));
                refundFactionEscrows(senderOrd);
                state = state.withFactionNames(state.factionNames().without(sender.nameFolded()));
                state = state.withFactions(state.factions().freed(senderOrd));
                effects.add(new Effect.MergeCompleted(seq, origin, c.sender(), c.target(), 0, 0,
                        bankMoved));
                audit(c.target(), c.actor(), FactionAuditAction.MERGE_ACCEPT, sender.name());
            }
        }

        private void reassignWarps(int fromOrd, int toOrd) {
            Warp[] warps = state.warps().forFaction(fromOrd);
            for (Warp w : warps) {
                state = state.withWarps(state.warps().set(toOrd, w));
            }
            state = state.withWarps(state.warps().removeFaction(fromOrd));
        }

        // ── small formatting helpers (locale-independent) ────────────────────────────────

        private static String fmt1(double v) {
            return String.format(java.util.Locale.ROOT, "%.1f", v);
        }

        private static String fmt2(double v) {
            return String.format(java.util.Locale.ROOT, "%.2f", v);
        }

        private static Rank[] append(Rank[] ranks, Rank r) {
            Rank[] out = new Rank[ranks.length + 1];
            System.arraycopy(ranks, 0, out, 0, ranks.length);
            out[ranks.length] = r;
            return out;
        }

        private static Rank[] removeAt(Rank[] ranks, int idx) {
            Rank[] out = new Rank[ranks.length - 1];
            System.arraycopy(ranks, 0, out, 0, idx);
            System.arraycopy(ranks, idx + 1, out, idx, ranks.length - idx - 1);
            return out;
        }
    }
}
