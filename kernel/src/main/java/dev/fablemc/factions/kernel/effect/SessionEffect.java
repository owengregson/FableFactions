package dev.fablemc.factions.kernel.effect;

import java.util.UUID;

import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * Session effects: session start/end and the inbox queue/deliver pair.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * See {@link Effect} for the hierarchy contract.
 */
public sealed interface SessionEffect extends Effect
        permits SessionEffect.SessionStarted, SessionEffect.SessionEnded,
        SessionEffect.InboxQueued, SessionEffect.InboxDelivered {

    record SessionStarted(long seq, Origin origin, UUID player, long lastActivity)
            implements SessionEffect {
    }

    record SessionEnded(long seq, Origin origin, UUID player, long lastActivity)
            implements SessionEffect {
    }

    record InboxQueued(long seq, Origin origin, UUID player, MessageKey key, String[] args)
            implements SessionEffect {
    }

    record InboxDelivered(long seq, Origin origin, UUID player, long[] ids)
            implements SessionEffect {
    }
}
