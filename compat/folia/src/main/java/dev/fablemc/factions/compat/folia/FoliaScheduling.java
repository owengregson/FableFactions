package dev.fablemc.factions.compat.folia;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * Region-aware {@link Scheduling} for Folia (CONTRACTS §3, AM-12; adapted from Mental's
 * proven backend, {@code mental-seam.md} §4).
 *
 * <p>Compiled against paper-api 1.20.4 as Java-17 bytecode so it loads on every Folia
 * build since 1.19.4. Constructed ONLY when {@code Capabilities.folia()} is true — loaded
 * by FQN string from {@code SchedulingFactory}; on regular Paper this class is never
 * touched, so its region-scheduler references never link.
 *
 * <p>Folia rejects tick delays below one, so delays are clamped rather than letting a
 * semantically-valid "this tick" request throw. Where {@code EntityScheduler} returns
 * {@code null} for an already-retired entity WITHOUT firing its retired hook, this backend
 * fires {@code retired} itself to honour the Scheduling contract.
 *
 * <p>Owning thread(s): callable from any region thread. Mutability class: immutable.
 */
public final class FoliaScheduling implements Scheduling {

    private final Plugin plugin;

    public FoliaScheduling(@NotNull Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void runGlobal(@NotNull Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    @Override
    public void runAt(@NotNull Location location, @NotNull Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, location, task);
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull Entity entity) {
        // The real region-ownership check: true iff the region executing on this thread
        // owns the entity, so the caller may read its live state safely.
        return Bukkit.getServer().isOwnedByCurrentRegion(entity);
    }

    @Override
    public void runOn(@NotNull Entity entity, @NotNull Runnable task, @NotNull Runnable retired) {
        ScheduledTask scheduled = entity.getScheduler().run(plugin, ignored -> task.run(), retired);
        if (scheduled == null) {
            // Already-retired at schedule time: Folia's run() returns null WITHOUT invoking
            // the retired callback — fire it here so callers' cleanup always runs (contract).
            retired.run();
        }
    }

    @Override
    public void runOnLater(
            @NotNull Entity entity, long delayTicks, @NotNull Runnable task, @NotNull Runnable retired) {
        ScheduledTask scheduled = entity.getScheduler()
                .runDelayed(plugin, ignored -> task.run(), retired, clampTicks(delayTicks));
        if (scheduled == null) {
            retired.run();
        }
    }

    @Override
    public void runAsync(@NotNull Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }

    @Override
    public @NotNull TaskHandle repeatGlobal(long initialTicks, long periodTicks, @NotNull Runnable task) {
        ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, ignored -> task.run(), clampTicks(initialTicks), clampTicks(periodTicks));
        return new FoliaHandle(scheduled);
    }

    @Override
    public @NotNull TaskHandle repeatOn(
            @NotNull Entity entity,
            long initialTicks,
            long periodTicks,
            @NotNull Runnable task,
            @NotNull Runnable retired) {
        ScheduledTask scheduled = entity.getScheduler().runAtFixedRate(
                plugin, ignored -> task.run(), retired, clampTicks(initialTicks), clampTicks(periodTicks));
        if (scheduled == null) {
            retired.run();
            return RetiredHandle.INSTANCE;
        }
        return new FoliaHandle(scheduled);
    }

    @Override
    public @NotNull TaskHandle repeatAsync(
            @NotNull Duration initial, @NotNull Duration period, @NotNull Runnable task) {
        ScheduledTask scheduled = Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                ignored -> task.run(),
                Math.max(0, initial.toMillis()),
                Math.max(1, period.toMillis()),
                TimeUnit.MILLISECONDS);
        return new FoliaHandle(scheduled);
    }

    @Override
    public @NotNull String describe() {
        return "folia";
    }

    private static long clampTicks(long ticks) {
        return Math.max(1, ticks);
    }

    /** Cancellation over a live Folia {@code ScheduledTask}. */
    private record FoliaHandle(@NotNull ScheduledTask task) implements TaskHandle {

        @Override
        public void cancel() {
            task.cancel();
        }

        @Override
        public boolean cancelled() {
            return task.isCancelled();
        }
    }

    /** The no-op handle for a repeat whose entity had already retired at schedule time. */
    private enum RetiredHandle implements TaskHandle {
        INSTANCE;

        @Override
        public void cancel() {}

        @Override
        public boolean cancelled() {
            return true;
        }
    }
}
