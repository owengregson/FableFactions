package dev.fablemc.factions.platform.resolve;

import java.util.Arrays;
import java.util.UUID;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The world-identity registry (CONTRACTS §3, AM-15). Worlds are keyed by
 * {@code World#getUID} and assigned a small dense {@code int} index used on hot paths
 * (claim atlas keys, per-world config arrays) — never object identity, never the name
 * string. The mapping is copy-on-write: reads are wait-free and allocation-free (a plain
 * volatile-array scan over a handful of worlds), writes clone-then-publish under a lock.
 * The registry is rebound on {@code WorldLoadEvent}/{@code WorldUnloadEvent} (both
 * floor-safe); a freed index is reused so the space stays compact.
 *
 * <p>Owning thread(s): {@link #register}/{@link #unregister} run on the main thread
 * (world load/unload); {@link #indexOf}/{@link #uid} are safe from any thread. Mutability
 * class: COW (immutable published snapshot swapped under the write lock). The UUID-keyed
 * core is Bukkit-free and unit-testable.
 */
public final class Worlds {

    /** The published snapshot: {@code byIndex[i]} is the world at index {@code i}, or {@code null} if freed. */
    private volatile UUID[] byIndex = new UUID[0];
    private final Object writeLock = new Object();

    public Worlds() {}

    /** Idempotently registers {@code uid}, returning its (possibly reused) dense index. */
    public int register(@NotNull UUID uid) {
        synchronized (writeLock) {
            UUID[] current = byIndex;
            int free = -1;
            for (int i = 0; i < current.length; i++) {
                if (uid.equals(current[i])) {
                    return i;
                }
                if (free < 0 && current[i] == null) {
                    free = i;
                }
            }
            if (free >= 0) {
                UUID[] next = current.clone();
                next[free] = uid;
                byIndex = next;
                return free;
            }
            UUID[] next = Arrays.copyOf(current, current.length + 1);
            next[current.length] = uid;
            byIndex = next;
            return current.length;
        }
    }

    /** The index of {@code uid}, or {@code -1} if it is not registered. Wait-free. */
    public int indexOf(@NotNull UUID uid) {
        UUID[] snapshot = byIndex;
        for (int i = 0; i < snapshot.length; i++) {
            if (uid.equals(snapshot[i])) {
                return i;
            }
        }
        return -1;
    }

    /** The world UUID at {@code index}, or {@code null} if out of range or freed. Wait-free. */
    public @Nullable UUID uid(int index) {
        UUID[] snapshot = byIndex;
        if (index < 0 || index >= snapshot.length) {
            return null;
        }
        return snapshot[index];
    }

    /** Frees {@code uid}'s index (world unload); a no-op if it was not registered. */
    public void unregister(@NotNull UUID uid) {
        synchronized (writeLock) {
            UUID[] current = byIndex;
            for (int i = 0; i < current.length; i++) {
                if (uid.equals(current[i])) {
                    UUID[] next = current.clone();
                    next[i] = null;
                    byIndex = next;
                    return;
                }
            }
        }
    }

    /** The number of currently-registered worlds. */
    public int size() {
        UUID[] snapshot = byIndex;
        int count = 0;
        for (UUID uid : snapshot) {
            if (uid != null) {
                count++;
            }
        }
        return count;
    }

    /* ---- World-typed conveniences (floor-safe: World is present since 1.7.10) ---- */

    /** Registers {@code world} on load and returns its index. */
    public int index(@NotNull World world) {
        return register(world.getUID());
    }

    /** The index of {@code world}, or {@code -1} if not registered. */
    public int indexOf(@NotNull World world) {
        return indexOf(world.getUID());
    }

    /** {@code WorldLoadEvent} hook — binds the world to an index. */
    public void onLoad(@NotNull World world) {
        register(world.getUID());
    }

    /** {@code WorldUnloadEvent} hook — frees the world's index. */
    public void onUnload(@NotNull World world) {
        unregister(world.getUID());
    }
}
