package dev.fablemc.factions.kernel.rules;

/**
 * Protection action codes fed to {@link Verdicts#decide} (CONTRACTS §2).
 *
 * <p><b>Owning thread(s):</b> none — {@code int} constants only. <b>Mutability:</b> stateless.
 * <b>Reducer rule:</b> n/a.
 *
 * <p>{@link #BUILD}/{@link #INTERACT}/{@link #CONTAINER} are territory-modify actions;
 * {@link #PVP} is player-vs-player damage; {@link #EXPLOSION}/{@link #FIRE_SPREAD} are the
 * flag-gated environmental actions; the remainder ({@link #LIQUID}/{@link #PISTON}/
 * {@link #ENTITY_GRIEF}/{@link #TRAMPLE}) are the D-4 grief-matrix additions, treated as
 * territory-modify actions by the verdict engine.
 */
public final class Action {

    private Action() {
    }

    public static final int BUILD = 0;
    public static final int INTERACT = 1;
    public static final int CONTAINER = 2;
    public static final int PVP = 3;
    public static final int EXPLOSION = 4;
    public static final int FIRE_SPREAD = 5;
    public static final int LIQUID = 6;
    public static final int PISTON = 7;
    public static final int ENTITY_GRIEF = 8;
    public static final int TRAMPLE = 9;

    /** {@code true} for the territory-modify actions (build-equivalent gating). */
    public static boolean isBuildLike(int action) {
        switch (action) {
            case BUILD:
            case INTERACT:
            case CONTAINER:
            case LIQUID:
            case PISTON:
            case ENTITY_GRIEF:
            case TRAMPLE:
                return true;
            default:
                return false;
        }
    }
}
