package dev.fablemc.factions.kernel.state;

import java.util.Arrays;

/**
 * Per-faction named team chests (contents referenced by {@code blobRef}; bytes live in storage).
 *
 * <p><b>Owning thread(s):</b> queries on any reader thread; mutators on the writer only.
 * <b>Mutability:</b> immutable, COW. <b>Reducer rule:</b> only the reducer creates/deletes
 * chests and commits new content blob refs; a mutation replaces one faction's chest array.
 *
 * <p>Chest names are normalized (trimmed, lower-cased) upstream and matched case-insensitively.
 * The default cap is small (1), so linear scans are cheap.
 */
public final class ChestTable {

    private static final ChestRef[] NO_CHESTS = new ChestRef[0];
    private static final ChestTable EMPTY = new ChestTable(new int[0], new ChestRef[0][], 0);

    private final int[] factionOrdinal;   // sorted ascending, parallel to chestsByFaction
    private final ChestRef[][] chestsByFaction;
    private final int total;

    private ChestTable(int[] factionOrdinal, ChestRef[][] chestsByFaction, int total) {
        this.factionOrdinal = factionOrdinal;
        this.chestsByFaction = chestsByFaction;
        this.total = total;
    }

    /** The shared empty table. */
    public static ChestTable empty() {
        return EMPTY;
    }

    /** Total chests across all factions. */
    public int size() {
        return total;
    }

    /** Chests owned by {@code faction} (fresh reference; do not mutate). Empty array if none. */
    public ChestRef[] forFaction(int faction) {
        int slot = slotOf(faction);
        return slot < 0 ? NO_CHESTS : chestsByFaction[slot];
    }

    /** Number of chests {@code faction} owns. */
    public int countForFaction(int faction) {
        int slot = slotOf(faction);
        return slot < 0 ? 0 : chestsByFaction[slot].length;
    }

    /** Chest named {@code name} for {@code faction}, or {@code null}. */
    public ChestRef get(int faction, String name) {
        int slot = slotOf(faction);
        if (slot < 0) {
            return null;
        }
        for (ChestRef c : chestsByFaction[slot]) {
            if (c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    /** Returns a copy with {@code chest} set for {@code faction} (insert or replace by name). */
    public ChestTable set(int faction, ChestRef chest) {
        int slot = slotOf(faction);
        if (slot < 0) {
            int ins = insertionPoint(faction);
            int[] nf = insertInt(factionOrdinal, ins, faction);
            ChestRef[][] nc = insertRow(chestsByFaction, ins, new ChestRef[] {chest});
            return new ChestTable(nf, nc, total + 1);
        }
        ChestRef[] cur = chestsByFaction[slot];
        int idx = indexByName(cur, chest.name());
        ChestRef[] updated;
        int delta;
        if (idx >= 0) {
            updated = Arrays.copyOf(cur, cur.length);
            updated[idx] = chest;
            delta = 0;
        } else {
            updated = Arrays.copyOf(cur, cur.length + 1);
            updated[cur.length] = chest;
            delta = 1;
        }
        ChestRef[][] nc = chestsByFaction.clone();
        nc[slot] = updated;
        return new ChestTable(factionOrdinal.clone(), nc, total + delta);
    }

    /** Returns a copy with chest {@code name} removed from {@code faction} (no-op if absent). */
    public ChestTable delete(int faction, String name) {
        int slot = slotOf(faction);
        if (slot < 0) {
            return this;
        }
        ChestRef[] cur = chestsByFaction[slot];
        int idx = indexByName(cur, name);
        if (idx < 0) {
            return this;
        }
        if (cur.length == 1) {
            return dropFaction(slot);
        }
        ChestRef[] shrunk = new ChestRef[cur.length - 1];
        System.arraycopy(cur, 0, shrunk, 0, idx);
        System.arraycopy(cur, idx + 1, shrunk, idx, cur.length - idx - 1);
        ChestRef[][] nc = chestsByFaction.clone();
        nc[slot] = shrunk;
        return new ChestTable(factionOrdinal.clone(), nc, total - 1);
    }

    /** Returns a copy with every chest of {@code faction} removed (disband / merge scrub). */
    public ChestTable removeFaction(int faction) {
        int slot = slotOf(faction);
        return slot < 0 ? this : dropFaction(slot);
    }

    private ChestTable dropFaction(int slot) {
        int removed = chestsByFaction[slot].length;
        int[] nf = new int[factionOrdinal.length - 1];
        ChestRef[][] nc = new ChestRef[chestsByFaction.length - 1][];
        System.arraycopy(factionOrdinal, 0, nf, 0, slot);
        System.arraycopy(chestsByFaction, 0, nc, 0, slot);
        System.arraycopy(factionOrdinal, slot + 1, nf, slot, factionOrdinal.length - slot - 1);
        System.arraycopy(chestsByFaction, slot + 1, nc, slot, chestsByFaction.length - slot - 1);
        return new ChestTable(nf, nc, total - removed);
    }

    private int slotOf(int faction) {
        return Arrays.binarySearch(factionOrdinal, faction);
    }

    private int insertionPoint(int faction) {
        int i = Arrays.binarySearch(factionOrdinal, faction);
        return i >= 0 ? i : -(i + 1);
    }

    private static int indexByName(ChestRef[] chests, String name) {
        for (int i = 0; i < chests.length; i++) {
            if (chests[i].name().equalsIgnoreCase(name)) {
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

    private static ChestRef[][] insertRow(ChestRef[][] a, int idx, ChestRef[] row) {
        ChestRef[][] out = new ChestRef[a.length + 1][];
        System.arraycopy(a, 0, out, 0, idx);
        out[idx] = row;
        System.arraycopy(a, idx, out, idx + 1, a.length - idx);
        return out;
    }
}
