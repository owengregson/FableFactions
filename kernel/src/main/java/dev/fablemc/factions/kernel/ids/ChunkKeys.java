package dev.fablemc.factions.kernel.ids;

/**
 * Static packing helpers for chunk and region keys.
 *
 * <p><b>Owning thread(s):</b> none — pure static functions, callable from any thread.
 * <b>Mutability:</b> stateless. <b>Reducer rule:</b> n/a (no state).
 *
 * <p>A chunk key packs the signed chunk coordinates into one {@code long}:
 * {@code ((long)x << 32) | (z & 0xFFFFFFFFL)} (AM-3). This is computed in-house and never
 * derived from Paper's {@code getChunkKey} (version-deltas §3.9). Every {@code long} value is
 * therefore a valid chunk key, so no {@code long} sentinel may be reserved for "absent" in
 * tables keyed by chunk key — occupancy is tracked out-of-band.
 *
 * <p>A region key groups chunks into 32×32 blocks by arithmetic right-shifting each coordinate
 * by 5 (AM-3 two-level spatial sharding), then packs identically.
 */
public final class ChunkKeys {

    private ChunkKeys() {
    }

    /** Packs signed chunk coordinates {@code (x, z)} into one key. */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Extracts the signed chunk X from a chunk key. */
    public static int x(long key) {
        return (int) (key >> 32);
    }

    /** Extracts the signed chunk Z from a chunk key. */
    public static int z(long key) {
        return (int) key;
    }

    /** Region key of a chunk key: {@code (x>>5, z>>5)} packed the same way. */
    public static long regionKey(long chunkKey) {
        return key(x(chunkKey) >> 5, z(chunkKey) >> 5);
    }

    /** Chunk key of the chunk containing a block position: {@code (blockX>>4, blockZ>>4)}. */
    public static long fromBlock(int blockX, int blockZ) {
        return key(blockX >> 4, blockZ >> 4);
    }

    /**
     * A finalizing mix (SplittableRandom / Murmur3 style) used to spread chunk and region keys
     * across open-addressed tables. Distinct from any storage identity — purely for hashing.
     */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
