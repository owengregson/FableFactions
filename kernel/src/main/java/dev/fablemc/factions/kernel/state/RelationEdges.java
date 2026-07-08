package dev.fablemc.factions.kernel.state;

import java.util.Arrays;

/**
 * Zero-allocation probes and COW mutators for the sorted parallel relation arrays a
 * {@link Faction} carries.
 *
 * <p><b>Owning thread(s):</b> probes run on any reader thread; mutators run only on the writer.
 * <b>Mutability:</b> stateless helpers over caller-owned frozen arrays. <b>Reducer rule:</b>
 * the {@code with*} helpers return fresh arrays; the reducer wraps them in a new {@link Faction}.
 *
 * <p>Edges are keyed by the <b>other faction's ordinal</b> (not its handle): both endpoints are
 * live when an edge exists, and disband scrubbing (AM-6) removes edges before an ordinal can be
 * reused, so an ordinal key can never point at a reincarnated faction. Arrays are sorted
 * ascending by ordinal so lookup is a branch-lean binary search with no boxing or iterator
 * (proposal-C §5b). {@code len} is passed explicitly so callers may over-allocate backing
 * arrays.
 */
public final class RelationEdges {

    private RelationEdges() {
    }

    /** Empty ordinal-key array shared by relation-free factions. */
    public static final int[] NO_ORDINALS = new int[0];
    /** Empty kind array shared by relation-free factions. */
    public static final byte[] NO_KINDS = new byte[0];

    /** Binary-search index of {@code targetOrdinal} in {@code ordinals[0..len)}, or {@code -1}. */
    public static int indexOf(int[] ordinals, int len, int targetOrdinal) {
        int lo = 0;
        int hi = len - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = ordinals[mid];
            if (v < targetOrdinal) {
                lo = mid + 1;
            } else if (v > targetOrdinal) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /**
     * Relation kind toward {@code targetOrdinal}, or {@link RelationKind#NEUTRAL} when no edge
     * exists. Zero allocation — the load-bearing relation read.
     */
    public static byte kindOf(int[] ordinals, byte[] kinds, int len, int targetOrdinal) {
        int i = indexOf(ordinals, len, targetOrdinal);
        return i < 0 ? RelationKind.NEUTRAL : kinds[i];
    }

    /**
     * Result of a COW upsert/removal: two fresh, sorted parallel arrays and their live length.
     */
    public record Edges(int[] ordinals, byte[] kinds, int len) {
    }

    /**
     * Returns a copy of {@code (ordinals, kinds)} with {@code targetOrdinal} set to {@code kind}.
     * A {@code kind} of {@link RelationKind#NEUTRAL} removes the edge (neutral is the default and
     * is never stored). Preserves ascending-ordinal order.
     */
    public static Edges with(int[] ordinals, byte[] kinds, int len, int targetOrdinal, byte kind) {
        if (kind == RelationKind.NEUTRAL) {
            return without(ordinals, kinds, len, targetOrdinal);
        }
        int i = indexOf(ordinals, len, targetOrdinal);
        if (i >= 0) {
            int[] no = Arrays.copyOf(ordinals, len);
            byte[] nk = Arrays.copyOf(kinds, len);
            nk[i] = kind;
            return new Edges(no, nk, len);
        }
        // Insert keeping sorted order.
        int ins = insertionPoint(ordinals, len, targetOrdinal);
        int[] no = new int[len + 1];
        byte[] nk = new byte[len + 1];
        System.arraycopy(ordinals, 0, no, 0, ins);
        System.arraycopy(kinds, 0, nk, 0, ins);
        no[ins] = targetOrdinal;
        nk[ins] = kind;
        System.arraycopy(ordinals, ins, no, ins + 1, len - ins);
        System.arraycopy(kinds, ins, nk, ins + 1, len - ins);
        return new Edges(no, nk, len + 1);
    }

    /** Returns a copy of {@code (ordinals, kinds)} with any edge to {@code targetOrdinal} removed. */
    public static Edges without(int[] ordinals, byte[] kinds, int len, int targetOrdinal) {
        int i = indexOf(ordinals, len, targetOrdinal);
        if (i < 0) {
            return new Edges(ordinals, kinds, len);
        }
        int[] no = new int[len - 1];
        byte[] nk = new byte[len - 1];
        System.arraycopy(ordinals, 0, no, 0, i);
        System.arraycopy(kinds, 0, nk, 0, i);
        System.arraycopy(ordinals, i + 1, no, i, len - i - 1);
        System.arraycopy(kinds, i + 1, nk, i, len - i - 1);
        return new Edges(no, nk, len - 1);
    }

    private static int insertionPoint(int[] ordinals, int len, int target) {
        int lo = 0;
        int hi = len;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ordinals[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
