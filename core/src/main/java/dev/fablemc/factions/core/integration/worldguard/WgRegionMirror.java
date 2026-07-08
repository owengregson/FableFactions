package dev.fablemc.factions.core.integration.worldguard;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.World;

import dev.fablemc.factions.core.integration.Reflect;
import dev.fablemc.factions.platform.sched.Scheduling;

/**
 * Mirrors faction claims as WorldGuard cuboid regions when {@code integrations.worldguard-sync-regions}
 * is on (ref-integrations §2.5). Driven by the writer's {@code ExternalEffect.WgRegionUpsert/Remove}
 * effects (kernel authority), each region write is routed onto the owning region thread by the
 * effect sink (AM-12) and the persistence {@code save()} is hopped to async, matching the reference's
 * incremental-async-save semantics.
 *
 * <p><b>Owning thread(s):</b> {@link #upsert}/{@link #remove} run on the region thread that owns the
 * chunk; the {@code save()} they trigger runs on an async thread. <b>Mutability:</b> holds only the
 * scheduler and logger; all region state lives in WorldGuard's in-memory store.
 */
public final class WgRegionMirror {

    private static final String BLOCK_VECTOR_3 = "com.sk89q.worldedit.math.BlockVector3";
    private static final String PROTECTED_CUBOID = "com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion";

    private final Scheduling scheduling;
    private final Logger logger;

    /** Constructor injection: the scheduler (for async save) and the plugin logger. */
    public WgRegionMirror(Scheduling scheduling, Logger logger) {
        this.scheduling = scheduling;
        this.logger = logger;
    }

    /**
     * Creates or replaces the region for {@code (chunkX, chunkZ)} with {@code members} as its build
     * domain (SAFEZONE/WARZONE pass an empty list ⇒ nobody may build via WG). Runs on the region
     * thread; {@code save()} is scheduled async.
     */
    public void upsert(World world, int chunkX, int chunkZ, List<UUID> members) {
        try {
            Object regionManager = WgApi.regionManager(world);
            if (regionManager == null) {
                return;
            }
            Object region = buildRegion(world, chunkX, chunkZ);
            if (region == null) {
                return;
            }
            Object domain = Reflect.call(region, "getMembers");
            for (int i = 0; i < members.size(); i++) {
                Reflect.call1(domain, "addPlayer", members.get(i));
            }
            Reflect.call1(regionManager, "addRegion", region);   // overwrites any existing id
            saveAsync(regionManager, world);
        } catch (RuntimeException | LinkageError failed) {
            logger.log(Level.WARNING, "WG sync: upsert failed for " + world.getName(), failed);
        }
    }

    /** Removes the region for {@code (chunkX, chunkZ)} if present; {@code save()} is scheduled async. */
    public void remove(World world, int chunkX, int chunkZ) {
        try {
            Object regionManager = WgApi.regionManager(world);
            if (regionManager == null) {
                return;
            }
            String id = WgRegionNames.regionName(world.getName(), chunkX, chunkZ);
            if (Reflect.call1(regionManager, "getRegion", id) == null) {
                return;
            }
            Reflect.call1(regionManager, "removeRegion", id);
            saveAsync(regionManager, world);
        } catch (RuntimeException | LinkageError failed) {
            logger.log(Level.WARNING, "WG sync: remove failed for " + world.getName(), failed);
        }
    }

    /** Builds a full-height {@code ProtectedCuboidRegion} spanning the 16×16 chunk. */
    private Object buildRegion(World world, int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int minY = minHeight(world);
        int maxY = world.getMaxHeight() - 1;
        Object min = blockVector3At(minX, minY, minZ);
        Object max = blockVector3At(minX + 15, maxY, minZ + 15);
        if (min == null || max == null) {
            return null;
        }
        Class<?> cuboid = Reflect.findClass(PROTECTED_CUBOID);
        Class<?> vector = Reflect.findClass(BLOCK_VECTOR_3);
        String id = WgRegionNames.regionName(world.getName(), chunkX, chunkZ);
        return Reflect.construct(cuboid, new Class<?>[] {String.class, vector, vector}, id, min, max);
    }

    /** {@code World.getMinHeight()} when the server exposes it (1.17+), else the legacy floor {@code 0}. */
    private static int minHeight(World world) {
        Object h = Reflect.call(world, "getMinHeight");
        return h instanceof Number number ? number.intValue() : 0;
    }

    /** {@code BlockVector3.at(x, y, z)} via the {@code int} overload, or {@code null}. */
    private static Object blockVector3At(int x, int y, int z) {
        Class<?> vector = Reflect.findClass(BLOCK_VECTOR_3);
        Method at = Reflect.method(vector, "at", int.class, int.class, int.class);
        return Reflect.invoke(null, at, x, y, z);
    }

    private void saveAsync(Object regionManager, World world) {
        scheduling.runAsync(() -> {
            try {
                Reflect.call(regionManager, "save");
            } catch (RuntimeException | LinkageError failed) {
                logger.warning("WG sync: async save failed for world " + world.getName());
            }
        });
    }
}
