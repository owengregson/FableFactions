package dev.fablemc.factions.kernel.state;

import java.util.Arrays;
import java.util.HashMap;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;

/**
 * Two-level spatial claim index (AM-3): world → region map → {@link RegionTable}.
 *
 * <p><b>Owning thread(s):</b> {@link #ownerAt} on any reader thread; {@code with*} mutators on
 * the writer only. <b>Mutability:</b> immutable, COW. <b>Reducer rule:</b> only the reducer
 * mutates claims, and a single-chunk change copies exactly one {@link RegionTable} plus a small
 * pointer-array copy (copy-one-region); every other region is shared with the prior atlas, so
 * an old published snapshot is unaffected.
 *
 * <p>The top level is a small array indexed by dense {@code worldIdx}. Each world holds a COW
 * open-addressed {@code regionKey → RegionTable} map ({@link RegionMap}). Because faction land
 * is spatially contiguous, {@code /f claim square}, fill, disband and merge touch 1–4 region
 * shards instead of hash-scattering. {@link #ownerAt} is ~2 probes, zero allocation.
 */
public final class ClaimAtlas {

    private static final ClaimAtlas EMPTY = new ClaimAtlas(new RegionMap[0], 0);

    private final RegionMap[] byWorld; // byWorld[worldIdx] may be null
    private final int totalClaims;

    private ClaimAtlas(RegionMap[] byWorld, int totalClaims) {
        this.byWorld = byWorld;
        this.totalClaims = totalClaims;
    }

    /** The shared empty atlas. */
    public static ClaimAtlas empty() {
        return EMPTY;
    }

    /** Total claimed chunks across all worlds. */
    public int size() {
        return totalClaims;
    }

    /**
     * Owning faction handle for {@code (worldIdx, chunkKey)}, or {@link FactionHandle#WILDERNESS}
     * if unclaimed / the world is unknown. Zero allocation — the protection hot path.
     */
    public int ownerAt(int worldIdx, long chunkKey) {
        if (worldIdx < 0 || worldIdx >= byWorld.length) {
            return FactionHandle.WILDERNESS;
        }
        RegionMap rm = byWorld[worldIdx];
        if (rm == null) {
            return FactionHandle.WILDERNESS;
        }
        RegionTable rt = rm.get(ChunkKeys.regionKey(chunkKey));
        if (rt == null) {
            return FactionHandle.WILDERNESS;
        }
        return rt.owner(chunkKey);
    }

    /** Returns a new atlas with {@code (worldIdx, chunkKey)} owned by {@code ownerHandle}. */
    public ClaimAtlas withClaim(int worldIdx, long chunkKey, int ownerHandle) {
        if (worldIdx < 0) {
            throw new IllegalArgumentException("worldIdx must be >= 0: " + worldIdx);
        }
        RegionMap rm = (worldIdx < byWorld.length) ? byWorld[worldIdx] : null;
        if (rm == null) {
            rm = RegionMap.EMPTY;
        }
        long rk = ChunkKeys.regionKey(chunkKey);
        RegionTable rt = rm.get(rk);
        if (rt == null) {
            rt = RegionTable.empty();
        }
        int before = rt.size();
        RegionTable rt2 = rt.withPut(chunkKey, ownerHandle);
        int delta = rt2.size() - before; // 0 (replace) or 1 (insert)
        RegionMap rm2 = rm.withRegion(rk, rt2);
        RegionMap[] nbw = ensureWorld(byWorld, worldIdx);
        nbw[worldIdx] = rm2;
        return new ClaimAtlas(nbw, totalClaims + delta);
    }

    /** Returns a new atlas with {@code (worldIdx, chunkKey)} unclaimed (no-op if absent). */
    public ClaimAtlas withoutClaim(int worldIdx, long chunkKey) {
        if (worldIdx < 0 || worldIdx >= byWorld.length) {
            return this;
        }
        RegionMap rm = byWorld[worldIdx];
        if (rm == null) {
            return this;
        }
        long rk = ChunkKeys.regionKey(chunkKey);
        RegionTable rt = rm.get(rk);
        if (rt == null) {
            return this;
        }
        int before = rt.size();
        RegionTable rt2 = rt.withRemove(chunkKey);
        if (rt2.size() == before) {
            return this; // chunk was not claimed
        }
        RegionMap rm2 = (rt2.size() == 0) ? rm.withoutRegion(rk) : rm.withRegion(rk, rt2);
        RegionMap[] nbw = Arrays.copyOf(byWorld, byWorld.length);
        nbw[worldIdx] = rm2.isEmpty() ? null : rm2;
        return new ClaimAtlas(nbw, totalClaims - (before - rt2.size()));
    }

    /** Visits every claim as {@code (worldIdx, chunkKey, ownerHandle)}. Allocates; not hot. */
    public void forEachClaim(AtlasVisitor visitor) {
        for (int w = 0; w < byWorld.length; w++) {
            RegionMap rm = byWorld[w];
            if (rm == null) {
                continue;
            }
            final int world = w;
            rm.forEach((chunkKey, owner) -> visitor.visit(world, chunkKey, owner));
        }
    }

    /** Callback for {@link #forEachClaim}. */
    @FunctionalInterface
    public interface AtlasVisitor {
        void visit(int worldIdx, long chunkKey, int ownerHandle);
    }

    private static RegionMap[] ensureWorld(RegionMap[] a, int worldIdx) {
        if (worldIdx < a.length) {
            return Arrays.copyOf(a, a.length);
        }
        int cap = Math.max(a.length, 1);
        while (cap <= worldIdx) {
            cap <<= 1;
        }
        return Arrays.copyOf(a, cap);
    }

    // ── RegionMap: COW open-addressed regionKey → RegionTable ─────────────────────────────

    /**
     * A world's region map: open-addressed {@code long regionKey → RegionTable} at load ≤ 0.6,
     * with a {@code null}-slot empty marker (every {@code long} is a valid region key, so no key
     * sentinel is available). {@link #get} is zero-allocation; structural changes rebuild.
     */
    static final class RegionMap {
        private static final double MAX_LOAD = 0.6;
        static final RegionMap EMPTY = new RegionMap(new long[2], new RegionTable[2], 0);

        private final long[] regionKeys;
        private final RegionTable[] tables; // tables[i] == null ⇒ empty slot
        private final int size;
        private final int mask;

        private RegionMap(long[] regionKeys, RegionTable[] tables, int size) {
            this.regionKeys = regionKeys;
            this.tables = tables;
            this.size = size;
            this.mask = regionKeys.length - 1;
        }

        boolean isEmpty() {
            return size == 0;
        }

        RegionTable get(long regionKey) {
            int i = (int) (ChunkKeys.mix(regionKey) & mask);
            while (true) {
                RegionTable t = tables[i];
                if (t == null) {
                    return null;
                }
                if (regionKeys[i] == regionKey) {
                    return t;
                }
                i = (i + 1) & mask;
            }
        }

        RegionMap withRegion(long regionKey, RegionTable table) {
            int idx = slotOf(regionKey);
            if (idx >= 0) {
                // Replace the pointer for an existing region — copy the two arrays only.
                long[] nk = Arrays.copyOf(regionKeys, regionKeys.length);
                RegionTable[] nt = Arrays.copyOf(tables, tables.length);
                nt[idx] = table;
                return new RegionMap(nk, nt, size);
            }
            // New region: grow if the load factor would exceed 0.6.
            int newSize = size + 1;
            if (newSize > (int) (regionKeys.length * MAX_LOAD)) {
                return rehashWith(regionKeys.length << 1, regionKey, table);
            }
            long[] nk = Arrays.copyOf(regionKeys, regionKeys.length);
            RegionTable[] nt = Arrays.copyOf(tables, tables.length);
            insertRaw(nk, nt, regionKey, table);
            return new RegionMap(nk, nt, newSize);
        }

        RegionMap withoutRegion(long regionKey) {
            int idx = slotOf(regionKey);
            if (idx < 0) {
                return this;
            }
            if (size == 1) {
                return EMPTY;
            }
            // Rebuild from remaining entries to avoid open-addressing tombstones.
            int cap = capacityFor(size - 1);
            long[] nk = new long[cap];
            RegionTable[] nt = new RegionTable[cap];
            for (int i = 0; i < tables.length; i++) {
                RegionTable t = tables[i];
                if (t != null && regionKeys[i] != regionKey) {
                    insertRaw(nk, nt, regionKeys[i], t);
                }
            }
            return new RegionMap(nk, nt, size - 1);
        }

        void forEach(RegionTable.ClaimVisitor visitor) {
            for (int i = 0; i < tables.length; i++) {
                RegionTable t = tables[i];
                if (t != null) {
                    t.forEach(visitor);
                }
            }
        }

        private int slotOf(long regionKey) {
            int i = (int) (ChunkKeys.mix(regionKey) & mask);
            while (true) {
                RegionTable t = tables[i];
                if (t == null) {
                    return -1;
                }
                if (regionKeys[i] == regionKey) {
                    return i;
                }
                i = (i + 1) & mask;
            }
        }

        private RegionMap rehashWith(int newCap, long addKey, RegionTable addTable) {
            long[] nk = new long[newCap];
            RegionTable[] nt = new RegionTable[newCap];
            for (int i = 0; i < tables.length; i++) {
                if (tables[i] != null) {
                    insertRaw(nk, nt, regionKeys[i], tables[i]);
                }
            }
            insertRaw(nk, nt, addKey, addTable);
            return new RegionMap(nk, nt, size + 1);
        }

        private static void insertRaw(long[] keys, RegionTable[] tables, long key, RegionTable t) {
            int mask = keys.length - 1;
            int i = (int) (ChunkKeys.mix(key) & mask);
            while (tables[i] != null) {
                if (keys[i] == key) {
                    tables[i] = t;
                    return;
                }
                i = (i + 1) & mask;
            }
            keys[i] = key;
            tables[i] = t;
        }

        private static int capacityFor(int liveEntries) {
            int min = (int) Math.ceil(liveEntries / MAX_LOAD) + 1;
            int cap = 2;
            while (cap < min) {
                cap <<= 1;
            }
            return cap;
        }
    }

    // ── Boot-time bulk builder ────────────────────────────────────────────────────────────

    /**
     * Single-threaded, pre-publish bulk fill (proposal-C §6.3). Accumulates every claim, then
     * freezes each region into a load-≤0.6 {@link RegionTable} in one pass.
     */
    public static final class Builder {
        private final HashMap<Integer, HashMap<Long, RegionTable.Builder>> byWorld = new HashMap<>();
        private int total;

        /** Records a claim; last write wins for a repeated chunk key. */
        public Builder put(int worldIdx, long chunkKey, int ownerHandle) {
            if (worldIdx < 0) {
                throw new IllegalArgumentException("worldIdx must be >= 0: " + worldIdx);
            }
            HashMap<Long, RegionTable.Builder> regions =
                    byWorld.computeIfAbsent(worldIdx, k -> new HashMap<>());
            long rk = ChunkKeys.regionKey(chunkKey);
            RegionTable.Builder rb = regions.computeIfAbsent(rk, k -> new RegionTable.Builder());
            int before = rb.size();
            rb.put(chunkKey, ownerHandle);
            total += rb.size() - before;
            return this;
        }

        /** Freezes into an immutable {@link ClaimAtlas}. */
        public ClaimAtlas build() {
            int maxWorld = -1;
            for (Integer w : byWorld.keySet()) {
                if (w > maxWorld) {
                    maxWorld = w;
                }
            }
            if (maxWorld < 0) {
                return EMPTY;
            }
            RegionMap[] worlds = new RegionMap[maxWorld + 1];
            for (java.util.Map.Entry<Integer, HashMap<Long, RegionTable.Builder>> e
                    : byWorld.entrySet()) {
                HashMap<Long, RegionTable.Builder> regions = e.getValue();
                RegionMap rm = RegionMap.EMPTY;
                for (java.util.Map.Entry<Long, RegionTable.Builder> re : regions.entrySet()) {
                    RegionTable t = re.getValue().build();
                    if (t.size() > 0) {
                        rm = rm.withRegion(re.getKey(), t);
                    }
                }
                worlds[e.getKey()] = rm.isEmpty() ? null : rm;
            }
            return new ClaimAtlas(worlds, total);
        }
    }
}
