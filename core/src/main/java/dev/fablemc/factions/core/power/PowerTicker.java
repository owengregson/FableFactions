package dev.fablemc.factions.core.power;

import java.time.Duration;
import java.util.Objects;
import java.util.function.IntSupplier;

import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.kernel.intent.PowerIntent;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * Drives the periodic power tick: an async repeating task that submits one {@link
 * PowerIntent.PowerTick} per configured interval, letting the reducer settle regen/raidable state
 * for all players in a single coalesced pass (ref-engines.md §3.7.1, proposal-C §4.5). The tick
 * carries the shared monotonic tick counter so the {@link IntentBus} coalesces a backlog down to
 * the newest tick — a slow writer never accumulates stale ticks.
 *
 * <p><b>Owning thread(s):</b> {@link #start}/{@link #stop} on the boot/reload thread; the submit
 * fires on the async scheduler thread. All it does is enqueue an intent (no Bukkit, no state
 * construction), so it is thread-rule clean. <b>Mutability:</b> holds one task handle, replaced on
 * {@link #start} and cleared on {@link #stop}; guarded by the instance monitor.
 */
public final class PowerTicker {

    private final Scheduling scheduling;
    private final IntentBus bus;
    private final SnapshotHub snapshots;
    private final IntSupplier tick;

    private TaskHandle handle;

    /**
     * Constructor injection (CONTRACTS §4): the scheduler, the intent bus, the snapshot source
     * (for the live tick-interval config) and the shared monotonic {@code tick} counter the bus
     * stamps onto envelopes.
     */
    public PowerTicker(Scheduling scheduling, IntentBus bus, SnapshotHub snapshots, IntSupplier tick) {
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.tick = Objects.requireNonNull(tick, "tick");
    }

    /**
     * Schedules the repeating tick at {@code factions.power.tick-interval-seconds} (clamped to a
     * {@code >= 1}s floor). Idempotent: a second {@link #start} cancels the prior task first.
     */
    public synchronized void start() {
        cancel();
        int intervalSeconds = Math.max(1, snapshots.current().config().power().tickIntervalSeconds());
        Duration period = Duration.ofSeconds(intervalSeconds);
        handle = scheduling.repeatAsync(period, period, this::submit);
    }

    /** Cancels the repeating tick (plugin disable / reload). */
    public synchronized void stop() {
        cancel();
    }

    private void submit() {
        bus.submitSystem(new PowerIntent.PowerTick(tick.getAsInt()));
    }

    private void cancel() {
        if (handle != null) {
            handle.cancel();
            handle = null;
        }
    }
}
