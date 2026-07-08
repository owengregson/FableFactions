package dev.fablemc.factions.kernel.state;

/**
 * Relation-kind byte codes (NOT an enum — pinned to {@code byte} for the zero-allocation
 * relation probe on the hot read path, CONTRACTS §2).
 *
 * <p><b>Owning thread(s):</b> none — constants only. <b>Mutability:</b> stateless.
 * <b>Reducer rule:</b> n/a.
 *
 * <p>Ordinals follow the reference hostility ordering low→high:
 * {@code MEMBER(0) < ALLY(1) < TRUCE(2) < NEUTRAL(3) < ENEMY(4)}. {@code MEMBER} is a
 * player-to-faction (same-faction) relation, never a stored faction↔faction edge; absent
 * edges are {@code NEUTRAL} by default.
 */
public final class RelationKind {

    private RelationKind() {
    }

    public static final byte MEMBER = 0;
    public static final byte ALLY = 1;
    public static final byte TRUCE = 2;
    public static final byte NEUTRAL = 3;
    public static final byte ENEMY = 4;

    /** {@code true} for MEMBER or ALLY. */
    public static boolean isFriendly(byte kind) {
        return kind == MEMBER || kind == ALLY;
    }

    /** {@code true} for anything that is not ENEMY. */
    public static boolean isNeutralOrBetter(byte kind) {
        return kind != ENEMY;
    }

    /** {@code true} for ENEMY. */
    public static boolean isHostile(byte kind) {
        return kind == ENEMY;
    }

    /** Lowercase reference name of a kind, or {@code "neutral"} for any unknown code. */
    public static String name(byte kind) {
        switch (kind) {
            case MEMBER:
                return "member";
            case ALLY:
                return "ally";
            case TRUCE:
                return "truce";
            case ENEMY:
                return "enemy";
            case NEUTRAL:
            default:
                return "neutral";
        }
    }
}
