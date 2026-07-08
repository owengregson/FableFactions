package dev.fablemc.factions.platform.sched;

/**
 * Cancellation handle for a repeating task, safe to call from any thread
 * (CONTRACTS §3). A tiny abstraction over {@code BukkitTask} / Folia
 * {@code ScheduledTask} (plus a no-op singleton for the already-retired repeat case).
 *
 * <p>Owning thread(s): any. Mutability class: implementation-defined; both
 * {@link #cancel()} and {@link #cancelled()} are thread-safe.
 */
public interface TaskHandle {

    void cancel();

    boolean cancelled();
}
