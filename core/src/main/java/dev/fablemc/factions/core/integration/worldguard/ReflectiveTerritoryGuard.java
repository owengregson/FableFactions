package dev.fablemc.factions.core.integration.worldguard;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import dev.fablemc.factions.core.integration.Reflect;

/**
 * The reflection-backed {@link TerritoryGuard}: drives WorldGuard's {@code RegionQuery.testBuild}
 * and {@code RegionManager.getRegion} by name so {@code :core} never imports {@code com.sk89q}
 * (ref-integrations §2.3).
 *
 * <p><b>Owning thread(s):</b> the region thread. <b>Mutability:</b> immutable (holds only the
 * sync-mode flag). Failure modes are load-bearing: {@link #canModifyTerritory} fails <b>open</b>
 * (any error ⇒ {@code true}); {@link #isFactionRegion} fails <b>closed</b> (any error ⇒ {@code false}).
 */
public final class ReflectiveTerritoryGuard implements TerritoryGuard {

    private final boolean syncEnabled;

    /** {@code syncEnabled} mirrors {@code integrations.worldguard-sync-regions}. */
    public ReflectiveTerritoryGuard(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    @Override
    public boolean canModifyTerritory(Player player, Location location) {
        try {
            Object query = WgApi.createQuery();
            Object weLoc = WgApi.adaptLocation(location);
            Object localPlayer = WgApi.wrapPlayer(player);
            if (query == null || weLoc == null || localPlayer == null) {
                return true;
            }
            Method testBuild = findTestBuild(query, weLoc, localPlayer);
            if (testBuild == null) {
                return true;
            }
            Object result = Reflect.invoke(query, testBuild, testBuildArgs(testBuild, weLoc, localPlayer));
            return !(result instanceof Boolean bool) || bool;
        } catch (RuntimeException | LinkageError failOpen) {
            return true;
        }
    }

    @Override
    public boolean syncsBuildProtection() {
        return syncEnabled;
    }

    @Override
    public boolean isFactionRegion(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        Object regionManager = WgApi.regionManager(world);
        if (regionManager == null) {
            return false;
        }
        String regionId = WgRegionNames.regionName(world.getName(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4);
        return Reflect.call1(regionManager, "getRegion", regionId) != null;
    }

    /** Finds {@code testBuild(Location, LocalPlayer, StateFlag...)} by shape. */
    private static Method findTestBuild(Object query, Object weLoc, Object localPlayer) {
        for (Method m : query.getClass().getMethods()) {
            if (!m.getName().equals("testBuild")) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length >= 2 && params[0].isInstance(weLoc) && params[1].isInstance(localPlayer)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /** Builds the invocation args, padding the trailing {@code StateFlag...} varargs with an empty array. */
    private static Object[] testBuildArgs(Method testBuild, Object weLoc, Object localPlayer) {
        Class<?>[] params = testBuild.getParameterTypes();
        Object[] args = new Object[params.length];
        args[0] = weLoc;
        args[1] = localPlayer;
        if (params.length == 3 && params[2].isArray()) {
            args[2] = Array.newInstance(params[2].getComponentType(), 0);
        }
        return args;
    }
}
