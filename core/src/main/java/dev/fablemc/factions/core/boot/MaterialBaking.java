package dev.fablemc.factions.core.boot;

import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import org.bukkit.Material;

import dev.fablemc.factions.kernel.config.BakedTables;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.config.PowerConfig;

/**
 * Bakes the AM-14 {@link BakedTables} <b>material bitsets</b> (and the world-power multiplier array)
 * from the RUNTIME {@link Material} registry at boot and on {@code /fa reload} (findings #7/#10 —
 * container/interact protection is DEAD until these bitsets are populated).
 *
 * <p>Material ordinals differ per server version, so classification iterates
 * {@link Material#values()} on the live server and matches by <b>name</b> (never a compile-time
 * constant reference — that would be a sticky {@code NoSuchFieldError} below its floor, and the
 * ordinal a listener reads via {@code block.getType().ordinal()} is only ever the runtime ordinal).
 * The result is folded into {@link ConfigImage#withBaked(BakedTables)} so the bitsets travel inside
 * the snapshot; {@link dev.fablemc.factions.core.listen.InteractProtectionListener} reads
 * {@code snap.config().baked().isContainer(ordinal)}/{@code isInteractable(ordinal)} with no map
 * lookup or boxing.
 *
 * <p>This is the ONE place in {@code core.boot} that touches {@link Material}; it is injected into
 * {@link BootAssembly} as a {@code UnaryOperator<ConfigImage>} so the Bukkit-free write plane never
 * names a server type (the headless boot test passes {@code null} → empty bitsets, its historic
 * behaviour). {@link dev.fablemc.factions.core.config.ReloadsImpl} runs the same finalizer before it
 * swaps the reparsed image so protection stays live across a reload.
 *
 * <p><b>Owning thread(s):</b> the boot/main thread (boot) and the async reload pool ({@code /fa
 * reload}); {@link Material#values()} and name classification are pure and thread-safe. The world
 * index is invoked defensively (a null/throwing lookup for an unloaded world simply skips that
 * multiplier). <b>Mutability:</b> static-only, stateless.
 */
public final class MaterialBaking {

    private MaterialBaking() {
    }

    /**
     * A boot/reload config finalizer that bakes the material bitsets + world multipliers using the
     * runtime {@link Material} registry. {@code worldIndex} resolves a world name to its dense
     * {@code worldIdx} (AM-15) for the world-power-multiplier array; may be {@code null} (multipliers
     * are then skipped and the parse-time zone multipliers are preserved).
     */
    public static UnaryOperator<ConfigImage> finalizer(ToIntFunction<String> worldIndex) {
        return config -> config.withBaked(bake(config, worldIndex));
    }

    /**
     * Rebuilds {@link BakedTables} for {@code config}: it preserves the parse-time zone multipliers
     * and reserved zone-flag words, (re)bakes world multipliers from {@code worldIndex}, and fills
     * the container/interactable material bitsets from the runtime {@link Material} registry.
     */
    public static BakedTables bake(ConfigImage config, ToIntFunction<String> worldIndex) {
        BakedTables prior = config.baked();
        PowerConfig power = config.power();
        BakedTables.Builder builder = new BakedTables.Builder();

        // Preserve the parse-time zone multipliers (indexed by ZoneContext ordinal).
        PowerConfig.ZoneMultipliers zones = power.zoneMultipliers();
        builder.zoneMultiplier(0, zones.safezone());
        builder.zoneMultiplier(1, zones.warzone());
        builder.zoneMultiplier(2, zones.ownClaimed());
        builder.zoneMultiplier(3, zones.enemyClaimed());
        builder.zoneMultiplier(4, zones.wilderness());

        // World-power multipliers by dense worldIdx (AM-15); skip worlds that aren't indexed yet.
        String[] names = power.worldMultiplierNames();
        double[] values = power.worldMultiplierValues();
        int worldCount = Math.min(names.length, values.length);
        for (int i = 0; i < worldCount; i++) {
            int idx = safeIndex(worldIndex, names[i]);
            if (idx >= 0) {
                builder.worldMultiplier(idx, values[i]);
            }
        }

        // Preserve any zone-verdict words a rules-wave baker may have written into the prior tables.
        long[] zoneFlags = prior.zoneFlagTable();
        for (int action = 0; action < zoneFlags.length; action++) {
            if (zoneFlags[action] != 0L) {
                builder.zoneFlagWord(action, zoneFlags[action]);
            }
        }

        // The load-bearing fix: container/interactable material bits from the RUNTIME registry.
        classifyInto(builder);
        return builder.build();
    }

    /**
     * Marks every runtime container/interactable {@link Material} into {@code builder} by its runtime
     * ordinal, classified by NAME so it is version-safe (a 1.7 {@code BURNING_FURNACE} and a 1.14
     * {@code SMOKER} are both handled without ever referencing a possibly-absent enum constant).
     */
    public static void classifyInto(BakedTables.Builder builder) {
        for (Material material : Material.values()) {
            String name = material.name();
            if (name.startsWith("LEGACY_")) {
                continue;   // 1.13+ back-compat aliases: block.getType() never returns these
            }
            if (isContainer(name)) {
                builder.container(material.ordinal());
            } else if (isInteractable(name)) {
                builder.interactable(material.ordinal());
            }
        }
    }

    /** Population count of a bitset word array (boot-report material counts). */
    public static int countBits(long[] words) {
        int count = 0;
        for (long word : words) {
            count += Long.bitCount(word);
        }
        return count;
    }

    /** Containers gate {@code Action.CONTAINER}: chests, furnaces, hoppers, barrels, shulkers, … */
    static boolean isContainer(String name) {
        switch (name) {
            case "CHEST":
            case "TRAPPED_CHEST":
            case "ENDER_CHEST":
            case "FURNACE":
            case "BURNING_FURNACE":   // 1.7–1.12 lit-furnace enum name
            case "BLAST_FURNACE":     // 1.14+
            case "SMOKER":            // 1.14+
            case "HOPPER":
            case "DROPPER":
            case "DISPENSER":
            case "BARREL":            // 1.14+
            case "BREWING_STAND":
                return true;
            default:
                return name.endsWith("SHULKER_BOX");   // 1.11+ (colourless + 16 dyed)
        }
    }

    /** Interactables gate {@code Action.INTERACT}: doors, trapdoors, gates, levers, buttons, … */
    static boolean isInteractable(String name) {
        switch (name) {
            case "LEVER":
            case "JUKEBOX":
            case "NOTE_BLOCK":
            case "BEACON":
            case "ENCHANTING_TABLE":    // 1.13+
            case "ENCHANTMENT_TABLE":   // 1.7–1.12 name
            case "WOODEN_DOOR":         // 1.7–1.12 placed wood door
            case "WOOD_DOOR":           // 1.7–1.12 wood-door item
            case "IRON_DOOR_BLOCK":     // 1.7–1.12 placed iron door
                return true;
            default:
                return name.endsWith("_DOOR")        // OAK_DOOR, IRON_DOOR, SPRUCE_DOOR, …
                        || name.endsWith("TRAPDOOR")  // OAK_TRAPDOOR, IRON_TRAPDOOR, …
                        || name.endsWith("TRAP_DOOR") // 1.7–1.12 TRAP_DOOR / IRON_TRAP_DOOR
                        || name.endsWith("FENCE_GATE")
                        || name.endsWith("_BUTTON")
                        || name.endsWith("ANVIL");    // ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL
        }
    }

    private static int safeIndex(ToIntFunction<String> worldIndex, String name) {
        if (worldIndex == null || name == null) {
            return -1;
        }
        try {
            return worldIndex.applyAsInt(name);
        } catch (RuntimeException offThreadOrAbsent) {
            return -1;   // unloaded world / off-main resolution: leave the multiplier at 1.0
        }
    }
}
