package dev.fablemc.factions.core.listen;

import dev.fablemc.factions.kernel.rules.Verdict;

/**
 * An 8-slot direct-mapped chunk→verdict memo for walking an explosion's block-list (proposal-C
 * §8.2, work order: "explosion block-lists walked with the 8-slot direct-mapped chunk-verdict
 * memo"). A single explosion touches many blocks across only a handful of chunks, so caching the
 * per-chunk verdict collapses hundreds of {@code Verdicts.decide} calls to a few.
 *
 * <p><b>Owning thread(s):</b> confined to one explosion handler invocation on the event's
 * region/main thread — one memo is allocated per explosion (a deliberate, justified region-thread
 * allocation: explosions are region-rate, not per-tick, and the alternative — a shared field —
 * would not be region-safe on Folia). <b>Mutability:</b> confined mutable scratch.
 *
 * <p>Direct-mapped by the low three bits of the chunk key; a collision simply overwrites the slot
 * (no probing), which is correct because every entry is an independent pure function of its key.
 * Chunk keys have no reserved "absent" sentinel (any {@code long} is a valid key,
 * {@link dev.fablemc.factions.kernel.ids.ChunkKeys}), so occupancy is tracked out of band in a
 * parallel flag array.
 */
final class ChunkVerdictMemo {

    private static final int SLOTS = 8;
    private static final int SLOT_MASK = SLOTS - 1;

    private final long[] keys = new long[SLOTS];
    private final int[] verdicts = new int[SLOTS];
    private final boolean[] occupied = new boolean[SLOTS];

    /**
     * The cached verdict for {@code chunkKey}, or {@link Verdict#DENY_INTERNAL} as the "miss"
     * sentinel telling the caller to decide and {@link #store}. {@code DENY_INTERNAL} is never a
     * real explosion verdict, so it is unambiguous as a miss marker.
     */
    int lookup(long chunkKey) {
        int slot = (int) (chunkKey & SLOT_MASK);
        if (occupied[slot] && keys[slot] == chunkKey) {
            return verdicts[slot];
        }
        return Verdict.DENY_INTERNAL;
    }

    /** Records {@code verdict} for {@code chunkKey} in its direct-mapped slot. */
    void store(long chunkKey, int verdict) {
        int slot = (int) (chunkKey & SLOT_MASK);
        keys[slot] = chunkKey;
        verdicts[slot] = verdict;
        occupied[slot] = true;
    }
}
