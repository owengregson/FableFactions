package dev.fablemc.factions.kernel.state;

import java.util.Arrays;
import java.util.Locale;

/**
 * Fold-cased faction-name → ordinal index — the in-memory uniqueness authority (kills the
 * name-uniqueness TOCTOU, checked in the same reducer step that inserts; DB UNIQUE is the belt).
 *
 * <p><b>Owning thread(s):</b> {@link #ordinalOf} on any reader thread; {@code with*} mutators
 * on the writer only. <b>Mutability:</b> immutable, COW open addressing. <b>Reducer rule:</b>
 * only the reducer registers or releases names.
 *
 * <p>Names are folded with {@link #fold} (locale-independent lower-casing) so lookups and
 * uniqueness are case-insensitive. Callers pass an already-folded key; {@link Faction#nameFolded}
 * is the pre-computed fold.
 */
public final class NameIndex {

    /** Returned by {@link #ordinalOf} when no faction owns the name. */
    public static final int ABSENT = -1;

    private static final double MAX_LOAD = 0.6;
    private static final NameIndex EMPTY = new NameIndex(new String[2], newFilled(2), 0);

    private final String[] keys;   // folded names; null ⇒ empty slot
    private final int[] ordinal;   // -1 ⇒ empty slot
    private final int size;
    private final int mask;

    private NameIndex(String[] keys, int[] ordinal, int size) {
        this.keys = keys;
        this.ordinal = ordinal;
        this.size = size;
        this.mask = keys.length - 1;
    }

    /** The shared empty index. */
    public static NameIndex empty() {
        return EMPTY;
    }

    /** Number of registered names. */
    public int size() {
        return size;
    }

    /** Locale-independent case-fold used for all name comparisons. */
    public static String fold(String name) {
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    /** Ordinal of the faction owning {@code nameFolded}, or {@link #ABSENT}. */
    public int ordinalOf(String nameFolded) {
        if (nameFolded == null) {
            return ABSENT;
        }
        int i = index(nameFolded);
        while (true) {
            String k = keys[i];
            if (k == null) {
                return ABSENT;
            }
            if (k.equals(nameFolded)) {
                return ordinal[i];
            }
            i = (i + 1) & mask;
        }
    }

    /** {@code true} when {@code nameFolded} is already registered (uniqueness check). */
    public boolean contains(String nameFolded) {
        return ordinalOf(nameFolded) != ABSENT;
    }

    /** Returns a copy registering {@code nameFolded → ordinal} (insert or overwrite). */
    public NameIndex with(String nameFolded, int ordinal) {
        boolean existed = contains(nameFolded);
        int newSize = existed ? size : size + 1;
        if (!existed && newSize > (int) (keys.length * MAX_LOAD)) {
            return rebuild(keys.length << 1, nameFolded, ordinal);
        }
        String[] nk = Arrays.copyOf(keys, keys.length);
        int[] no = Arrays.copyOf(this.ordinal, this.ordinal.length);
        insertRaw(nk, no, nameFolded, ordinal);
        return new NameIndex(nk, no, newSize);
    }

    /** Returns a copy with {@code nameFolded} released (no-op if absent). */
    public NameIndex without(String nameFolded) {
        if (!contains(nameFolded)) {
            return this;
        }
        if (size == 1) {
            return EMPTY;
        }
        // Rebuild from remaining entries to avoid tombstones.
        int cap = capacityFor(size - 1);
        String[] nk = new String[cap];
        int[] no = newFilled(cap);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != null && !keys[i].equals(nameFolded)) {
                insertRaw(nk, no, keys[i], ordinal[i]);
            }
        }
        return new NameIndex(nk, no, size - 1);
    }

    private int index(String nameFolded) {
        return (nameFolded.hashCode() * 0x9E3779B1 >>> 1) & mask;
    }

    private NameIndex rebuild(int newCap, String addKey, int addOrd) {
        String[] nk = new String[newCap];
        int[] no = newFilled(newCap);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != null) {
                insertRaw(nk, no, keys[i], ordinal[i]);
            }
        }
        insertRaw(nk, no, addKey, addOrd);
        return new NameIndex(nk, no, size + 1);
    }

    private static void insertRaw(String[] keys, int[] ordinal, String key, int ord) {
        int mask = keys.length - 1;
        int i = (key.hashCode() * 0x9E3779B1 >>> 1) & mask;
        while (keys[i] != null) {
            if (keys[i].equals(key)) {
                ordinal[i] = ord;
                return;
            }
            i = (i + 1) & mask;
        }
        keys[i] = key;
        ordinal[i] = ord;
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
        Arrays.fill(a, ABSENT);
        return a;
    }
}
