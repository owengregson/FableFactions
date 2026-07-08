package dev.fablemc.factions.kernel.state;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;

/**
 * A frozen, open-addressed chunk→owner table for one 32×32-chunk region (AM-3).
 *
 * <p><b>Owning thread(s):</b> {@link #owner} on any reader thread; the {@code with*} rebuilders
 * on the writer only. <b>Mutability:</b> immutable, frozen open addressing at load ≤ 0.6.
 * <b>Reducer rule:</b> a claim mutation rebuilds exactly one region table
 * (copy-one-region) and the enclosing {@link ClaimAtlas} shares the other regions.
 *
 * <p>Slots store the owning faction <b>handle</b> (generation-tagged, AM-6) in the parallel
 * {@code owner[]}; a slot value of {@link FactionHandle#WILDERNESS} ({@code -1}) marks the slot
 * empty. This is unambiguous because unclaimed chunks are never stored (absence ⇒ wilderness),
 * so no claimed owner is ever {@code -1}. {@link #owner} allocates nothing — the load-bearing
 * atlas read (proposal-C §5c/N-3).
 */
public final class RegionTable {

    private static final double MAX_LOAD = 0.6;
    private static final RegionTable EMPTY = new RegionTable(new long[2], newFilled(2), 0);

    private final long[] keys;
    private final int[] owner; // owner[i] == WILDERNESS ⇒ empty slot
    private final int size;
    private final int mask;

    private RegionTable(long[] keys, int[] owner, int size) {
        this.keys = keys;
        this.owner = owner;
        this.size = size;
        this.mask = keys.length - 1;
    }

    /** The shared empty region table. */
    public static RegionTable empty() {
        return EMPTY;
    }

    /** Number of claimed chunks in this region. */
    public int size() {
        return size;
    }

    /**
     * Owning faction handle for {@code chunkKey}, or {@link FactionHandle#WILDERNESS} if the
     * chunk is unclaimed in this region. Zero allocation, ~1–2 probes.
     */
    public int owner(long chunkKey) {
        int i = (int) (ChunkKeys.mix(chunkKey) & mask);
        while (true) {
            int o = owner[i];
            if (o == FactionHandle.WILDERNESS) {
                return FactionHandle.WILDERNESS; // empty slot ⇒ not present
            }
            if (keys[i] == chunkKey) {
                return o;
            }
            i = (i + 1) & mask;
        }
    }

    /** Returns a rebuilt table with {@code chunkKey}→{@code ownerHandle} set (insert or replace). */
    public RegionTable withPut(long chunkKey, int ownerHandle) {
        if (ownerHandle == FactionHandle.WILDERNESS) {
            return withRemove(chunkKey);
        }
        int existing = owner(chunkKey);
        int newSize = existing == FactionHandle.WILDERNESS ? size + 1 : size;
        RegionTable t = allocFor(newSize);
        t.copyFrom(this, chunkKey); // copy all but chunkKey
        t.insertRaw(chunkKey, ownerHandle);
        return t;
    }

    /** Returns a rebuilt table with {@code chunkKey} removed (no-op if absent). */
    public RegionTable withRemove(long chunkKey) {
        if (owner(chunkKey) == FactionHandle.WILDERNESS) {
            return this;
        }
        int newSize = size - 1;
        if (newSize == 0) {
            return EMPTY;
        }
        RegionTable t = allocFor(newSize);
        t.copyFrom(this, chunkKey); // copy all but chunkKey
        return t;
    }

    /** Visits every live claim in this region. May allocate the lambda; not a hot path. */
    public void forEach(ClaimVisitor visitor) {
        for (int i = 0; i < owner.length; i++) {
            int o = owner[i];
            if (o != FactionHandle.WILDERNESS) {
                visitor.visit(keys[i], o);
            }
        }
    }

    /** Callback for {@link #forEach}: {@code chunkKey} and its owning faction handle. */
    @FunctionalInterface
    public interface ClaimVisitor {
        void visit(long chunkKey, int ownerHandle);
    }

    // ── internal frozen-table construction ───────────────────────────────────────────────

    /** Copies all live entries of {@code src} except {@code skipKey} into this (empty) table. */
    private void copyFrom(RegionTable src, long skipKey) {
        for (int i = 0; i < src.owner.length; i++) {
            int o = src.owner[i];
            if (o != FactionHandle.WILDERNESS && src.keys[i] != skipKey) {
                insertRaw(src.keys[i], o);
            }
        }
    }

    /** Linear-probe insert into a freshly sized (non-shared) table; mutates in place. */
    private void insertRaw(long key, int ownerHandle) {
        int i = (int) (ChunkKeys.mix(key) & mask);
        while (owner[i] != FactionHandle.WILDERNESS) {
            if (keys[i] == key) {
                owner[i] = ownerHandle;
                return;
            }
            i = (i + 1) & mask;
        }
        keys[i] = key;
        owner[i] = ownerHandle;
    }

    private static RegionTable allocFor(int liveEntries) {
        int cap = capacityFor(liveEntries);
        return new RegionTable(new long[cap], newFilled(cap), liveEntries);
    }

    private static int capacityFor(int liveEntries) {
        int min = (int) Math.ceil(liveEntries / MAX_LOAD) + 1;
        int cap = 2;
        while (cap < min) {
            cap <<= 1;
        }
        return cap;
    }

    private static int[] newFilled(int cap) {
        int[] a = new int[cap];
        java.util.Arrays.fill(a, FactionHandle.WILDERNESS);
        return a;
    }

    /**
     * Boot-time bulk builder (single-threaded, pre-publish — the only place a region table is
     * mutated before freezing). Accumulates entries, then freezes into a load-≤0.6 table.
     */
    public static final class Builder {
        private long[] keys = new long[16];
        private int[] owners = new int[16];
        private int count;

        /** Adds or overwrites a claim; last write wins for a repeated key. */
        public Builder put(long chunkKey, int ownerHandle) {
            for (int i = 0; i < count; i++) {
                if (keys[i] == chunkKey) {
                    owners[i] = ownerHandle;
                    return this;
                }
            }
            if (count == keys.length) {
                keys = java.util.Arrays.copyOf(keys, count << 1);
                owners = java.util.Arrays.copyOf(owners, count << 1);
            }
            keys[count] = chunkKey;
            owners[count] = ownerHandle;
            count++;
            return this;
        }

        /** Number of accumulated entries. */
        public int size() {
            return count;
        }

        /** Freezes into an immutable {@link RegionTable}. */
        public RegionTable build() {
            if (count == 0) {
                return EMPTY;
            }
            RegionTable t = allocFor(count);
            for (int i = 0; i < count; i++) {
                t.insertRaw(keys[i], owners[i]);
            }
            return t;
        }
    }
}
