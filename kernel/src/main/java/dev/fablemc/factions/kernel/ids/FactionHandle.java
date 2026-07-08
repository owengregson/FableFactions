package dev.fablemc.factions.kernel.ids;

/**
 * Generation-tagged faction handle codec (AM-6).
 *
 * <p><b>Owning thread(s):</b> none — pure static functions. <b>Mutability:</b> stateless.
 * <b>Reducer rule:</b> n/a.
 *
 * <p>Every faction reference held anywhere outside {@code FactionArena} is an {@code int}
 * {@code handle = (generation << 20) | ordinal}. The arena bumps a slot's generation on free,
 * so a stale handle can never resolve to a reincarnated faction: {@code resolve(handle)}
 * returns {@code null} on generation mismatch. The claim atlas stores handles in its owner
 * slots for the same reason (stale claim ⇒ wilderness-after-mismatch).
 *
 * <p>Layout: ordinal in the low 20 bits (0..1,048,575), generation in the high 12 bits
 * (0..4,095). {@link #WILDERNESS} ({@code -1}) is a reserved sentinel meaning "no faction /
 * empty atlas slot" and is never produced by {@link #handle}; it must be tested with
 * {@code == WILDERNESS} rather than decoded.
 */
public final class FactionHandle {

    private FactionHandle() {
    }

    /** Number of bits reserved for the ordinal. */
    public static final int ORDINAL_BITS = 20;
    private static final int ORDINAL_MASK = (1 << ORDINAL_BITS) - 1; // 0xFFFFF

    /** The empty / no-faction sentinel: atlas empty slot, unclaimed chunk owner. */
    public static final int WILDERNESS = -1;

    /** Reserved ordinal of the SAFEZONE sentinel faction ({@code isNormal()==false}). */
    public static final int SAFEZONE_ORDINAL = 0;
    /** Reserved ordinal of the WARZONE sentinel faction ({@code isNormal()==false}). */
    public static final int WARZONE_ORDINAL = 1;
    /** First ordinal available to normal player factions. */
    public static final int FIRST_NORMAL_ORDINAL = 2;

    /** The largest generation a slot can hold before wrapping (12 bits). */
    public static final int MAX_GENERATION = (1 << (32 - ORDINAL_BITS)) - 1; // 4095
    /** The largest ordinal representable (20 bits). */
    public static final int MAX_ORDINAL = ORDINAL_MASK; // 1048575

    /** Packs a generation and ordinal into a handle. */
    public static int handle(int generation, int ordinal) {
        return (generation << ORDINAL_BITS) | (ordinal & ORDINAL_MASK);
    }

    /** Low-20-bit ordinal of a handle. */
    public static int ordinal(int handle) {
        return handle & ORDINAL_MASK;
    }

    /** High-12-bit generation of a handle. */
    public static int generation(int handle) {
        return handle >>> ORDINAL_BITS;
    }

    /** {@code true} for a normal player faction ordinal (not SAFEZONE / WARZONE / wilderness). */
    public static boolean isNormalOrdinal(int ordinal) {
        return ordinal >= FIRST_NORMAL_ORDINAL;
    }
}
