package dev.fablemc.factions.kernel.vocab;

import dev.fablemc.factions.kernel.state.RelationKind;

/**
 * The cold-path enum companion of the {@link RelationKind} byte codes. Effects, storage and the
 * API layer carry {@code Relation}; the hot relation-probe read path keeps the raw {@code byte}
 * (CONTRACTS §2 hot-path pin) — {@link RelationKind} bytes inside faction relation-edge arrays
 * stay primitive.
 *
 * <p><b>Owning thread(s):</b> none — a classification value. <b>Mutability:</b> immutable enum.
 * <b>Reducer rule:</b> n/a.
 *
 * <p>{@link #code()} is the stable {@code byte} code (matching {@link RelationKind}); ordinals
 * follow the reference hostility ordering {@code MEMBER(0) < ALLY(1) < TRUCE(2) < NEUTRAL(3) <
 * ENEMY(4)}. {@link #fromCode(byte)} is the inverse.
 */
public enum Relation {

    /** Player-to-faction (same-faction) relation. */
    MEMBER(RelationKind.MEMBER),
    /** An ally: shared protection, friendly-fire rules apply. */
    ALLY(RelationKind.ALLY),
    /** A truce: limited protection, no PvP by default. */
    TRUCE(RelationKind.TRUCE),
    /** No declared relation (the default between two factions). */
    NEUTRAL(RelationKind.NEUTRAL),
    /** An enemy: hostile, land is overclaimable when raidable. */
    ENEMY(RelationKind.ENEMY);

    private final byte code;

    Relation(byte code) {
        this.code = code;
    }

    /** The stable {@code byte} code (matching {@link RelationKind}). */
    public byte code() {
        return code;
    }

    private static final Relation[] VALUES = values();

    /** The relation with the given stable {@link #code()}; throws for an unknown code. */
    public static Relation fromCode(byte code) {
        for (Relation r : VALUES) {
            if (r.code == code) {
                return r;
            }
        }
        throw new IllegalArgumentException("unknown Relation code: " + code);
    }
}
