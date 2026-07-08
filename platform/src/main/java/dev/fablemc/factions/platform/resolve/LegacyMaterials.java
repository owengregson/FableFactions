package dev.fablemc.factions.platform.resolve;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The single material-flattening seam (CONTRACTS §3, version-deltas Risk #3). The kernel
 * and GUI model speak MODERN material names only; this resolver maps them to the
 * pre-1.13 {@code (legacy enum name, data byte)} pair for the handful of GUI icon
 * families a factions plugin draws — wool, dye, bed and skull. On a flattened (1.13+)
 * server the whole seam is identity; the data-byte {@code ItemStack} constructor is
 * touched ONLY in the legacy branch, so it never links a deprecated shape on modern.
 *
 * <p>Owning thread(s): the caller's (icon creation on the region/main thread).
 * Mutability class: static-only; the table is immutable after class load.
 */
public final class LegacyMaterials {

    /** One boot flattening probe: {@code WHITE_WOOL} is a post-flattening constant. */
    private static final boolean FLATTENED = Material.getMaterial("WHITE_WOOL") != null;

    /** A pre-flattening material identity: the legacy enum NAME plus its data byte. */
    private record Legacy(@NotNull String name, int data) {}

    /** Modern name → pre-flattening (legacy name, data). ~40 GUI icons across four families. */
    private static final Map<String, Legacy> TABLE = buildTable();

    private LegacyMaterials() {}

    /**
     * The {@link Material} for a modern name. Identity via {@code getMaterial} on a
     * flattened server; on legacy, the table's legacy enum (data lives on the icon, not
     * the Material). Falls back to {@code STONE} for an unknown/absent name so an icon
     * never fails to render.
     */
    public static @NotNull Material material(@NotNull String modernName) {
        if (FLATTENED) {
            Material modern = Material.getMaterial(modernName);
            return modern != null ? modern : Material.STONE;
        }
        Legacy legacy = TABLE.get(modernName);
        String lookup = legacy != null ? legacy.name() : modernName;
        Material found = Material.getMaterial(lookup);
        return found != null ? found : Material.STONE;
    }

    /**
     * A GUI icon {@link ItemStack} for a modern material name. On a flattened server this
     * is {@code new ItemStack(material, amount)}; on a legacy server the data-byte
     * constructor {@code new ItemStack(Material, int, short)} carries the colour/variant —
     * and that constructor appears ONLY inside this legacy branch (version-deltas Risk #3).
     */
    public static @NotNull ItemStack icon(@NotNull String modernName, int amount) {
        int count = Math.max(1, amount);
        if (FLATTENED) {
            return new ItemStack(material(modernName), count);
        }
        Legacy legacy = TABLE.get(modernName);
        if (legacy == null) {
            Material direct = Material.getMaterial(modernName);
            return new ItemStack(direct != null ? direct : Material.STONE, count);
        }
        Material base = Material.getMaterial(legacy.name());
        // Data-byte constructor — LEGACY BRANCH ONLY (never linked on a flattened server).
        return new ItemStack(base != null ? base : Material.STONE, count, (short) legacy.data());
    }

    /** For the boot report: identity vs the legacy translation table. */
    public static String describe() {
        return FLATTENED ? "flattened (identity)" : "legacy (" + TABLE.size() + "-entry modern→legacy table)";
    }

    private static Map<String, Legacy> buildTable() {
        Map<String, Legacy> table = new HashMap<>();
        // Wool: WOOL + colour data 0..15.
        String[] wool = {
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
            "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"
        };
        for (int i = 0; i < wool.length; i++) {
            table.put(wool[i] + "_WOOL", new Legacy("WOOL", (byte) i));
        }
        // Dye: INK_SACK + data (the INVERSE-of-wool legacy dye ordering).
        table.put("BLACK_DYE", new Legacy("INK_SACK", (byte) 0));
        table.put("RED_DYE", new Legacy("INK_SACK", (byte) 1));
        table.put("GREEN_DYE", new Legacy("INK_SACK", (byte) 2));
        table.put("BROWN_DYE", new Legacy("INK_SACK", (byte) 3));
        table.put("BLUE_DYE", new Legacy("INK_SACK", (byte) 4));
        table.put("PURPLE_DYE", new Legacy("INK_SACK", (byte) 5));
        table.put("CYAN_DYE", new Legacy("INK_SACK", (byte) 6));
        table.put("LIGHT_GRAY_DYE", new Legacy("INK_SACK", (byte) 7));
        table.put("GRAY_DYE", new Legacy("INK_SACK", (byte) 8));
        table.put("PINK_DYE", new Legacy("INK_SACK", (byte) 9));
        table.put("LIME_DYE", new Legacy("INK_SACK", (byte) 10));
        table.put("YELLOW_DYE", new Legacy("INK_SACK", (byte) 11));
        table.put("LIGHT_BLUE_DYE", new Legacy("INK_SACK", (byte) 12));
        table.put("MAGENTA_DYE", new Legacy("INK_SACK", (byte) 13));
        table.put("ORANGE_DYE", new Legacy("INK_SACK", (byte) 14));
        table.put("WHITE_DYE", new Legacy("INK_SACK", (byte) 15));
        // Bed: the legacy item is the colourless BED (item id 355); every colour maps to it.
        for (String colour : wool) {
            table.put(colour + "_BED", new Legacy("BED", (byte) 0));
        }
        // Skull: the player head is SKULL_ITEM data 3.
        table.put("PLAYER_HEAD", new Legacy("SKULL_ITEM", (byte) 3));
        return Map.copyOf(table);
    }
}
