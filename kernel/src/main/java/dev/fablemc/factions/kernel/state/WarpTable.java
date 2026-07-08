package dev.fablemc.factions.kernel.state;

import java.util.Arrays;

/**
 * Per-faction named warps.
 *
 * <p><b>Owning thread(s):</b> queries on any reader thread; mutators on the writer only.
 * <b>Mutability:</b> immutable, COW. <b>Reducer rule:</b> only the reducer sets/deletes warps;
 * a mutation replaces one faction's warp array.
 *
 * <p>Warps are keyed by faction ordinal and hold a per-faction array sorted by (folded) name.
 * Warp counts are small (default cap 10), so linear scans are cheap.
 */
public final class WarpTable {

    private static final Warp[] NO_WARPS = new Warp[0];
    private static final WarpTable EMPTY = new WarpTable(new int[0], new Warp[0][], 0);

    private final int[] factionOrdinal;  // sorted ascending, parallel to warpsByFaction
    private final Warp[][] warpsByFaction;
    private final int total;

    private WarpTable(int[] factionOrdinal, Warp[][] warpsByFaction, int total) {
        this.factionOrdinal = factionOrdinal;
        this.warpsByFaction = warpsByFaction;
        this.total = total;
    }

    /** The shared empty table. */
    public static WarpTable empty() {
        return EMPTY;
    }

    /** Total warps across all factions. */
    public int size() {
        return total;
    }

    /** Warps owned by {@code faction} (fresh reference; do not mutate). Empty array if none. */
    public Warp[] forFaction(int faction) {
        int slot = slotOf(faction);
        return slot < 0 ? NO_WARPS : warpsByFaction[slot];
    }

    /** Number of warps {@code faction} owns. */
    public int countForFaction(int faction) {
        int slot = slotOf(faction);
        return slot < 0 ? 0 : warpsByFaction[slot].length;
    }

    /** Warp named {@code name} (case-sensitive as stored) for {@code faction}, or {@code null}. */
    public Warp get(int faction, String name) {
        int slot = slotOf(faction);
        if (slot < 0) {
            return null;
        }
        for (Warp w : warpsByFaction[slot]) {
            if (w.name().equalsIgnoreCase(name)) {
                return w;
            }
        }
        return null;
    }

    /** Returns a copy with {@code warp} set for {@code faction} (insert or replace by name). */
    public WarpTable set(int faction, Warp warp) {
        int slot = slotOf(faction);
        if (slot < 0) {
            int ins = insertionPoint(faction);
            int[] nf = insertInt(factionOrdinal, ins, faction);
            Warp[][] nw = insertRow(warpsByFaction, ins, new Warp[] {warp});
            return new WarpTable(nf, nw, total + 1);
        }
        Warp[] cur = warpsByFaction[slot];
        int idx = indexByName(cur, warp.name());
        Warp[] updated;
        int delta;
        if (idx >= 0) {
            updated = Arrays.copyOf(cur, cur.length);
            updated[idx] = warp;
            delta = 0;
        } else {
            updated = Arrays.copyOf(cur, cur.length + 1);
            updated[cur.length] = warp;
            delta = 1;
        }
        Warp[][] nw = warpsByFaction.clone();
        nw[slot] = updated;
        return new WarpTable(factionOrdinal.clone(), nw, total + delta);
    }

    /** Returns a copy with warp {@code name} removed from {@code faction} (no-op if absent). */
    public WarpTable delete(int faction, String name) {
        int slot = slotOf(faction);
        if (slot < 0) {
            return this;
        }
        Warp[] cur = warpsByFaction[slot];
        int idx = indexByName(cur, name);
        if (idx < 0) {
            return this;
        }
        if (cur.length == 1) {
            return dropFaction(slot);
        }
        Warp[] shrunk = new Warp[cur.length - 1];
        System.arraycopy(cur, 0, shrunk, 0, idx);
        System.arraycopy(cur, idx + 1, shrunk, idx, cur.length - idx - 1);
        Warp[][] nw = warpsByFaction.clone();
        nw[slot] = shrunk;
        return new WarpTable(factionOrdinal.clone(), nw, total - 1);
    }

    /** Returns a copy with every warp of {@code faction} removed (disband / merge scrub). */
    public WarpTable removeFaction(int faction) {
        int slot = slotOf(faction);
        return slot < 0 ? this : dropFaction(slot);
    }

    private WarpTable dropFaction(int slot) {
        int removed = warpsByFaction[slot].length;
        int[] nf = new int[factionOrdinal.length - 1];
        Warp[][] nw = new Warp[warpsByFaction.length - 1][];
        System.arraycopy(factionOrdinal, 0, nf, 0, slot);
        System.arraycopy(warpsByFaction, 0, nw, 0, slot);
        System.arraycopy(factionOrdinal, slot + 1, nf, slot, factionOrdinal.length - slot - 1);
        System.arraycopy(warpsByFaction, slot + 1, nw, slot, warpsByFaction.length - slot - 1);
        return new WarpTable(nf, nw, total - removed);
    }

    private int slotOf(int faction) {
        int i = Arrays.binarySearch(factionOrdinal, faction);
        return i;
    }

    private int insertionPoint(int faction) {
        int i = Arrays.binarySearch(factionOrdinal, faction);
        return i >= 0 ? i : -(i + 1);
    }

    private static int indexByName(Warp[] warps, String name) {
        for (int i = 0; i < warps.length; i++) {
            if (warps[i].name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static int[] insertInt(int[] a, int idx, int v) {
        int[] out = new int[a.length + 1];
        System.arraycopy(a, 0, out, 0, idx);
        out[idx] = v;
        System.arraycopy(a, idx, out, idx + 1, a.length - idx);
        return out;
    }

    private static Warp[][] insertRow(Warp[][] a, int idx, Warp[] row) {
        Warp[][] out = new Warp[a.length + 1][];
        System.arraycopy(a, 0, out, 0, idx);
        out[idx] = row;
        System.arraycopy(a, idx, out, idx + 1, a.length - idx);
        return out;
    }
}
