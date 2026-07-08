package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

/**
 * Who initiated an intent, and through which channel.
 *
 * <p><b>Owning thread(s):</b> captured at submit on the calling thread, consumed by the writer;
 * immutable thereafter. <b>Mutability:</b> immutable value. <b>Reducer rule:</b> the reducer
 * reads it for authorization context and copies it onto emitted effects.
 *
 * <p>{@code actor} is nullable for console/system origins. {@code channel} is a {@code byte} code
 * (not an enum) so it stays cheap on the submit path.
 */
public record Origin(UUID actor, int channel) {

    /** A player-issued command / action. */
    public static final int PLAYER = 0;
    /** An admin ({@code /fa}) command. */
    public static final int ADMIN = 1;
    /** A console command. */
    public static final int CONSOLE = 2;
    /** A system-generated intent (ticks, timers, session lifecycle, escrow settlement). */
    public static final int SYSTEM = 3;
    /** An intent submitted through the public API. */
    public static final int API = 4;

    /** A system origin with no actor. */
    public static final Origin SYSTEM_ORIGIN = new Origin(null, SYSTEM);

    /** A console origin with no actor. */
    public static final Origin CONSOLE_ORIGIN = new Origin(null, CONSOLE);

    /** A player origin for {@code actor}. */
    public static Origin player(UUID actor) {
        return new Origin(actor, PLAYER);
    }

    /** An admin origin for {@code actor}. */
    public static Origin admin(UUID actor) {
        return new Origin(actor, ADMIN);
    }
}
