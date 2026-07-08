package dev.fablemc.factions.core.integration.dynmap;

/**
 * The load-bearing dynmap presentation constants (ref-integrations §5.2/§5.3, checklist §3): the
 * 8-entry territory palette, the marker fill/line styling, the layer id/label, and the deterministic
 * per-faction colour picker {@code PALETTE[abs(seed.hashCode()) % 8]}.
 *
 * <p><b>Owning thread(s):</b> pure static, any thread. <b>Mutability:</b> the palette array is a
 * private constant; callers only read colours through {@link #colorFor}.
 */
public final class DynmapPalette {

    /** The FableFactions dynmap marker-set id (distinct standalone identity). */
    public static final String LAYER_ID = "fablefactions";
    /** The FableFactions dynmap marker-set label. */
    public static final String LAYER_LABEL = "Factions";
    /** The marker-set layer priority. */
    public static final int LAYER_PRIORITY = 5;

    /** Area fill opacity (35%). */
    public static final double FILL_OPACITY = 0.35;
    /** Area outline weight (1px). */
    public static final int LINE_WEIGHT = 1;
    /** Area outline opacity (full). */
    public static final double LINE_OPACITY = 1.0;

    /** The marker-id field separator — disjoint from UUID hyphens, world chars, and digits. */
    public static final char SEPARATOR = '~';

    private static final int[] PALETTE = {
            0x3399ff, 0xff6633, 0x33cc33, 0xff3399, 0x9966ff, 0xffcc00, 0x00cccc, 0xff6600,
    };

    private DynmapPalette() {
    }

    /** Deterministic 0xRRGGBB colour for {@code seed} (the reference {@code abs(hashCode) % 8}). */
    public static int colorFor(String seed) {
        return PALETTE[Math.abs(seed.hashCode()) % PALETTE.length];
    }

    /** The marker id for a claimed chunk: {@code <factionSeg>~<world>~<cx>~<cz>}. */
    public static String markerId(String factionSeg, String world, int chunkX, int chunkZ) {
        return factionSeg + SEPARATOR + world + SEPARATOR + chunkX + SEPARATOR + chunkZ;
    }
}
