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
 *
 * <p>Each chest carries a per-chest <b>session nonce</b> (parallel to the {@link ChestRef} array,
 * kept out of {@code ChestRef} so its persisted/replayed shape is untouched). The reducer records
 * the winning (highest, monotonic) commit nonce here and rejects a lower one, so a stale session's
 * late {@code CommitChestContents} cannot overwrite a newer session (the lost-update/dupe guard,
 * finding #26). It is runtime-only durability metadata: it is not projected to storage, so it loads
 * back as {@code 0} on boot, which is always {@code <=} any live monotonic nonce and thus accepts
 * the first post-boot commit.
 */
public final class ChestTable {

    private static final ChestRef[] NO_CHESTS = new ChestRef[0];
    private static final ChestTable EMPTY =
            new ChestTable(new int[0], new ChestRef[0][], new long[0][], 0);

    /** Sentinel nonce for a chest with no session commit recorded (fresh / just-created). */
    public static final long NO_NONCE = 0L;

    private final int[] factionOrdinal;   // sorted ascending, parallel to chestsByFaction / nonceByFaction
    private final ChestRef[][] chestsByFaction;
    private final long[][] nonceByFaction; // parallel to chestsByFaction: the winning session nonce per chest
    private final int total;

    private ChestTable(int[] factionOrdinal, ChestRef[][] chestsByFaction,
                       long[][] nonceByFaction, int total) {
        this.factionOrdinal = factionOrdinal;
        this.chestsByFaction = chestsByFaction;
        this.nonceByFaction = nonceByFaction;
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

    /**
     * The recorded winning session nonce for chest {@code name} of {@code faction}, or
     * {@link #NO_NONCE} if the chest is absent or has no commit recorded yet.
     */
    public long nonceOf(int faction, String name) {
        int slot = slotOf(faction);
        if (slot < 0) {
            return NO_NONCE;
        }
        ChestRef[] chests = chestsByFaction[slot];
        for (int i = 0; i < chests.length; i++) {
            if (chests[i].name().equalsIgnoreCase(name)) {
                return nonceByFaction[slot][i];
            }
        }
        return NO_NONCE;
    }

    /**
     * Returns a copy with {@code chest} set for {@code faction} (insert or replace by name),
     * <b>preserving</b> the existing session nonce on replace ({@link #NO_NONCE} on insert). Used by
     * create/baseline paths that do not touch the nonce.
     */
    public ChestTable set(int faction, ChestRef chest) {
        return set(faction, chest, nonceOf(faction, chest.name()));
    }

    /**
     * Returns a copy with {@code chest} set for {@code faction} and its session nonce set to
     * {@code sessionNonce} (insert or replace by name). The commit reducer calls this to record the
     * winning nonce alongside the new blob ref.
     */
    public ChestTable set(int faction, ChestRef chest, long sessionNonce) {
        int slot = slotOf(faction);
        if (slot < 0) {
            int ins = insertionPoint(faction);
            int[] nf = insertInt(factionOrdinal, ins, faction);
            ChestRef[][] nc = insertRow(chestsByFaction, ins, new ChestRef[] {chest});
            long[][] nn = insertNonceRow(nonceByFaction, ins, new long[] {sessionNonce});
            return new ChestTable(nf, nc, nn, total + 1);
        }
        ChestRef[] cur = chestsByFaction[slot];
        long[] curNonce = nonceByFaction[slot];
        int idx = indexByName(cur, chest.name());
        ChestRef[] updated;
        long[] updatedNonce;
        int delta;
        if (idx >= 0) {
            updated = Arrays.copyOf(cur, cur.length);
            updated[idx] = chest;
            updatedNonce = Arrays.copyOf(curNonce, curNonce.length);
            updatedNonce[idx] = sessionNonce;
            delta = 0;
        } else {
            updated = Arrays.copyOf(cur, cur.length + 1);
            updated[cur.length] = chest;
            updatedNonce = Arrays.copyOf(curNonce, curNonce.length + 1);
            updatedNonce[curNonce.length] = sessionNonce;
            delta = 1;
        }
        ChestRef[][] nc = chestsByFaction.clone();
        nc[slot] = updated;
        long[][] nn = nonceByFaction.clone();
        nn[slot] = updatedNonce;
        return new ChestTable(factionOrdinal.clone(), nc, nn, total + delta);
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
        long[] curNonce = nonceByFaction[slot];
        long[] shrunkNonce = new long[curNonce.length - 1];
        System.arraycopy(curNonce, 0, shrunkNonce, 0, idx);
        System.arraycopy(curNonce, idx + 1, shrunkNonce, idx, curNonce.length - idx - 1);
        ChestRef[][] nc = chestsByFaction.clone();
        nc[slot] = shrunk;
        long[][] nn = nonceByFaction.clone();
        nn[slot] = shrunkNonce;
        return new ChestTable(factionOrdinal.clone(), nc, nn, total - 1);
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
        long[][] nn = new long[nonceByFaction.length - 1][];
        System.arraycopy(factionOrdinal, 0, nf, 0, slot);
        System.arraycopy(chestsByFaction, 0, nc, 0, slot);
        System.arraycopy(nonceByFaction, 0, nn, 0, slot);
        System.arraycopy(factionOrdinal, slot + 1, nf, slot, factionOrdinal.length - slot - 1);
        System.arraycopy(chestsByFaction, slot + 1, nc, slot, chestsByFaction.length - slot - 1);
        System.arraycopy(nonceByFaction, slot + 1, nn, slot, nonceByFaction.length - slot - 1);
        return new ChestTable(nf, nc, nn, total - removed);
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

    private static long[][] insertNonceRow(long[][] a, int idx, long[] row) {
        long[][] out = new long[a.length + 1][];
        System.arraycopy(a, 0, out, 0, idx);
        out[idx] = row;
        System.arraycopy(a, idx, out, idx + 1, a.length - idx);
        return out;
    }
}
