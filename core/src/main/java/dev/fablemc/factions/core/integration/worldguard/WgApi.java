package dev.fablemc.factions.core.integration.worldguard;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import dev.fablemc.factions.core.integration.Reflect;

/**
 * The shared reflective plumbing for the WorldGuard 7 API (ref-integrations §2.3/§2.5), used by both
 * {@link ReflectiveTerritoryGuard} and {@link WgRegionMirror}. Every entry point resolves the
 * provider classes by name and swallows failures to {@code null}/absent — WorldGuard itself is
 * never imported.
 *
 * <p><b>Owning thread(s):</b> the region thread (region-container access is location-bound, AM-12).
 * <b>Mutability:</b> stateless.
 */
final class WgApi {

    private static final String WORLD_GUARD = "com.sk89q.worldguard.WorldGuard";
    private static final String WG_PLUGIN = "com.sk89q.worldguard.bukkit.WorldGuardPlugin";
    private static final String BUKKIT_ADAPTER = "com.sk89q.worldedit.bukkit.BukkitAdapter";

    private WgApi() {
    }

    /** {@code true} when the WorldGuard 7 platform classes resolve on the classpath. */
    static boolean present() {
        return Reflect.classPresent(WORLD_GUARD) && Reflect.classPresent(BUKKIT_ADAPTER);
    }

    /** {@code WorldGuard.getInstance().getPlatform().getRegionContainer()}, or {@code null}. */
    static Object regionContainer() {
        Object wg = Reflect.callStatic(Reflect.findClass(WORLD_GUARD), "getInstance");
        Object platform = Reflect.call(wg, "getPlatform");
        return Reflect.call(platform, "getRegionContainer");
    }

    /** A fresh {@code RegionQuery} from the region container, or {@code null}. */
    static Object createQuery() {
        return Reflect.call(regionContainer(), "createQuery");
    }

    /** The {@code RegionManager} for {@code world} (WG in-memory store), or {@code null}. */
    static Object regionManager(World world) {
        Object weWorld = adaptWorld(world);
        return weWorld == null ? null : Reflect.call1(regionContainer(), "get", weWorld);
    }

    /** {@code BukkitAdapter.adapt(location)} → a WorldEdit location, or {@code null}. */
    static Object adaptLocation(Location location) {
        return Reflect.callStatic1(Reflect.findClass(BUKKIT_ADAPTER), "adapt", location);
    }

    /** {@code BukkitAdapter.adapt(world)} → a WorldEdit world, or {@code null}. */
    static Object adaptWorld(World world) {
        return Reflect.callStatic1(Reflect.findClass(BUKKIT_ADAPTER), "adapt", world);
    }

    /** {@code WorldGuardPlugin.inst().wrapPlayer(player)} → a {@code LocalPlayer}, or {@code null}. */
    static Object wrapPlayer(Player player) {
        Object inst = Reflect.callStatic(Reflect.findClass(WG_PLUGIN), "inst");
        return Reflect.call1(inst, "wrapPlayer", player);
    }
}
