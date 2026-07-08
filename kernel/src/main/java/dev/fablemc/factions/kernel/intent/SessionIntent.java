package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

/**
 * Session intents: player connect/disconnect and inbox acknowledgement.
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. See {@link Intent} for the hierarchy contract.
 */
public sealed interface SessionIntent extends Intent
        permits SessionIntent.PlayerConnected, SessionIntent.PlayerDisconnected,
        SessionIntent.AckInbox {

    /** A player connected: (re)establish the online set + settle offline power epoch. */
    record PlayerConnected(UUID player, String name, String localeHint) implements SessionIntent {
    }

    /** A player disconnected: leave the online set + settle online power epoch. */
    record PlayerDisconnected(UUID player) implements SessionIntent {
    }

    /** Acknowledge delivery of specific inbox entry ids (deletes exactly the delivered set). */
    record AckInbox(UUID player, long[] entryIds) implements SessionIntent {
    }
}
