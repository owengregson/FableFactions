package dev.fablemc.factions.kernel.reduce;

import java.util.UUID;

import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.effect.RoleEffect;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.DisbandRules;
import dev.fablemc.factions.kernel.rules.FactionAggregates;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.rules.MergeRules;
import dev.fablemc.factions.kernel.rules.MoneyMath;
import dev.fablemc.factions.kernel.rules.NameRules;
import dev.fablemc.factions.kernel.rules.RoleRules;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.MergeTable;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.kernel.state.Warp;
import dev.fablemc.factions.kernel.vocab.FactionAuditAction;
import dev.fablemc.factions.kernel.vocab.PagePhase;

/**
 * Reduces the lifecycle intents: create / disband (paged) / rename / description / motd / ownership transfer / merge (paged).
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved the code; the P3 sweep standardized the
 * guard/emission shapes without behavior change).
 */
final class LifecycleReducer {

    private LifecycleReducer() {
    }

    static void reduce(ReduceSupport s, LifecycleIntent i) {
        if (i instanceof LifecycleIntent.CreateFaction x) {
            createFaction(s, x);
        } else if (i instanceof LifecycleIntent.DisbandFaction x) {
            disbandFaction(s, x);
        } else if (i instanceof LifecycleIntent.RenameFaction x) {
            renameFaction(s, x);
        } else if (i instanceof LifecycleIntent.SetDescription x) {
            setDescription(s, x);
        } else if (i instanceof LifecycleIntent.SetMotd x) {
            setMotd(s, x);
        } else if (i instanceof LifecycleIntent.TransferOwnership x) {
            transferOwnership(s, x);
        } else if (i instanceof LifecycleIntent.SendMergeRequest x) {
            sendMergeRequest(s, x);
        } else if (i instanceof LifecycleIntent.AcceptMergeRequest x) {
            acceptMergeRequest(s, x);
        } else if (i instanceof LifecycleIntent.DisbandPage x) {
            disbandPage(s, x);
        } else if (i instanceof LifecycleIntent.MergePage x) {
            mergePage(s, x);
        } else {
            throw new IllegalStateException("unhandled lifecycle intent: " + i.getClass().getName());
        }
    }

    static void createFaction(ReduceSupport s, LifecycleIntent.CreateFaction c) {
        if (s.rejectIf(NameRules.validate(c.name(), s.state.factionNames()))) {
            return;
        }
        int existingOrd = s.memberOrd(c.owner());
        if (existingOrd >= 0 && s.state.ledger().has(existingOrd)
                && s.memberFactionHandle(existingOrd) != ReduceSupport.NO_HANDLE) {
            s.reject(ReasonCode.ALREADY_IN_FACTION);
            return;
        }
        int ord = s.state.factions().nextFreeOrdinal();
        int handle = FactionHandle.handle(s.state.factions().generationAt(ord), ord);
        UUID id = s.newUuid();
        Rank[] ranks = defaultRanks(s);
        int ownerRankIdx = RoleRules.ownerRankIndex(ranks);
        String tag = c.name();
        Faction f = new Faction(ord, id, c.name(), NameIndex.fold(c.name()), c.owner(), "", "",
                s.epochMillis, 0.0, 0.0, 0L,
                RelationEdges.NO_ORDINALS, RelationEdges.NO_KINDS,
                RelationEdges.NO_ORDINALS, RelationEdges.NO_KINDS,
                null, Faction.NO_SHIELD, 0, false, ranks, 0, 0.0, (long) s.tick,
                FactionClaimList.empty(), tag, tag);
        s.state = s.state.withFactions(s.state.factions().withFaction(ord, f));
        s.state = s.state.withFactionNames(s.state.factionNames().with(NameIndex.fold(c.name()), ord));

        int ownerOrd = s.ensureMember(c.owner(), "");
        s.state = s.state.withLedger(s.state.ledger()
                .withFactionHandle(ownerOrd, handle)
                .withRankIdx(ownerOrd, ownerRankIdx)
                .withJoinedAt(ownerOrd, s.epochMillis));

        s.emit(new LifecycleEffect.FactionCreated(s.seq, s.origin, handle, id, c.name()));
    }

    static Rank[] defaultRanks(ReduceSupport s) {
        String op = s.state.config().role().defaultOwnerPrefix();
        String ofp = s.state.config().role().defaultOfficerPrefix();
        String mp = s.state.config().role().defaultMemberPrefix();
        return new Rank[] {
                new Rank(Rank.NAME_MEMBER, Rank.NAME_MEMBER, s.emptyToNull(mp), Rank.PRIORITY_MEMBER),
                new Rank(Rank.NAME_OFFICER, Rank.NAME_OFFICER, s.emptyToNull(ofp), Rank.PRIORITY_OFFICER),
                new Rank(Rank.NAME_OWNER, Rank.NAME_OWNER, s.emptyToNull(op), Rank.PRIORITY_OWNER)
        };
    }

    static void renameFaction(ReduceSupport s, LifecycleIntent.RenameFaction c) {
        Faction f = s.factionOrReject(c.faction());
        if (f == null) {
            return;
        }
        if (s.rejectIf(NameRules.validateFormat(c.newName()))) {
            return;
        }
        String newFold = NameIndex.fold(c.newName());
        if (!newFold.equals(f.nameFolded()) && s.state.factionNames().contains(newFold)) {
            s.reject(ReasonCode.NAME_TAKEN);
            return;
        }
        String oldName = f.name();
        String tag = c.newName();
        Faction nf = FactionEdit.withName(f, c.newName(), newFold, tag, tag);
        s.replaceFaction(nf);
        if (!newFold.equals(f.nameFolded())) {
            s.state = s.state.withFactionNames(
                    s.state.factionNames().without(f.nameFolded()).with(newFold, f.idx()));
        }
        s.emit(new LifecycleEffect.FactionRenamed(s.seq, s.origin, c.faction(), oldName, c.newName()));
    }

    static void setDescription(ReduceSupport s, LifecycleIntent.SetDescription c) {
        Faction f = s.factionOrReject(c.faction());
        if (f == null) {
            return;
        }
        String d = c.description() == null ? "" : c.description();
        if (d.length() > 250) {
            s.reject(ReasonCode.DESCRIPTION_TOO_LONG);
            return;
        }
        s.replaceFaction(FactionEdit.withDescription(f, d));
        s.emit(new LifecycleEffect.DescriptionChanged(s.seq, s.origin, c.faction(), d));
    }

    static void setMotd(ReduceSupport s, LifecycleIntent.SetMotd c) {
        Faction f = s.factionOrReject(c.faction());
        if (f == null) {
            return;
        }
        String m = c.motd() == null ? "" : c.motd();
        s.replaceFaction(FactionEdit.withMotd(f, m));
        s.emit(new LifecycleEffect.MotdChanged(s.seq, s.origin, c.faction(), m));
        s.audit(c.faction(), c.actor(), FactionAuditAction.MOTD_SET, m);
    }

    static void transferOwnership(ReduceSupport s, LifecycleIntent.TransferOwnership c) {
        Faction f = s.factionOrReject(c.faction());
        if (f == null) {
            return;
        }
        int newOwnerOrd = s.memberOrd(c.newOwner());
        if (newOwnerOrd < 0 || !s.state.ledger().has(newOwnerOrd)
                || FactionHandle.ordinal(s.memberFactionHandle(newOwnerOrd)) != f.idx()) {
            s.reject(ReasonCode.NOT_MEMBER);
            return;
        }
        Rank[] ranks = f.ranks();
        int ownerRankIdx = RoleRules.ownerRankIndex(ranks);
        int officerRankIdx = RoleRules.officerRankIndex(ranks);
        if (ownerRankIdx < 0 || officerRankIdx < 0) {
            s.reject(ReasonCode.ROLE_FAILED);
            return;
        }
        UUID oldOwner = f.ownerId();
        int oldOwnerOrd = oldOwner == null ? -1 : s.memberOrd(oldOwner);
        if (oldOwnerOrd >= 0 && s.state.ledger().has(oldOwnerOrd)) {
            s.state = s.state.withLedger(s.state.ledger().withRankIdx(oldOwnerOrd, officerRankIdx));
            s.emit(new RoleEffect.RankChanged(s.seq, s.origin, c.faction(), oldOwner, officerRankIdx));
        }
        s.state = s.state.withLedger(s.state.ledger().withRankIdx(newOwnerOrd, ownerRankIdx));
        s.replaceFaction(FactionEdit.withOwner(f, c.newOwner()));
        s.emit(new RoleEffect.RankChanged(s.seq, s.origin, c.faction(), c.newOwner(), ownerRankIdx));
        s.emit(new LifecycleEffect.OwnershipTransferred(s.seq, s.origin, c.faction(), oldOwner, c.newOwner()));
    }

    static void sendMergeRequest(ReduceSupport s, LifecycleIntent.SendMergeRequest c) {
        if (s.rejectIf(MergeRules.validateSend(s.state, c.sender(), c.target()))) {
            return;
        }
        Faction sender = s.resolve(c.sender());
        Faction target = s.resolve(c.target());
        s.state = s.state.withMergeRequests(s.state.mergeRequests().add(
                new MergeTable.MergeRequest(s.seq, sender.idx(), target.idx(), c.actor(), s.epochMillis)));
        s.emit(new LifecycleEffect.MergeRequested(s.seq, s.origin, c.sender(), c.target()));
        s.audit(c.sender(), c.actor(), FactionAuditAction.MERGE_REQUEST, target.name());
    }

    static void acceptMergeRequest(ReduceSupport s, LifecycleIntent.AcceptMergeRequest c) {
        if (s.rejectIf(MergeRules.validateAccept(s.state, c.sender(), c.target()))) {
            return;
        }
        s.continuation(new LifecycleIntent.MergePage(c.sender(), c.target(), PagePhase.CLAIMS, 0, c.actor()));
    }

    static void mergePage(ReduceSupport s, LifecycleIntent.MergePage c) {
        Faction sender = s.resolve(c.sender());
        Faction target = s.resolve(c.target());
        if (sender == null || target == null) {
            return; // one side vanished; abort quietly
        }
        int senderOrd = sender.idx();
        int targetOrd = target.idx();
        switch (c.phase()) {
            case CLAIMS -> {
                s.reassignUpToPageClaims(senderOrd, c.target(), targetOrd);
                Faction after = s.resolve(c.sender());
                if (after != null && after.landCount() > 0) {
                    s.continuation(new LifecycleIntent.MergePage(c.sender(), c.target(), PagePhase.CLAIMS, 0, c.actor()));
                } else {
                    // reassign warps in one shot (low volume), then members
                    reassignWarps(s, senderOrd, targetOrd);
                    s.continuation(new LifecycleIntent.MergePage(c.sender(), c.target(), PagePhase.MEMBERS, 0, c.actor()));
                }
            }
            case MEMBERS -> {
                int defaultRankIdx = Math.max(0, RoleRules.defaultRankIndex(target.ranks()));
                int moved = s.migrateMembersPage(senderOrd, c.target(), defaultRankIdx);
                if (moved == Reducer.PAGE_SIZE && FactionAggregates.memberCount(s.state, c.sender()) > 0) {
                    s.continuation(new LifecycleIntent.MergePage(c.sender(), c.target(), PagePhase.MEMBERS, 0, c.actor()));
                } else {
                    s.continuation(new LifecycleIntent.MergePage(c.sender(), c.target(), PagePhase.FINAL, 0, c.actor()));
                }
            }
            case FINAL -> {
                // Final page: move bank, scrub sender, free it, emit MergeCompleted.
                double bankMoved = MoneyMath.round2(sender.bank());
                Faction tgt = s.resolve(c.target());
                if (tgt != null && bankMoved != 0.0) {
                    s.replaceFaction(FactionEdit.withBank(tgt, MoneyMath.round2(tgt.bank() + bankMoved)));
                }
                s.state = s.state.withFactions(DisbandRules.scrubRelations(s.state.factions(), senderOrd));
                s.state = s.state.withInvites(
                        s.state.invites().removeIf(in -> in.factionOrdinal() == senderOrd));
                s.state = s.state.withMergeRequests(s.state.mergeRequests().removeInvolving(senderOrd));
                s.state = s.state.withChests(s.state.chests().removeFaction(senderOrd));
                s.refundFactionEscrows(senderOrd);
                s.state = s.state.withFactionNames(s.state.factionNames().without(sender.nameFolded()));
                s.state = s.state.withFactions(s.state.factions().freed(senderOrd));
                s.emit(new LifecycleEffect.MergeCompleted(s.seq, s.origin, c.sender(), c.target(), 0, 0,
                        bankMoved));
                s.audit(c.target(), c.actor(), FactionAuditAction.MERGE_ACCEPT, sender.name());
            }
        }
    }

    static void reassignWarps(ReduceSupport s, int fromOrd, int toOrd) {
        Warp[] warps = s.state.warps().forFaction(fromOrd);
        for (Warp w : warps) {
            s.state = s.state.withWarps(s.state.warps().set(toOrd, w));
        }
        s.state = s.state.withWarps(s.state.warps().removeFaction(fromOrd));
    }

    static void disbandFaction(ReduceSupport s, LifecycleIntent.DisbandFaction c) {
        Faction f = s.factionOrReject(c.faction());
        if (f == null) {
            return;
        }
        s.continuation(new LifecycleIntent.DisbandPage(c.faction(), PagePhase.CLAIMS, 0, c.byAdmin(), c.actor()));
    }

    static void disbandPage(ReduceSupport s, LifecycleIntent.DisbandPage c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            return; // already gone
        }
        int ord = f.idx();
        switch (c.phase()) {
            case CLAIMS -> {
                s.removeUpToPageClaims(f.idx());
                Faction after = s.resolve(c.faction());
                if (after != null && after.landCount() > 0) {
                    s.continuation(new LifecycleIntent.DisbandPage(c.faction(), PagePhase.CLAIMS, 0, c.byAdmin(), c.actor()));
                } else {
                    s.continuation(new LifecycleIntent.DisbandPage(c.faction(), PagePhase.MEMBERS, 0, c.byAdmin(), c.actor()));
                }
            }
            case MEMBERS -> {
                int moved = s.clearMembersPage(ord);
                if (moved == Reducer.PAGE_SIZE && FactionAggregates.memberCount(s.state, c.faction()) > 0) {
                    s.continuation(new LifecycleIntent.DisbandPage(c.faction(), PagePhase.MEMBERS, 0, c.byAdmin(), c.actor()));
                } else {
                    s.continuation(new LifecycleIntent.DisbandPage(c.faction(), PagePhase.FINAL, 0, c.byAdmin(), c.actor()));
                }
            }
            case FINAL -> {
                // Final page: scrub inbound references, release name, free ordinal (generation bump LAST).
                s.state = s.state.withFactions(DisbandRules.scrubRelations(s.state.factions(), ord));
                s.state = s.state.withInvites(s.state.invites().removeIf(in -> in.factionOrdinal() == ord));
                s.state = s.state.withMergeRequests(s.state.mergeRequests().removeInvolving(ord));
                s.state = s.state.withWarps(s.state.warps().removeFaction(ord));
                s.state = s.state.withChests(s.state.chests().removeFaction(ord));
                s.refundFactionEscrows(ord);
                s.state = s.state.withFactionNames(s.state.factionNames().without(f.nameFolded()));
                s.state = s.state.withFactions(s.state.factions().freed(ord));
                s.emit(new LifecycleEffect.FactionDisbanded(s.seq, s.origin, c.faction(), f.name()));
            }
        }
    }
}
