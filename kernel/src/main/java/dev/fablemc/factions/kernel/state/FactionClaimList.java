package dev.fablemc.factions.kernel.state;

import java.util.Arrays;

/**
 * Reverse claim index: a faction's own chunk keys, per world, as sorted {@code long[]} runs
 * (AM-4).
 *
 * <p><b>Owning thread(s):</b> queries on any reader thread; mutators on the writer only.
 * <b>Mutability:</b> immutable, COW. <b>Reducer rule:</b> maintained by the reducer alongside
 * the {@link ClaimAtlas}, so disband / unclaim-all / merge are O(faction land) instead of a
 * hash-scatter over the whole atlas.
 *
 * <p>Because faction land is spatially contiguous, the sorted keys compress into a handful of
 * dense runs; sorting also makes containment and merge a binary search.
 */
public final class FactionClaimList {

    private static final FactionClaimList EMPTY =
            new FactionClaimList(new int[0], new long[0][], 0);

    private final int[] worldIdx;      // sorted ascending, parallel to keysByWorld
    private final long[][] keysByWorld; // each inner array sorted ascending
    private final int total;

    private FactionClaimList(int[] worldIdx, long[][] keysByWorld, int total) {
        this.worldIdx = worldIdx;
        this.keysByWorld = keysByWorld;
        this.total = total;
    }

    /** The shared empty list. */
    public static FactionClaimList empty() {
        return EMPTY;
    }

    /** Total number of chunks this faction holds across all worlds. */
    public int count() {
        return total;
    }

    /** {@code true} when the faction holds no land. */
    public boolean isEmpty() {
        return total == 0;
    }

    /** {@code true} when this faction owns {@code chunkKey} in {@code world}. */
    public boolean contains(int world, long chunkKey) {
        int wi = worldSlot(world);
        if (wi < 0) {
            return false;
        }
        return keyIndex(keysByWorld[wi], chunkKey) >= 0;
    }

    /** Sorted chunk keys held in {@code world} (empty array if none). Do not mutate. */
    public long[] keys(int world) {
        int wi = worldSlot(world);
        return wi < 0 ? EMPTY_KEYS : keysByWorld[wi];
    }

    /** Returns a copy with {@code chunkKey} added to {@code world} (no-op if already present). */
    public FactionClaimList add(int world, long chunkKey) {
        int wi = worldSlot(world);
        if (wi < 0) {
            // New world entry, inserted keeping worldIdx sorted.
            int ins = worldInsertionPoint(world);
            int[] nw = new int[worldIdx.length + 1];
            long[][] nk = new long[keysByWorld.length + 1][];
            System.arraycopy(worldIdx, 0, nw, 0, ins);
            System.arraycopy(keysByWorld, 0, nk, 0, ins);
            nw[ins] = world;
            nk[ins] = new long[] {chunkKey};
            System.arraycopy(worldIdx, ins, nw, ins + 1, worldIdx.length - ins);
            System.arraycopy(keysByWorld, ins, nk, ins + 1, keysByWorld.length - ins);
            return new FactionClaimList(nw, nk, total + 1);
        }
        long[] cur = keysByWorld[wi];
        int idx = keyIndex(cur, chunkKey);
        if (idx >= 0) {
            return this; // already present
        }
        int ip = keyInsertionPoint(cur, chunkKey);
        long[] merged = new long[cur.length + 1];
        System.arraycopy(cur, 0, merged, 0, ip);
        merged[ip] = chunkKey;
        System.arraycopy(cur, ip, merged, ip + 1, cur.length - ip);
        long[][] nk = keysByWorld.clone();
        nk[wi] = merged;
        return new FactionClaimList(worldIdx.clone(), nk, total + 1);
    }

    /** Returns a copy with {@code chunkKey} removed from {@code world} (no-op if absent). */
    public FactionClaimList remove(int world, long chunkKey) {
        int wi = worldSlot(world);
        if (wi < 0) {
            return this;
        }
        long[] cur = keysByWorld[wi];
        int idx = keyIndex(cur, chunkKey);
        if (idx < 0) {
            return this;
        }
        if (cur.length == 1) {
            // Drop the whole world entry.
            int[] nw = new int[worldIdx.length - 1];
            long[][] nk = new long[keysByWorld.length - 1][];
            System.arraycopy(worldIdx, 0, nw, 0, wi);
            System.arraycopy(keysByWorld, 0, nk, 0, wi);
            System.arraycopy(worldIdx, wi + 1, nw, wi, worldIdx.length - wi - 1);
            System.arraycopy(keysByWorld, wi + 1, nk, wi, keysByWorld.length - wi - 1);
            return new FactionClaimList(nw, nk, total - 1);
        }
        long[] shrunk = new long[cur.length - 1];
        System.arraycopy(cur, 0, shrunk, 0, idx);
        System.arraycopy(cur, idx + 1, shrunk, idx, cur.length - idx - 1);
        long[][] nk = keysByWorld.clone();
        nk[wi] = shrunk;
        return new FactionClaimList(worldIdx.clone(), nk, total - 1);
    }

    private int worldSlot(int world) {
        for (int i = 0; i < worldIdx.length; i++) {
            if (worldIdx[i] == world) {
                return i;
            }
        }
        return -1;
    }

    private int worldInsertionPoint(int world) {
        int i = 0;
        while (i < worldIdx.length && worldIdx[i] < world) {
            i++;
        }
        return i;
    }

    private static int keyIndex(long[] keys, long key) {
        int i = Arrays.binarySearch(keys, key);
        return i;
    }

    private static int keyInsertionPoint(long[] keys, long key) {
        int i = Arrays.binarySearch(keys, key);
        return i >= 0 ? i : -(i + 1);
    }

    private static final long[] EMPTY_KEYS = new long[0];
}
