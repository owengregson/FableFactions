package dev.fablemc.factions.kernel.reduce;

import dev.fablemc.factions.kernel.effect.SessionEffect;
import dev.fablemc.factions.kernel.intent.SessionIntent;

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
        s.emit(new SessionEffect.SessionStarted(s.seq, s.origin, c.player(), s.epochMillis));
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
        s.emit(new SessionEffect.SessionEnded(s.seq, s.origin, c.player(), s.epochMillis));
    }

    static void ackInbox(ReduceSupport s, SessionIntent.AckInbox c) {
        s.state = s.state.withInbox(s.state.inbox().removeIds(c.entryIds()));
        s.emit(new SessionEffect.InboxDelivered(s.seq, s.origin, c.player(), c.entryIds()));
    }
}
