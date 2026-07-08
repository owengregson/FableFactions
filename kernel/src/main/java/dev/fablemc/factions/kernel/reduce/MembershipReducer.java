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
 * Membership and invite intents: join / leave / kick / invite send-revoke-decline.
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved this code unchanged).
 */
final class MembershipReducer {

    private MembershipReducer() {
    }

    static void reduce(ReduceSupport s, MembershipIntent i) {
        if (i instanceof MembershipIntent.JoinFaction x) {
            joinFaction(s, x);
        } else if (i instanceof MembershipIntent.LeaveFaction x) {
            leaveFaction(s, x);
        } else if (i instanceof MembershipIntent.KickMember x) {
            kickMember(s, x);
        } else if (i instanceof MembershipIntent.SendInvite x) {
            sendInvite(s, x);
        } else if (i instanceof MembershipIntent.RevokeInvite x) {
            revokeInvite(s, x);
        } else if (i instanceof MembershipIntent.DeclineInvite x) {
            declineInvite(s, x);
        } else if (i instanceof MembershipIntent.DeclineAllInvites x) {
            declineAllInvites(s, x);
        } else {
            throw new IllegalStateException("unhandled membership intent: " + i.getClass().getName());
        }
    }
    static void joinFaction(ReduceSupport s, MembershipIntent.JoinFaction c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        int ord = s.memberOrd(c.player());
        if (ord >= 0 && s.state.ledger().has(ord) && s.memberFactionHandle(ord) != ReduceSupport.NO_HANDLE) {
            s.reject(ReasonCode.ALREADY_IN_FACTION);
            return;
        }
        InviteTable.Invite invite = null;
        if (c.viaInviteId() == MembershipIntent.OPEN_JOIN) {
            if (!f.flag(Faction.FLAG_OPEN,
                    s.state.config().flagDefaults().defaultOf(Faction.FLAG_OPEN))) {
                s.reject(ReasonCode.NO_INVITE_PENDING);
                return;
            }
        } else {
            invite = s.state.invites().byId(c.viaInviteId());
            if (invite == null || invite.factionOrdinal() != f.idx()
                    || !invite.invitee().equals(c.player())
                    || !InviteRules.isActive(invite, s.state.config().limits().invitesTtlHours(),
                            s.epochMillis)) {
                s.reject(ReasonCode.NOT_INVITED);
                return;
            }
        }
        int max = s.state.config().limits().maxMembers();
        if (max > 0 && FactionAggregates.memberCount(s.state, c.faction()) >= max) {
            s.reject(ReasonCode.FACTION_FULL);
            return;
        }
        int newOrd = s.ensureMember(c.player(), "");
        int defaultRankIdx = RoleRules.defaultRankIndex(f.ranks());
        s.state = s.state.withLedger(s.state.ledger()
                .withFactionHandle(newOrd, c.faction())
                .withRankIdx(newOrd, Math.max(0, defaultRankIdx))
                .withJoinedAt(newOrd, s.epochMillis));
        // Accept = remove the invite atomically with the join (TOCTOU-free).
        if (invite != null) {
            s.state = s.state.withInvites(s.state.invites().remove(invite.id()));
            s.effects.add(new MembershipEffect.InviteRemoved(s.seq, s.origin, c.faction(), c.player(),
                    InviteRemovalReason.ACCEPTED));
        }
        s.effects.add(new MembershipEffect.MemberJoined(s.seq, s.origin, c.faction(), c.player()));
        s.notifyFaction(c.faction(), "member.player-joined", c.player().toString());
    }

    static void leaveFaction(ReduceSupport s, MembershipIntent.LeaveFaction c) {
        int ord = s.memberOrd(c.player());
        if (ord < 0 || !s.state.ledger().has(ord)
                || FactionHandle.ordinal(s.memberFactionHandle(ord)) != FactionHandle.ordinal(c.faction())) {
            s.reject(ReasonCode.NOT_IN_FACTION);
            return;
        }
        s.state = s.state.withLedger(s.state.ledger().withFactionHandle(ord, ReduceSupport.NO_HANDLE).withRankIdx(ord, 0));
        s.effects.add(new MembershipEffect.MemberLeft(s.seq, s.origin, c.faction(), c.player(), false));
    }

    static void kickMember(ReduceSupport s, MembershipIntent.KickMember c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        if (c.actor().equals(c.target())) {
            s.reject(ReasonCode.CANNOT_KICK_SELF);
            return;
        }
        int actorOrd = s.memberOrd(c.actor());
        int targetOrd = s.memberOrd(c.target());
        if (actorOrd < 0 || FactionHandle.ordinal(s.memberFactionHandle(actorOrd)) != f.idx()) {
            s.reject(ReasonCode.NOT_IN_FACTION);
            return;
        }
        if (targetOrd < 0 || FactionHandle.ordinal(s.memberFactionHandle(targetOrd)) != f.idx()) {
            s.reject(ReasonCode.NOT_MEMBER);
            return;
        }
        if (c.target().equals(f.ownerId())) {
            s.reject(ReasonCode.CANNOT_KICK_LEADER);
            return;
        }
        Rank actorRank = s.rankOf(f, actorOrd);
        Rank targetRank = s.rankOf(f, targetOrd);
        if (!RoleRules.canManage(actorRank, targetRank)) {
            s.reject(ReasonCode.MUST_BE_OFFICER);
            return;
        }
        s.state = s.state.withLedger(s.state.ledger().withFactionHandle(targetOrd, ReduceSupport.NO_HANDLE)
                .withRankIdx(targetOrd, 0));
        s.effects.add(new MembershipEffect.MemberLeft(s.seq, s.origin, c.faction(), c.target(), true));
        s.audit(c.faction(), c.actor(), FactionAuditAction.MEMBER_KICK, c.target().toString());
    }

    static void sendInvite(ReduceSupport s, MembershipIntent.SendInvite c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        int inviterOrd = s.memberOrd(c.inviter());
        if (inviterOrd < 0 || FactionHandle.ordinal(s.memberFactionHandle(inviterOrd)) != f.idx()) {
            s.reject(ReasonCode.NOT_IN_FACTION);
            return;
        }
        Rank inviterRank = s.rankOf(f, inviterOrd);
        if (inviterRank == null || !inviterRank.isOfficerOrAbove()) {
            s.reject(ReasonCode.MUST_BE_OFFICER);
            return;
        }
        if (s.state.invites().has(f.idx(), c.invitee())) {
            s.reject(ReasonCode.ALREADY_INVITED);
            return;
        }
        long id = s.seq;
        s.state = s.state.withInvites(s.state.invites().add(
                new InviteTable.Invite(id, f.idx(), c.inviter(), c.invitee(), s.epochMillis)));
        s.effects.add(new MembershipEffect.InviteCreated(s.seq, s.origin, c.faction(), c.invitee(), id));
        s.notify(c.invitee(), "invite.received", f.name());
    }

    static void revokeInvite(ReduceSupport s, MembershipIntent.RevokeInvite c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        InviteTable.Invite in = s.state.invites().find(f.idx(), c.invitee());
        if (in == null) {
            s.reject(ReasonCode.NOT_INVITED);
            return;
        }
        s.state = s.state.withInvites(s.state.invites().remove(in.id()));
        s.effects.add(new MembershipEffect.InviteRemoved(s.seq, s.origin, c.faction(), c.invitee(),
                InviteRemovalReason.REVOKED));
    }

    static void declineInvite(ReduceSupport s, MembershipIntent.DeclineInvite c) {
        Faction f = s.resolve(c.faction());
        int ord = f == null ? FactionHandle.ordinal(c.faction()) : f.idx();
        InviteTable.Invite in = s.state.invites().find(ord, c.invitee());
        if (in == null) {
            s.reject(ReasonCode.NOT_INVITED);
            return;
        }
        s.state = s.state.withInvites(s.state.invites().remove(in.id()));
        s.effects.add(new MembershipEffect.InviteRemoved(s.seq, s.origin, c.faction(), c.invitee(),
                InviteRemovalReason.DECLINED));
    }

    static void declineAllInvites(ReduceSupport s, MembershipIntent.DeclineAllInvites c) {
        InviteTable.Invite[] mine = s.state.invites().forInvitee(c.invitee());
        if (mine.length == 0) {
            return;
        }
        for (InviteTable.Invite in : mine) {
            s.state = s.state.withInvites(s.state.invites().remove(in.id()));
            s.effects.add(new MembershipEffect.InviteRemoved(s.seq, s.origin,
                    s.state.factions().handleOf(in.factionOrdinal()), c.invitee(),
                    InviteRemovalReason.DECLINED));
        }
    }
}
