package dev.fablemc.factions.core.session;

/**
 * The first-party combat-tag window policy over the confined {@link PlayerSession} combat field
 * (proposal-C §4.6 / D-6, ARCHITECTURE AM-14). A hit tags both parties for a fixed window; travel
 * ({@code /f home}, {@code /f warp}) and the fly-on-threat re-check consult {@link #inCombat} to
 * block escapes. The whole feature is config-gated: when disabled the window is never applied and
 * every {@link #inCombat} query is {@code false}, so combat gating vanishes cleanly.
 *
 * <p><b>Owning thread(s):</b> operates on a {@link PlayerSession}, so every mutating call MUST run
 * on that player's region thread (the session is confined, AM-14); {@code inCombat}/{@code
 * remaining} are pure reads of the same confined field. <b>Mutability:</b> immutable value (holds
 * only the enabled flag and the window length); the mutation lands on the passed session.
 */
public final class CombatTags {

    /** The reference combat-tag lifetime after a hit ({@value} ms) — matches the PvP listener. */
    public static final long DEFAULT_WINDOW_MILLIS = 15_000L;

    private final boolean enabled;
    private final long windowMillis;

    /**
     * Builds the policy. {@code windowMillis} must be positive; {@code enabled} gates the whole
     * feature (a disabled policy never tags and reports {@link #inCombat} as {@code false}).
     */
    public CombatTags(boolean enabled, long windowMillis) {
        if (windowMillis <= 0L) {
            throw new IllegalArgumentException("windowMillis must be positive");
        }
        this.enabled = enabled;
        this.windowMillis = windowMillis;
    }

    /** The reference-default policy: enabled with a {@value #DEFAULT_WINDOW_MILLIS} ms window. */
    public static CombatTags defaults() {
        return new CombatTags(true, DEFAULT_WINDOW_MILLIS);
    }

    /** Whether combat tagging is active on this server. */
    public boolean enabled() {
        return enabled;
    }

    /** The combat-tag window length in milliseconds. */
    public long windowMillis() {
        return windowMillis;
    }

    /**
     * Tags {@code session} as in combat until {@code nowEpochMillis + windowMillis} (only ever
     * extends the window). A no-op when the feature is disabled or {@code session} is {@code null}.
     * Must run on the session owner's region thread.
     */
    public void tag(PlayerSession session, long nowEpochMillis) {
        if (!enabled || session == null) {
            return;
        }
        session.tagCombat(nowEpochMillis + windowMillis);
    }

    /** Clears any active combat window on {@code session}. Region-thread confined; null-safe. */
    public void clear(PlayerSession session) {
        if (session != null) {
            session.clearCombat();
        }
    }

    /**
     * Whether {@code session} is inside its combat window at {@code nowEpochMillis}. Always
     * {@code false} when the feature is disabled or the session is {@code null}.
     */
    public boolean inCombat(PlayerSession session, long nowEpochMillis) {
        return enabled && session != null && session.inCombat(nowEpochMillis);
    }

    /**
     * Milliseconds left in {@code session}'s combat window at {@code nowEpochMillis}, or {@code 0}
     * when not tagged / disabled (never negative).
     */
    public long remaining(PlayerSession session, long nowEpochMillis) {
        if (!enabled || session == null) {
            return 0L;
        }
        long left = session.combatTagUntil() - nowEpochMillis;
        return left > 0L ? left : 0L;
    }
}
