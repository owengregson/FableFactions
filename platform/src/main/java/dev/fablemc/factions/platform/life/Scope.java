package dev.fablemc.factions.platform.life;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * Every resource a subsystem acquires, closed as a unit on any exit (Mental pattern,
 * CONTRACTS §3). A subsystem registers its listeners, repeating tasks and closeables
 * through the scope; a reload/disable closes the scope <b>whole</b>.
 *
 * <p>Owning thread(s): the plugin main/boot thread (registration and close run there).
 * Mutability class: confined (single-threaded; the reconciler owns each scope).
 *
 * <p>{@link #close()} closes every registration in <b>reverse</b> registration order,
 * once; a throw in one close never skips the rest, and all such failures are collected
 * as suppressed onto one summary exception thrown at the end. Double-close is a no-op.
 * A partly-built scope (a throw part-way through assembly) still holds whatever was
 * already registered, so the caller can close it cleanly (zero leaked handlers).
 */
public final class Scope implements AutoCloseable {

    private final Plugin plugin;
    private final List<AutoCloseable> registrations = new ArrayList<>();
    private boolean closed;

    public Scope(@NotNull Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Registers {@code listener} for events and tracks it; unregistered in {@link #close()}. */
    public void listen(@NotNull Listener listener) {
        Objects.requireNonNull(listener, "listener");
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        registrations.add(() -> HandlerList.unregisterAll(listener));
    }

    /** Tracks a repeating task; cancelled in {@link #close()}. */
    public void task(@NotNull TaskHandle handle) {
        Objects.requireNonNull(handle, "handle");
        registrations.add(handle::cancel);
    }

    /** Tracks an arbitrary closeable; closed in {@link #close()}. */
    public void closeable(@NotNull AutoCloseable closeable) {
        Objects.requireNonNull(closeable, "closeable");
        registrations.add(closeable);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<Throwable> failures = new ArrayList<>();
        for (int i = registrations.size() - 1; i >= 0; i--) {
            try {
                registrations.get(i).close();
            } catch (Exception failure) {
                failures.add(failure);
            }
        }
        registrations.clear();
        if (!failures.isEmpty()) {
            RuntimeException summary = new RuntimeException(
                    "scope close encountered " + failures.size() + " failure(s)");
            failures.forEach(summary::addSuppressed);
            throw summary;
        }
    }
}
