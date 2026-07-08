package dev.fablemc.factions.platform.sched;

import java.time.Duration;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * The one scheduling surface FableFactions code is allowed to touch (CONTRACTS §3,
 * cloned VERBATIM from Mental's proven interface — {@code mental-seam.md} §4).
 *
 * <p>On Paper this delegates to the classic {@code BukkitScheduler}; on Folia it
 * routes through the region-aware schedulers. Module code therefore never needs
 * to know which platform it is running on and is region-correct by construction:
 * entity work follows the entity, location work runs on the owning region, global
 * work runs on the global tick.
 *
 * <p>Owning thread(s): implementations are called from any thread; they marshal to
 * the correct region/main thread. Mutability class: implementations are immutable
 * (they hold only the plugin handle).
 *
 * <h2>The retired-callback contract</h2>
 *
 * <p>The entity-scoped methods ({@link #runOn}, {@link #repeatOn},
 * {@link #runOnLater}) take a {@code retired} callback that fires when the target
 * entity is gone before the work could run. The two backends historically diverged
 * on when and where it fires; the contract is the honest common denominator, pinned
 * by the Scheduling TCK:
 *
 * <ul>
 *   <li><b>Either thread.</b> {@code retired} may run on the owning/main thread
 *       <em>or</em> inline on the caller thread. Callers MUST NOT assume any thread
 *       affinity for it.</li>
 *   <li><b>Exactly once.</b> For a single submission, exactly one of
 *       {@code task} / {@code retired} runs, and it runs exactly once. A repeating
 *       task that retires cancels itself and fires {@code retired} a single time.</li>
 *   <li><b>May be immediate.</b> {@code retired} may run before the submitting call
 *       returns (Folia inline case) or on a later tick (Bukkit case).</li>
 * </ul>
 */
public interface Scheduling {

    void runGlobal(@NotNull Runnable task);

    void runAt(@NotNull Location location, @NotNull Runnable task);

    /**
     * Runs on the thread that owns {@code entity}. If the entity is removed before
     * execution, {@code retired} runs instead (possibly immediately) — see the
     * retired-callback contract on the type javadoc.
     */
    void runOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired);

    /**
     * Runs {@code task} on the thread that owns {@code entity} after {@code delayTicks}
     * server ticks. If the entity is removed first, {@code retired} runs instead
     * (possibly immediately). A delay of {@code 0} is clamped to {@code 1} on Folia.
     */
    void runOnLater(
            @NotNull Entity entity, long delayTicks, @NotNull Runnable task, @NotNull Runnable retired);

    /**
     * Whether {@code entity} is owned by the region executing on the CURRENT thread.
     * Always {@code true} on Paper (one region owns everything); the real
     * region-ownership check on Folia. Gates live-entity reads that would otherwise
     * throw at a region boundary. Call it from a region/owning thread.
     */
    boolean isOwnedByCurrentRegion(@NotNull Entity entity);

    void runAsync(@NotNull Runnable task);

    @NotNull TaskHandle repeatGlobal(long initialTicks, long periodTicks, @NotNull Runnable task);

    @NotNull TaskHandle repeatOn(
            @NotNull Entity entity,
            long initialTicks,
            long periodTicks,
            @NotNull Runnable task,
            @NotNull Runnable retired);

    @NotNull TaskHandle repeatAsync(@NotNull Duration initial, @NotNull Duration period, @NotNull Runnable task);

    @NotNull String describe();

    /**
     * Runs {@code task} on the thread that owns {@code entity} — INLINE and
     * synchronously when the current thread already owns it, otherwise deferred
     * exactly like {@link #runOn}. The inline path removes the one-tick hop for work
     * that is already on the right thread. MUST be called from a region/owning
     * thread; a live entity owned by the caller never retires.
     */
    default void ensureOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
        if (entity.isValid() && isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            runOn(entity, task, retired);
        }
    }
}
