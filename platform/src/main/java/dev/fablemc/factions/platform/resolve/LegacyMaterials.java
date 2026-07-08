package dev.fablemc.factions.platform.resolve;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import dev.fablemc.factions.platform.probe.Probes;

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

    /** The single flattening fact ({@link Probes#flattened()}): identity above 1.13, table below. */
    private static final boolean FLATTENED = Probes.flattened();

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
        // The 16 colours in legacy WOOL data order (0..15); the legacy dye data is the
        // exact inverse of it (BLACK dye is 0 where BLACK wool is 15), so one loop derives
        // both. Beds all map onto the single colourless legacy BED item (id 355).
        String[] colours = {
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
            "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"
        };
        Map<String, Legacy> table = new HashMap<>();
        for (int i = 0; i < colours.length; i++) {
            table.put(colours[i] + "_WOOL", new Legacy("WOOL", i));
            table.put(colours[i] + "_DYE", new Legacy("INK_SACK", 15 - i));
            table.put(colours[i] + "_BED", new Legacy("BED", 0));
        }
        // Skull: the player head is SKULL_ITEM data 3.
        table.put("PLAYER_HEAD", new Legacy("SKULL_ITEM", 3));
        return Map.copyOf(table);
    }
}
