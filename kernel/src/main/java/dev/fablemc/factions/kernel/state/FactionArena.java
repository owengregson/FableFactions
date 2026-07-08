package dev.fablemc.factions.kernel.state;

import java.util.Arrays;

import dev.fablemc.factions.kernel.ids.FactionHandle;

/**
 * Dense faction store indexed by ordinal, with a free-list and per-slot generation bump (AM-6).
 *
 * <p><b>Owning thread(s):</b> {@link #resolve}/{@link #at} on any reader thread; the
 * {@code with*}/{@code freed} mutators on the writer only. <b>Mutability:</b> immutable, COW —
 * a mutation copies the {@code slots}/{@code generation} arrays and returns a new arena.
 * <b>Reducer rule:</b> only the reducer allocates, replaces, or frees factions.
 *
 * <p>Freeing a slot bumps its generation, so a handle minted against the old generation fails
 * {@link #resolve} (generation mismatch) and can never reach a reincarnated faction. Ordinals
 * 0/1 are reserved for the SAFEZONE/WARZONE sentinels; the free-list hands out reclaimed
 * ordinals before extending the high-water mark, and always skips {@code 0}/{@code 1}.
 */
public final class FactionArena {

    private final Faction[] slots;      // slots[ord] == null ⇒ free
    private final int[] generation;     // generation[ord], bumped on free
    private final int[] freeList;       // stack of reclaimed ordinals (>= FIRST_NORMAL_ORDINAL)
    private final int freeCount;
    private final int highWater;        // next never-used ordinal
    private final int liveCount;

    private FactionArena(Faction[] slots, int[] generation, int[] freeList, int freeCount,
                         int highWater, int liveCount) {
        this.slots = slots;
        this.generation = generation;
        this.freeList = freeList;
        this.freeCount = freeCount;
        this.highWater = highWater;
        this.liveCount = liveCount;
    }

    /** An empty arena with ordinals 0/1 reserved (high-water starts at the first normal ordinal). */
    public static FactionArena empty() {
        int cap = 4;
        return new FactionArena(new Faction[cap], new int[cap], new int[cap], 0,
                FactionHandle.FIRST_NORMAL_ORDINAL, 0);
    }

    /** Number of live factions (including any SAFEZONE/WARZONE sentinels that have been placed). */
    public int liveCount() {
        return liveCount;
    }

    /** One past the highest ordinal ever handed out. */
    public int highWater() {
        return highWater;
    }

    /** Faction at {@code ordinal} with no generation check, or {@code null} if the slot is free. */
    public Faction at(int ordinal) {
        if (ordinal < 0 || ordinal >= slots.length) {
            return null;
        }
        return slots[ordinal];
    }

    /** Current generation stored for {@code ordinal} (0 for never-used slots). */
    public int generationAt(int ordinal) {
        if (ordinal < 0 || ordinal >= generation.length) {
            return 0;
        }
        return generation[ordinal];
    }

    /** The live handle for {@code ordinal} (generation-tagged), regardless of occupancy. */
    public int handleOf(int ordinal) {
        return FactionHandle.handle(generationAt(ordinal), ordinal);
    }

    /**
     * Resolves a generation-tagged handle. Returns {@code null} on a free slot, an
     * out-of-range ordinal, or a generation mismatch (a stale handle to a reincarnated slot).
     */
    public Faction resolve(int handle) {
        if (handle == FactionHandle.WILDERNESS) {
            return null;
        }
        int ord = FactionHandle.ordinal(handle);
        if (ord < 0 || ord >= slots.length) {
            return null;
        }
        Faction f = slots[ord];
        if (f == null) {
            return null;
        }
        if (generation[ord] != FactionHandle.generation(handle)) {
            return null;
        }
        return f;
    }

    /**
     * The ordinal the next {@link #withFaction} allocation will use (free-list top, else the
     * high-water mark). Does not mutate.
     */
    public int nextFreeOrdinal() {
        if (freeCount > 0) {
            return freeList[freeCount - 1];
        }
        return highWater;
    }

    /**
     * Places {@code f} at {@code ordinal}, consuming a free-list entry or extending the
     * high-water mark. {@code ordinal} must be {@link #nextFreeOrdinal()} or a reserved sentinel
     * ordinal (0/1). The slot's generation is unchanged, so
     * {@code FactionHandle.handle(generationAt(ordinal), ordinal)} is the new faction's handle.
     */
    public FactionArena withFaction(int ordinal, Faction f) {
        if (f == null) {
            throw new IllegalArgumentException("faction must not be null");
        }
        if (ordinal != f.idx()) {
            throw new IllegalArgumentException(
                    "faction.idx (" + f.idx() + ") must equal ordinal (" + ordinal + ")");
        }
        int need = ordinal + 1;
        Faction[] ns = ensureCapacity(slots, need);
        int[] ng = ensureCapacity(generation, need);
        boolean wasOccupied = ordinal < slots.length && slots[ordinal] != null;
        ns[ordinal] = f;

        int newFreeCount = freeCount;
        int[] newFree = freeList;
        int newHigh = highWater;
        if (freeCount > 0 && freeList[freeCount - 1] == ordinal) {
            newFree = Arrays.copyOf(freeList, freeList.length);
            newFreeCount = freeCount - 1;
        } else if (ordinal >= highWater) {
            newHigh = ordinal + 1;
        }
        int newLive = wasOccupied ? liveCount : liveCount + 1;
        return new FactionArena(ns, ng, newFree, newFreeCount, newHigh, newLive);
    }

    /**
     * Replaces the faction already present at {@code ordinal} (same generation) — the common
     * whole-record swap for a faction-scoped change. The slot must be occupied.
     */
    public FactionArena replace(int ordinal, Faction f) {
        if (f == null) {
            throw new IllegalArgumentException("faction must not be null");
        }
        if (ordinal < 0 || ordinal >= slots.length || slots[ordinal] == null) {
            throw new IllegalStateException("no live faction at ordinal " + ordinal);
        }
        if (ordinal != f.idx()) {
            throw new IllegalArgumentException(
                    "faction.idx (" + f.idx() + ") must equal ordinal (" + ordinal + ")");
        }
        Faction[] ns = Arrays.copyOf(slots, slots.length);
        ns[ordinal] = f;
        return new FactionArena(ns, generation, freeList, freeCount, highWater, liveCount);
    }

    /**
     * Frees {@code ordinal}: nulls the slot, bumps its generation (wrapping at
     * {@link FactionHandle#MAX_GENERATION}), and pushes the ordinal onto the free-list so a
     * later allocation reuses it under a fresh generation.
     */
    public FactionArena freed(int ordinal) {
        if (ordinal < 0 || ordinal >= slots.length || slots[ordinal] == null) {
            return this;
        }
        Faction[] ns = Arrays.copyOf(slots, slots.length);
        int[] ng = Arrays.copyOf(generation, generation.length);
        ns[ordinal] = null;
        int g = ng[ordinal] + 1;
        ng[ordinal] = g > FactionHandle.MAX_GENERATION ? 0 : g;

        int[] newFree = ensureCapacity(freeList, freeCount + 1);
        newFree[freeCount] = ordinal;
        return new FactionArena(ns, ng, newFree, freeCount + 1, highWater, liveCount - 1);
    }

    private static Faction[] ensureCapacity(Faction[] a, int need) {
        if (need <= a.length) {
            return Arrays.copyOf(a, a.length);
        }
        int cap = a.length;
        while (cap < need) {
            cap <<= 1;
        }
        return Arrays.copyOf(a, cap);
    }

    private static int[] ensureCapacity(int[] a, int need) {
        if (need <= a.length) {
            return Arrays.copyOf(a, a.length);
        }
        int cap = a.length;
        while (cap < need) {
            cap <<= 1;
        }
        return Arrays.copyOf(a, cap);
    }
}
