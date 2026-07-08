package dev.fablemc.factions.core.economy;

import java.time.Duration;
import java.util.Objects;
import java.util.function.IntSupplier;

import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.kernel.config.EconomyConfig;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * Drives the periodic faction-bank tax sweep: an async repeating task that submits one {@link
 * EconomyIntent.TaxSweep} per configured interval; the reducer then pages over factions applying
 * the {@code round2} tax math (ref-engines.md §3.8.5, AM-5 paging). Like the power tick, the sweep
 * carries the shared monotonic tick so the {@link IntentBus} coalesces a backlog to the newest.
 *
 * <p><b>Owning thread(s):</b> {@link #start}/{@link #stop} on the boot/reload thread; the submit
 * fires on the async scheduler thread and only enqueues an intent (thread-rule clean).
 * <b>Mutability:</b> holds one task handle, guarded by the instance monitor.
 *
 * <p>The task is scheduled only when banks and tax are both enabled; a disabled tax leaves no timer
 * running (matching the reference's "don't schedule when off").
 */
public final class TaxScheduler {

    private final Scheduling scheduling;
    private final IntentBus bus;
    private final SnapshotHub snapshots;
    private final IntSupplier tick;

    private TaskHandle handle;

    /**
     * Constructor injection (CONTRACTS §4): the scheduler, the intent bus, the snapshot source (for
     * the live tax cadence) and the shared monotonic {@code tick} counter.
     */
    public TaxScheduler(Scheduling scheduling, IntentBus bus, SnapshotHub snapshots, IntSupplier tick) {
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.tick = Objects.requireNonNull(tick, "tick");
    }

    /**
     * Schedules the sweep at {@code factions.economy.tax.interval-hours} (clamped to a {@code >= 1}h
     * floor) when both banks and tax are enabled; otherwise leaves no timer. Idempotent.
     */
    public synchronized void start() {
        cancel();
        EconomyConfig economy = snapshots.current().config().economy();
        if (!economy.enabled() || !economy.taxEnabled()) {
            return;
        }
        int intervalHours = Math.max(1, economy.taxIntervalHours());
        Duration period = Duration.ofHours(intervalHours);
        handle = scheduling.repeatAsync(period, period, this::submit);
    }

    /** Cancels the sweep (plugin disable / reload). */
    public synchronized void stop() {
        cancel();
    }

    private void submit() {
        bus.submitSystem(new EconomyIntent.TaxSweep(tick.getAsInt()));
    }

    private void cancel() {
        if (handle != null) {
            handle.cancel();
            handle = null;
        }
    }
}
