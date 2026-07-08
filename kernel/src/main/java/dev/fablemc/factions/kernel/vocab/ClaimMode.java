package dev.fablemc.factions.kernel.vocab;

/**
 * The shape/strategy a claim request used, carried by {@code ClaimChunks} (the {@code :core}
 * shape collectors resolve the command into one of these before submitting the intent).
 *
 * <p><b>Owning thread(s):</b> set by the command layer, carried through the reducer.
 * <b>Mutability:</b> immutable enum. <b>Reducer rule:</b> n/a — advisory metadata; the keys array
 * is authoritative.
 *
 * <p>{@link #code()} is the stable wire/DB code, never the ordinal.
 */
public enum ClaimMode {

    /** A single chunk (the default). */
    SINGLE(0),
    /** Auto-claim as the player walks. */
    AUTO(1),
    /** A square radius around the player. */
    SQUARE(2),
    /** A circular radius around the player. */
    CIRCLE(3),
    /** Flood-fill the enclosed region. */
    FILL(4),
    /** The nearby unclaimed chunks. */
    NEARBY(5),
    /** A specific chunk coordinate. */
    AT(6);

    private final int code;

    ClaimMode(int code) {
        this.code = code;
    }

    /** The stable wire/DB code. */
    public int code() {
        return code;
    }

    private static final ClaimMode[] VALUES = values();

    /** The mode with the given stable {@link #code()}; throws for an unknown code. */
    public static ClaimMode fromCode(int code) {
        for (ClaimMode m : VALUES) {
            if (m.code == code) {
                return m;
            }
        }
        throw new IllegalArgumentException("unknown ClaimMode code: " + code);
    }
}
