package dev.fablemc.factions.kernel.effect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A reusable, growable append buffer for effects accumulated within a reducer batch.
 *
 * <p><b>Owning thread(s):</b> the single writer only — confined, never published while mutable.
 * <b>Mutability:</b> mutable buffer, reset between batches via {@link #clear()}. <b>Reducer
 * rule:</b> the reducer appends here as it processes an intent; the writer then {@link #drainTo}s
 * an immutable list into the published batch and reuses the buffer, so steady-state effect
 * accumulation allocates only the backing growth.
 *
 * <p>This is intentionally a plain array-backed buffer (not a stream/iterator) to keep the
 * reducer's hot loop allocation-light.
 */
public final class EffectList {

    private Effect[] items;
    private int size;

    /** Creates a buffer with the given initial capacity. */
    public EffectList(int initialCapacity) {
        this.items = new Effect[Math.max(1, initialCapacity)];
    }

    /** Creates a buffer with a small default capacity. */
    public EffectList() {
        this(16);
    }

    /** Appends {@code effect}. */
    public void add(Effect effect) {
        if (size == items.length) {
            items = Arrays.copyOf(items, size << 1);
        }
        items[size++] = effect;
    }

    /** Number of buffered effects. */
    public int size() {
        return size;
    }

    /** {@code true} when nothing is buffered. */
    public boolean isEmpty() {
        return size == 0;
    }

    /** The effect at {@code i} ({@code 0 <= i < size}). */
    public Effect get(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + " of size " + size);
        }
        return items[i];
    }

    /** Resets the buffer to empty (nulls references so drained effects can be collected). */
    public void clear() {
        for (int i = 0; i < size; i++) {
            items[i] = null;
        }
        size = 0;
    }

    /**
     * Copies the buffered effects into a fresh unmodifiable list and clears the buffer, ready for
     * the next batch. Returns an empty immutable list when nothing was buffered.
     */
    public List<Effect> drainToList() {
        if (size == 0) {
            clear();
            return Collections.emptyList();
        }
        ArrayList<Effect> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(items[i]);
        }
        clear();
        return Collections.unmodifiableList(out);
    }

    /** Snapshot copy of the buffered effects into a fresh array (does not clear). */
    public Effect[] toArray() {
        return Arrays.copyOf(items, size);
    }
}
