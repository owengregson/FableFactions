package dev.fablemc.factions.core.integration.worldguard;

import java.util.Locale;

/**
 * The load-bearing WorldGuard region-id formula (ref-integrations §2.3, checklist §3). A faction
 * chunk maps to region id {@code f_<world>_<x|nX>_<z|nZ>}: the world name lower-cased with every
 * non-{@code [a-z0-9]} character replaced by {@code _}, and each <b>chunk</b> coordinate rendered
 * with an {@code n}-prefixed absolute value when negative. Both the territory guard's
 * {@code isFactionRegion} probe and the region-sync mirror derive ids here so they never diverge.
 *
 * <p><b>Owning thread(s):</b> pure static, any thread. <b>Mutability:</b> stateless.
 *
 * <p>Example: world {@code world_nether}, chunk {@code (-3, 5)} ⇒ {@code f_world_nether_n3_5}.
 */
public final class WgRegionNames {

    /** The region-id prefix marking a faction-owned cuboid. */
    public static final String PREFIX = "f_";

    private WgRegionNames() {
    }

    /** The region id for {@code worldName}'s chunk {@code (chunkX, chunkZ)}. */
    public static String regionName(String worldName, int chunkX, int chunkZ) {
        String w = worldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        return PREFIX + w + "_" + coord(chunkX) + "_" + coord(chunkZ);
    }

    private static String coord(int value) {
        return value < 0 ? "n" + (-value) : Integer.toString(value);
    }
}
