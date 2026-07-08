package dev.fablemc.factions.kernel.reduce;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import dev.fablemc.factions.kernel.vocab.FactionAuditAction;
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
import dev.fablemc.factions.kernel.vocab.Relation;
import dev.fablemc.factions.kernel.vocab.PowerSource;
import dev.fablemc.factions.kernel.vocab.PagePhase;
import dev.fablemc.factions.kernel.vocab.NotifyPredicate;
import dev.fablemc.factions.kernel.vocab.InviteRemovalReason;
import dev.fablemc.factions.kernel.vocab.EscrowOutcome;
import dev.fablemc.factions.kernel.vocab.EscrowKind;
import dev.fablemc.factions.kernel.vocab.BroadcastScope;
import dev.fablemc.factions.kernel.vocab.BankTxType;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.kernel.intent.SessionIntent;
import dev.fablemc.factions.kernel.intent.RoleIntent;
import dev.fablemc.factions.kernel.intent.RelationIntent;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.intent.PowerIntent;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.kernel.intent.ClaimIntent;
import dev.fablemc.factions.kernel.intent.ChestIntent;
import dev.fablemc.factions.kernel.effect.TravelEffect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.effect.SessionEffect;
import dev.fablemc.factions.kernel.effect.RoleEffect;
import dev.fablemc.factions.kernel.effect.RelationEffect;
import dev.fablemc.factions.kernel.effect.PrefEffect;
import dev.fablemc.factions.kernel.effect.PowerEffect;
import dev.fablemc.factions.kernel.effect.MembershipEffect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.effect.ExternalEffect;
import dev.fablemc.factions.kernel.effect.EconomyEffect;
import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.ChestEffect;
import dev.fablemc.factions.kernel.effect.AuditEffect;

/**
 * The reducer's confined working context plus the effect-buffer, envelope-rng, and common-lookup
 * helpers shared by every per-domain reducer (W25-REORG P2a). One instance exists per
 * {@link Reducer#apply} call and is never published while mutable.
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only. <b>Mutability:</b> confined,
 * single-threaded mutation of {@link #state} and {@link #effects} during one {@code apply}
 * call; nondeterminism comes only from the envelope ({@code epochMillis}, {@code tick},
 * {@code rngSeed}). New UUIDs are drawn from a per-envelope {@link SplittableRandom} seeded by
 * {@code rngSeed} — never {@code UUID.randomUUID} — which makes replay byte-identical.
 */
final class ReduceSupport {

    static final int NO_HANDLE = FactionHandle.WILDERNESS;
    static final String[] NO_ARGS = new String[0];

    KernelState state;
    final long seq;
    final long epochMillis;
    final int tick;
    final Origin origin;
    final List<Effect> effects = new ArrayList<>(8);
    private SplittableRandom rng;
    private final long rngSeed;

    ReduceSupport(KernelState state, IntentEnvelope env) {
        this.state = state;
        this.seq = env.seq();
        this.epochMillis = env.epochMillis();
        this.tick = env.tick();
        this.origin = env.origin();
        this.rngSeed = env.rngSeed();
    }

    UUID newUuid() {
        if (rng == null) {
            rng = new SplittableRandom(rngSeed);
        }
        return new UUID(rng.nextLong(), rng.nextLong());
    }

    void reject(ReasonCode reason) {
        effects.add(new FeedbackEffect.Rejected(seq, origin, reason, NO_ARGS));
    }

    void reject(ReasonCode reason, String... args) {
        effects.add(new FeedbackEffect.Rejected(seq, origin, reason, args));
    }

    void audit(int factionHandle, UUID actor, FactionAuditAction action, String detail) {
        effects.add(new AuditEffect.AuditRecorded(seq, origin, factionHandle, actor, action, detail));
    }

    void notify(UUID target, String key, String... args) {
        effects.add(new FeedbackEffect.Notify(seq, origin, target, MessageKey.of(key), args));
    }

    void notifyFaction(int factionHandle, String key, String... args) {
        effects.add(new FeedbackEffect.NotifyFaction(seq, origin, factionHandle, NotifyPredicate.MEMBERS_ALL,
                MessageKey.of(key), args));
    }

    void continuation(Intent next) {
        effects.add(new SystemEffect.ContinuationRequested(seq, origin, next));
    }

    PowerConfig power() {
        return state.config().power();
    }

    BakedTables baked() {
        return state.config().baked();
    }

    Faction resolve(int handle) {
        return state.factions().resolve(handle);
    }

    int memberOrd(UUID player) {
        return state.members().get(player);
    }

    /** Faction handle of a member ordinal, or WILDERNESS. */
    int memberFactionHandle(int ord) {
        return ord < 0 ? NO_HANDLE : state.ledger().factionHandle(ord);
    }

    /** Ensures a ledger row + directory mapping for {@code player}; returns the ordinal. */
    int ensureMember(UUID player, String name) {
        int ord = memberOrd(player);
        if (ord >= 0 && state.ledger().has(ord)) {
            return ord;
        }
        ord = state.ledger().nextOrdinal();
        state = state.withLedger(state.ledger().withNewMember(ord, player, name == null ? "" : name));
        state = state.withMembers(state.members().withMapping(player, ord));
        return ord;
    }

    void replaceFaction(Faction f) {
        state = state.withFactions(state.factions().replace(f.idx(), f));
    }

    Rank rankOf(Faction f, int memberOrdinal) {
        int idx = state.ledger().rankIdx(memberOrdinal);
        Rank[] ranks = f.ranks();
        return (idx >= 0 && idx < ranks.length) ? ranks[idx] : null;
    }

    String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    void settleMember(int ord, boolean online) {
        PowerConfig pc = power();
        double settled = PowerMath.settle(pc, online, state.ledger().powerBase(ord),
                state.ledger().powerAsOfTick(ord), state.ledger().powerFrozen(ord), tick);
        state = state.withLedger(state.ledger().withPower(ord, settled, tick));
    }

    List<long[]> collectFactionClaims(int factionOrd, int limit) {
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

    /** Removes up to a page of {@code factionOrd}'s claims (via the atlas scan); returns count. */
    int removeUpToPageClaims(int factionOrd, int unusedNewOwner) {
        List<long[]> batch = collectFactionClaims(factionOrd, Reducer.PAGE_SIZE);
        for (long[] wk : batch) {
            int world = (int) wk[0];
            long key = wk[1];
            state = state.withClaims(state.claims().withoutClaim(world, key));
            Faction f = state.factions().at(factionOrd);
            if (f != null) {
                state = state.withFactions(state.factions().replace(factionOrd,
                        FactionEdit.withLand(f, f.landCount() - 1, f.claims().remove(world, key))));
            }
            effects.add(new ClaimEffect.ClaimRemoved(seq, origin, world, key,
                    state.factions().handleOf(factionOrd)));
        }
        return batch.size();
    }

    /** Reassigns up to a page of {@code factionOrd}'s claims to {@code newOwnerHandle}; returns count. */
    int reassignUpToPageClaims(int factionOrd, int newOwnerHandle, int newOwnerOrd) {
        List<long[]> batch = collectFactionClaims(factionOrd, Reducer.PAGE_SIZE);
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
            effects.add(new ClaimEffect.ClaimSet(seq, origin, world, key, newOwnerHandle,
                    state.factions().handleOf(factionOrd)));
        }
        return batch.size();
    }

    /** Clears the faction of up to a page of members; returns how many were cleared. */
    int clearMembersPage(int factionOrd) {
        PlayerLedger l = state.ledger();
        int hw = l.highWater();
        int cleared = 0;
        for (int i = 0; i < hw && cleared < Reducer.PAGE_SIZE; i++) {
            if (l.has(i) && FactionHandle.ordinal(l.factionHandle(i)) == factionOrd) {
                UUID uuid = l.uuid(i);
                l = l.withFactionHandle(i, NO_HANDLE).withRankIdx(i, 0);
                effects.add(new MembershipEffect.MemberLeft(seq, origin,
                        state.factions().handleOf(factionOrd), uuid, false));
                cleared++;
            }
        }
        state = state.withLedger(l);
        return cleared;
    }

    int migrateMembersPage(int fromOrd, int toHandle, int toDefaultRankIdx) {
        PlayerLedger l = state.ledger();
        int hw = l.highWater();
        int moved = 0;
        for (int i = 0; i < hw && moved < Reducer.PAGE_SIZE; i++) {
            if (l.has(i) && FactionHandle.ordinal(l.factionHandle(i)) == fromOrd) {
                UUID uuid = l.uuid(i);
                l = l.withFactionHandle(i, toHandle).withRankIdx(i, toDefaultRankIdx)
                        .withJoinedAt(i, epochMillis);
                effects.add(new MembershipEffect.MemberJoined(seq, origin, toHandle, uuid));
                moved++;
            }
        }
        state = state.withLedger(l);
        return moved;
    }

    void refundFactionEscrows(int factionOrd) {
        EscrowTable escrows = state.escrows();
        ArrayList<EscrowTable.Escrow> toRefund = new ArrayList<>();
        escrows.forEach(e -> {
            if (e.factionOrdinal() == factionOrd) {
                toRefund.add(e);
            }
        });
        for (EscrowTable.Escrow e : toRefund) {
            state = state.withEscrows(state.escrows().settle(e.id()));
            effects.add(new ExternalEffect.EscrowRefund(seq, origin, e.id(), e.player(), e.amount()));
        }
    }

    String fmt1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
