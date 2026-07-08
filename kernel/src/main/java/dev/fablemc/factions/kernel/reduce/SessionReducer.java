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
 * Session intents: player connect-disconnect power settlement / inbox acknowledgement.
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved this code unchanged).
 */
final class SessionReducer {

    private SessionReducer() {
    }

    static void reduce(ReduceSupport s, SessionIntent i) {
        if (i instanceof SessionIntent.PlayerConnected x) {
            playerConnected(s, x);
        } else if (i instanceof SessionIntent.PlayerDisconnected x) {
            playerDisconnected(s, x);
        } else if (i instanceof SessionIntent.AckInbox x) {
            ackInbox(s, x);
        } else {
            throw new IllegalStateException("unhandled session intent: " + i.getClass().getName());
        }
    }
    static void playerConnected(ReduceSupport s, SessionIntent.PlayerConnected c) {
        int ord = s.ensureMember(c.player(), c.name());
        if (c.name() != null && !c.name().isEmpty()) {
            s.state = s.state.withLedger(s.state.ledger().withNameLast(ord, c.name()));
        }
        // Offline→online transition is an epoch boundary: settle the offline accrual first.
        s.settleMember(ord, false);
        s.state = s.state.withOnline(s.state.online().with(ord));
        s.state = s.state.withLedger(s.state.ledger().withLastActivity(ord, s.epochMillis));
        s.effects.add(new SessionEffect.SessionStarted(s.seq, s.origin, c.player(), s.epochMillis));
    }

    static void playerDisconnected(ReduceSupport s, SessionIntent.PlayerDisconnected c) {
        int ord = s.memberOrd(c.player());
        if (ord < 0 || !s.state.ledger().has(ord)) {
            return;
        }
        // Online→offline transition: settle the online accrual before leaving the set.
        s.settleMember(ord, true);
        s.state = s.state.withOnline(s.state.online().without(ord));
        s.state = s.state.withLedger(s.state.ledger().withLastActivity(ord, s.epochMillis));
        s.effects.add(new SessionEffect.SessionEnded(s.seq, s.origin, c.player(), s.epochMillis));
    }

    static void ackInbox(ReduceSupport s, SessionIntent.AckInbox c) {
        s.state = s.state.withInbox(s.state.inbox().removeIds(c.entryIds()));
        s.effects.add(new SessionEffect.InboxDelivered(s.seq, s.origin, c.player(), c.entryIds()));
    }
}
