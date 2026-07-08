package dev.fablemc.factions.kernel.state;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * The set of online member ordinals, as a COW {@code long[]} bitset (proposal-C §4.5/§5e).
 *
 * <p><b>Owning thread(s):</b> {@link #contains}/{@link #forEach} on any reader thread;
 * {@code with*} mutators on the writer only. <b>Mutability:</b> immutable, COW. <b>Reducer
 * rule:</b> fed by {@code PlayerConnected}/{@code PlayerDisconnected} intents; iterated by
 * {@code PowerTick} so a tick is O(online) not O(all members ever).
 *
 * <p>A bitset keyed by member ordinal makes membership and iteration allocation-light; a
 * mutation copies the {@code long[]} words (a few KB for thousands of players).
 */
public final class OnlineSet {

    private static final OnlineSet EMPTY = new OnlineSet(new long[0], 0);

    private final long[] words;
    private final int count;

    private OnlineSet(long[] words, int count) {
        this.words = words;
        this.count = count;
    }

    /** The shared empty set. */
    public static OnlineSet empty() {
        return EMPTY;
    }

    /** Number of online members. */
    public int size() {
        return count;
    }

    /** {@code true} when member {@code ord} is marked online. */
    public boolean contains(int ord) {
        if (ord < 0) {
            return false;
        }
        int w = ord >>> 6;
        if (w >= words.length) {
            return false;
        }
        return (words[w] & (1L << (ord & 63))) != 0;
    }

    /** Returns a copy with {@code ord} marked online (no-op if already set). */
    public OnlineSet with(int ord) {
        if (ord < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0: " + ord);
        }
        if (contains(ord)) {
            return this;
        }
        int w = ord >>> 6;
        long[] nw = ensure(words, w + 1);
        nw[w] |= (1L << (ord & 63));
        return new OnlineSet(nw, count + 1);
    }

    /** Returns a copy with {@code ord} marked offline (no-op if absent). */
    public OnlineSet without(int ord) {
        if (!contains(ord)) {
            return this;
        }
        int w = ord >>> 6;
        long[] nw = Arrays.copyOf(words, words.length);
        nw[w] &= ~(1L << (ord & 63));
        return new OnlineSet(nw, count - 1);
    }

    /** Visits every online ordinal in ascending order. */
    public void forEach(IntConsumer consumer) {
        for (int w = 0; w < words.length; w++) {
            long bits = words[w];
            while (bits != 0) {
                int b = Long.numberOfTrailingZeros(bits);
                consumer.accept((w << 6) + b);
                bits &= (bits - 1);
            }
        }
    }

    private static long[] ensure(long[] a, int need) {
        if (need <= a.length) {
            return Arrays.copyOf(a, a.length);
        }
        int cap = Math.max(a.length, 1);
        while (cap < need) {
            cap <<= 1;
        }
        return Arrays.copyOf(a, cap);
    }
}
