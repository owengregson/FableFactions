package dev.fablemc.factions.core.integration.dynmap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.World;

import dev.fablemc.factions.core.integration.Reflect;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * The dynmap territory layer (ref-integrations §5), driven reflectively so {@code :core} never
 * imports {@code org.dynmap}. Claim/unclaim/disband/rename effects enqueue marker ops on the writer
 * thread; a 2-second {@link #flush()} tick (scheduled global by {@link
 * dev.fablemc.factions.core.integration.IntegrationsBootstrap}) drains and applies them on the main
 * region, coalescing bursts into one marker-API pass (proposal-C §10.3 debounce).
 *
 * <p><b>Owning thread(s):</b> {@code enqueue*} on the writer thread (pure data only); {@link #start},
 * {@link #flush}, {@link #stop} on the global/main thread (the marker API is main-bound). <b>Mutability:</b>
 * the pending-op queue is a lock-free MPSC handoff; the marker-set handle is a volatile publish.
 */
public final class DynmapLayer {

    /** The kind of a queued marker mutation. */
    private enum Kind { CLAIM, UNCLAIM, DISBAND, RENAME }

    /** A queued marker mutation carrying only pure data resolved on the writer thread. */
    private record Op(Kind kind, String factionSeg, String factionName, UUID worldUid, int cx, int cz) {
    }

    private final SnapshotHub snapshots;
    private final Worlds worlds;
    private final Logger logger;

    private final Queue<Op> pending = new ConcurrentLinkedQueue<>();
    private volatile Object markerApi;
    private volatile Object markerSet;
    private volatile boolean needsFullLoad;

    /** Constructor injection: the snapshot source (full load), the world registry, and the logger. */
    public DynmapLayer(SnapshotHub snapshots, Worlds worlds, Logger logger) {
        this.snapshots = snapshots;
        this.worlds = worlds;
        this.logger = logger;
    }

    // ── writer-thread enqueue surface (pure data) ────────────────────────────────────────────

    /** Enqueues a claim marker for a chunk now owned by {@code factionSeg}. */
    public void enqueueClaim(String factionSeg, String factionName, UUID worldUid, int cx, int cz) {
        pending.offer(new Op(Kind.CLAIM, factionSeg, factionName, worldUid, cx, cz));
    }

    /** Enqueues removal of the marker at a now-wilderness chunk (faction id not needed). */
    public void enqueueUnclaim(UUID worldUid, int cx, int cz) {
        pending.offer(new Op(Kind.UNCLAIM, "", "", worldUid, cx, cz));
    }

    /** Enqueues removal of every marker for a disbanded faction. */
    public void enqueueDisband(String factionSeg) {
        pending.offer(new Op(Kind.DISBAND, factionSeg, "", null, 0, 0));
    }

    /** Enqueues a re-label of every marker for a renamed faction. */
    public void enqueueRename(String factionSeg, String newName) {
        pending.offer(new Op(Kind.RENAME, factionSeg, newName, null, 0, 0));
    }

    // ── main-thread lifecycle + flush ────────────────────────────────────────────────────────

    /** Resolves the marker API, replaces any stale marker set, and arms the +1-flush full load. */
    public boolean start() {
        Object dynmap = Reflect.plugin("dynmap");
        if (dynmap == null) {
            return false;
        }
        Object api = Reflect.call(dynmap, "getMarkerAPI");
        if (api == null) {
            logger.warning("dynmap MarkerAPI not ready — faction territory layer skipped.");
            return false;
        }
        this.markerApi = api;
        Object stale = Reflect.call1(api, "getMarkerSet", DynmapPalette.LAYER_ID);
        if (stale != null) {
            Reflect.call(stale, "deleteMarkerSet");
        }
        Object set = Reflect.call(api, "createMarkerSet",
                new Class<?>[] {String.class, String.class, java.util.Set.class, boolean.class},
                DynmapPalette.LAYER_ID, DynmapPalette.LAYER_LABEL, null, false);
        if (set == null) {
            logger.warning("Failed to create dynmap faction marker set.");
            return false;
        }
        Reflect.call(set, "setHideByDefault", Reflect.sig(boolean.class), false);
        Reflect.call(set, "setLayerPriority", Reflect.sig(int.class), DynmapPalette.LAYER_PRIORITY);
        this.markerSet = set;
        this.needsFullLoad = true;
        return true;
    }

    /** Drains queued marker ops (running the deferred full load first) on the main region. */
    public void flush() {
        Object set = markerSet;
        if (set == null) {
            return;
        }
        try {
            if (needsFullLoad) {
                needsFullLoad = false;
                loadAll(set);
            }
            Op op;
            while ((op = pending.poll()) != null) {
                apply(set, op);
            }
        } catch (RuntimeException | LinkageError failed) {
            logger.log(Level.WARNING, "dynmap flush failed", failed);
        }
    }

    /** Deletes the marker set at shutdown so a reload starts clean. */
    public void stop() {
        Object set = markerSet;
        markerSet = null;
        if (set != null) {
            Reflect.call(set, "deleteMarkerSet");
        }
    }

    private void apply(Object set, Op op) {
        switch (op.kind()) {
            case CLAIM -> {
                String world = worldName(op.worldUid());
                if (world != null) {
                    addChunkMarker(set, op.factionSeg(), op.factionName(), world, op.cx(), op.cz());
                }
            }
            case UNCLAIM -> {
                String world = worldName(op.worldUid());
                if (world != null) {
                    removeBySuffix(set, "" + DynmapPalette.SEPARATOR + world
                            + DynmapPalette.SEPARATOR + op.cx() + DynmapPalette.SEPARATOR + op.cz());
                }
            }
            case DISBAND -> forEachWithPrefix(set, op.factionSeg() + DynmapPalette.SEPARATOR,
                    marker -> Reflect.call(marker, "deleteMarker"));
            case RENAME -> forEachWithPrefix(set, op.factionSeg() + DynmapPalette.SEPARATOR,
                    marker -> Reflect.call(marker, "setLabel", Reflect.sig(String.class), op.factionName()));
        }
    }

    private void addChunkMarker(Object set, String seg, String name, String world, int cx, int cz) {
        String id = DynmapPalette.markerId(seg, world, cx, cz);
        deleteById(set, id);
        double x0 = cx * 16.0;
        double z0 = cz * 16.0;
        double[] xs = {x0, x0, x0 + 16, x0 + 16};
        double[] zs = {z0, z0 + 16, z0 + 16, z0};
        Object marker = Reflect.call(set, "createAreaMarker",
                new Class<?>[] {String.class, String.class, boolean.class, String.class,
                        double[].class, double[].class, boolean.class},
                id, name, false, world, xs, zs, false);
        if (marker == null) {
            return;
        }
        int color = DynmapPalette.colorFor(seg);
        Reflect.call(marker, "setFillStyle", Reflect.sig(double.class, int.class),
                DynmapPalette.FILL_OPACITY, color);
        Reflect.call(marker, "setLineStyle",
                new Class<?>[] {int.class, double.class, int.class},
                DynmapPalette.LINE_WEIGHT, DynmapPalette.LINE_OPACITY, color);
    }

    private void loadAll(Object set) {
        forEachMarker(set, marker -> Reflect.call(marker, "deleteMarker"));
        KernelSnapshot snapshot = snapshots.current();
        FactionArena arena = snapshot.state().factions();
        for (World world : Bukkit.getWorlds()) {
            int idx = worlds.indexOf(world.getUID());
            if (idx < 0) {
                continue;
            }
            String name = world.getName();
            int hw = arena.highWater();
            for (int ord = 0; ord < hw; ord++) {
                Faction faction = arena.at(ord);
                if (faction == null) {
                    continue;
                }
                long[] keys = faction.claims().keys(idx);
                String seg = Integer.toString(faction.idx());
                for (long key : keys) {
                    addChunkMarker(set, seg, faction.name(), name, ChunkKeys.x(key), ChunkKeys.z(key));
                }
            }
        }
    }

    private String worldName(UUID uid) {
        if (uid == null) {
            return null;
        }
        World world = Bukkit.getWorld(uid);
        return world != null ? world.getName() : null;
    }

    private void deleteById(Object set, String id) {
        forEachMarker(set, marker -> {
            if (id.equals(Reflect.call(marker, "getMarkerID"))) {
                Reflect.call(marker, "deleteMarker");
            }
        });
    }

    private void removeBySuffix(Object set, String suffix) {
        forEachMarker(set, marker -> {
            Object markerId = Reflect.call(marker, "getMarkerID");
            if (markerId instanceof String s && s.endsWith(suffix)) {
                Reflect.call(marker, "deleteMarker");
            }
        });
    }

    private void forEachWithPrefix(Object set, String prefix, Consumer<Object> action) {
        forEachMarker(set, marker -> {
            Object markerId = Reflect.call(marker, "getMarkerID");
            if (markerId instanceof String s && s.startsWith(prefix)) {
                action.accept(marker);
            }
        });
    }

    private void forEachMarker(Object set, Consumer<Object> action) {
        Object markers = Reflect.call(set, "getAreaMarkers");
        if (!(markers instanceof Collection<?> collection)) {
            return;
        }
        List<Object> snapshot = new ArrayList<>(collection);   // copy: deletes mutate the live set
        for (Object marker : snapshot) {
            action.accept(marker);
        }
    }
}
