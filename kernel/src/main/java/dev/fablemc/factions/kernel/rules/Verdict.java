package dev.fablemc.factions.kernel.rules;

/**
 * Protection verdict codes returned by {@link Verdicts#decide} (CONTRACTS §2, proposal-C §5c).
 *
 * <p><b>Owning thread(s):</b> none — {@code int} constants only. <b>Mutability:</b> stateless.
 * <b>Reducer rule:</b> n/a — verdicts are read-side (protection) decisions, never state.
 *
 * <p>{@link #ALLOW} is {@code 0}; every {@code DENY_*} is {@code > 0} and maps 1:1 to a
 * message key the listener renders when it cancels the event.
 */
public final class Verdict {

    private Verdict() {
    }

    public static final int ALLOW = 0;
    public static final int DENY_WILDERNESS = 1;
    public static final int DENY_SAFEZONE = 2;
    public static final int DENY_WARZONE = 3;
    public static final int DENY_ENEMY = 4;
    public static final int DENY_NEUTRAL = 5;
    public static final int DENY_ALLY = 6;
    public static final int DENY_TRUCE = 7;
    public static final int DENY_PVP_FLAG = 8;
    public static final int DENY_FRIENDLY_FIRE = 9;
    public static final int DENY_EXPLOSIONS = 10;
    public static final int DENY_FIRE = 11;
    public static final int DENY_INTERNAL = 12;

    /** {@code true} when {@code verdict} permits the action. */
    public static boolean allowed(int verdict) {
        return verdict == ALLOW;
    }
}
