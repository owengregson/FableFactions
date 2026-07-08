package dev.fablemc.factions.kernel.vocab;

/**
 * Cold-path enum companion of the {@code Action} protection-action int codes. The hot verdict
 * path keeps the raw {@code int} ({@code Verdicts.decide(..., int action)} signature is pinned and
 * DOES NOT change); this enum is for readable classification off the hot path.
 *
 * <p><b>Owning thread(s):</b> none — a classification value. <b>Mutability:</b> immutable enum.
 * <b>Reducer rule:</b> n/a.
 *
 * <p>{@link #code()} is the stable code matching {@code Action.*}, never the ordinal.
 */
public enum ActionKind {

    /** Block place/break. */
    BUILD(0),
    /** Right-click interact. */
    INTERACT(1),
    /** Container access. */
    CONTAINER(2),
    /** Player-vs-player damage. */
    PVP(3),
    /** Explosion damage. */
    EXPLOSION(4),
    /** Fire spread. */
    FIRE_SPREAD(5),
    /** Liquid flow. */
    LIQUID(6),
    /** Piston movement. */
    PISTON(7),
    /** Entity griefing. */
    ENTITY_GRIEF(8),
    /** Farmland trample. */
    TRAMPLE(9);

    private final int code;

    ActionKind(int code) {
        this.code = code;
    }

    /** The stable code (matching {@code Action.*}). */
    public int code() {
        return code;
    }

    private static final ActionKind[] VALUES = values();

    /** The action with the given stable {@link #code()}; throws for an unknown code. */
    public static ActionKind fromCode(int code) {
        for (ActionKind a : VALUES) {
            if (a.code == code) {
                return a;
            }
        }
        throw new IllegalArgumentException("unknown ActionKind code: " + code);
    }
}
