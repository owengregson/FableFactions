package dev.fablemc.factions.kernel.vocab;

/**
 * The two system-zone kinds. The {@link #code()} values intentionally match the sentinel faction
 * ordinals ({@code FactionHandle} ordinals 0 = SAFEZONE, 1 = WARZONE), so a zone kind and its
 * sentinel faction ordinal are interchangeable by value.
 *
 * <p><b>Owning thread(s):</b> none — a classification value. <b>Mutability:</b> immutable enum.
 * <b>Reducer rule:</b> n/a. Zone-assignment intents/effects still carry the raw {@code zoneOrdinal}
 * (a faction ordinal — see the record javadoc), because those slots are keyed by ordinal on the
 * hot atlas path; this enum is the cold-path companion for readable classification.
 */
public enum ZoneKind {

    /** The safezone sentinel (ordinal 0). */
    SAFEZONE(0),
    /** The warzone sentinel (ordinal 1). */
    WARZONE(1);

    private final int code;

    ZoneKind(int code) {
        this.code = code;
    }

    /** The stable code (equal to the sentinel faction ordinal). */
    public int code() {
        return code;
    }

    private static final ZoneKind[] VALUES = values();

    /** The zone kind with the given stable {@link #code()}; throws for an unknown code. */
    public static ZoneKind fromCode(int code) {
        for (ZoneKind z : VALUES) {
            if (z.code == code) {
                return z;
            }
        }
        throw new IllegalArgumentException("unknown ZoneKind code: " + code);
    }
}
