package dev.fablemc.factions.core.pipeline;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.intent.PowerIntent;
import dev.fablemc.factions.kernel.intent.EconomyIntent;

/**
 * The MPSC intent queue feeding the single writer (proposal-C §3.2, §3.5). Two lanes:
 *
 * <ul>
 *   <li><b>player lane</b> — bounded ({@value #DEFAULT_CAPACITY} by default); a full lane makes
 *       {@link #submit} return {@link SubmitResult#REJECTED_BUSY} so the game thread never
 *       blocks;</li>
 *   <li><b>system lane</b> — unbounded (join/quit/ticks/config/escrow settlements/continuations
 *       must never drop).</li>
 * </ul>
 *
 * <p>Submits are lock-free (an atomic counter bound + a Michael–Scott {@link ConcurrentLinkedQueue}).
 * {@code PowerTick} and {@code TaxSweep} are coalesced: only the newest-tick instance of each is
 * ever pending (a stale one is dropped), so a slow writer cannot accumulate a backlog of ticks.
 *
 * <p><b>Owning thread(s):</b> {@link #submit}/{@link #submitSystem} on any producer thread;
 * {@link #drain} on the single writer only. <b>Mutability:</b> thread-safe, lock-free.
 * Nondeterminism (wall clock, tick, rng seed) is captured into the envelope at enqueue; the
 * writer assigns the total-order {@code seq} at drain (proposal-C §4.5).
 */
public final class IntentBus {

    /** The default bounded player-lane capacity (proposal-C §3.5). */
    public static final int DEFAULT_CAPACITY = 65_536;

    // Sentinel envelopes that mark a coalesced tick's slot in the FIFO system lane; on drain the
    // real (latest) envelope is read from the matching slot. Identity-compared (never reduced).
    private static final IntentEnvelope POWER_TOKEN = token();
    private static final IntentEnvelope TAX_TOKEN = token();

    private final int capacity;
    private final LongSupplier clock;
    private final IntSupplier tick;
    private final Runnable wakeup;

    private final Queue<IntentEnvelope> playerLane = new ConcurrentLinkedQueue<>();
    private final AtomicInteger playerCount = new AtomicInteger();
    private final Queue<IntentEnvelope> systemLane = new ConcurrentLinkedQueue<>();
    private final AtomicReference<IntentEnvelope> pendingPowerTick = new AtomicReference<>();
    private final AtomicReference<IntentEnvelope> pendingTaxSweep = new AtomicReference<>();

    private volatile boolean shutdown;

    /** Full constructor (used by boot and tests). {@code wakeup} unparks the writer after enqueue. */
    public IntentBus(int capacity, LongSupplier clock, IntSupplier tick, Runnable wakeup) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tick = Objects.requireNonNull(tick, "tick");
        this.wakeup = Objects.requireNonNull(wakeup, "wakeup");
    }

    /** Default-capacity constructor. */
    public IntentBus(LongSupplier clock, IntSupplier tick, Runnable wakeup) {
        this(DEFAULT_CAPACITY, clock, tick, wakeup);
    }

    /** Submits a player/admin/api intent onto the bounded lane; may reject (proposal-C §3.5). */
    public SubmitResult submit(Intent intent, Origin origin) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(origin, "origin");
        if (shutdown) {
            return SubmitResult.REJECTED_SHUTDOWN;
        }
        int count = playerCount.incrementAndGet();
        if (count > capacity) {
            playerCount.decrementAndGet();
            return SubmitResult.REJECTED_BUSY;
        }
        playerLane.offer(envelope(origin, intent));
        wakeup.run();
        return SubmitResult.ACCEPTED;
    }

    /** Submits a system intent onto the unbounded lane (never rejected); ticks are coalesced. */
    public void submitSystem(Intent intent) {
        Objects.requireNonNull(intent, "intent");
        if (intent instanceof PowerIntent.PowerTick pt) {
            coalesce(pendingPowerTick, POWER_TOKEN,
                    envelope(Origin.SYSTEM_ORIGIN, intent), pt.tick());
        } else if (intent instanceof EconomyIntent.TaxSweep ts) {
            coalesce(pendingTaxSweep, TAX_TOKEN,
                    envelope(Origin.SYSTEM_ORIGIN, intent), ts.tick());
        } else {
            systemLane.offer(envelope(Origin.SYSTEM_ORIGIN, intent));
        }
        wakeup.run();
    }

    /**
     * Drains up to {@code max} envelopes into {@code out} (system lane first, then player lane),
     * resolving coalesced ticks. Returns the number drained. Called by the writer only.
     */
    public int drain(List<IntentEnvelope> out, int max) {
        int n = 0;
        IntentEnvelope e;
        while (n < max && (e = pollSystem()) != null) {
            out.add(e);
            n++;
        }
        while (n < max && (e = pollPlayer()) != null) {
            out.add(e);
            n++;
        }
        return n;
    }

    /**
     * Approximate depth across both lanes (telemetry / tests). Each coalesced {@code PowerTick}/
     * {@code TaxSweep} is represented by exactly one FIFO placeholder token in {@link #systemLane}
     * (offered once when its slot goes empty→pending), so {@code systemLane.size()} already counts
     * it — the pending refs must NOT be added again or a single pending tick would be double-counted.
     */
    public int depth() {
        return playerCount.get() + systemLane.size();
    }

    /** Rejects all new player intents (shutdown phase 1, proposal-C §6.4). System lane stays open. */
    public void beginShutdown() {
        shutdown = true;
    }

    /** {@code true} once {@link #beginShutdown()} has been called. */
    public boolean isShuttingDown() {
        return shutdown;
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────

    private IntentEnvelope pollSystem() {
        IntentEnvelope e = systemLane.poll();
        if (e == null) {
            return null;
        }
        if (e == POWER_TOKEN) {
            IntentEnvelope real = pendingPowerTick.getAndSet(null);
            return real != null ? real : pollSystem();
        }
        if (e == TAX_TOKEN) {
            IntentEnvelope real = pendingTaxSweep.getAndSet(null);
            return real != null ? real : pollSystem();
        }
        return e;
    }

    private IntentEnvelope pollPlayer() {
        IntentEnvelope e = playerLane.poll();
        if (e != null) {
            playerCount.decrementAndGet();
        }
        return e;
    }

    private void coalesce(AtomicReference<IntentEnvelope> slot, IntentEnvelope token,
                          IntentEnvelope candidate, int candidateTick) {
        IntentEnvelope prev = slot.get();
        while (prev == null || tickOf(prev) < candidateTick) {
            if (slot.compareAndSet(prev, candidate)) {
                if (prev == null) {
                    // slot transitioned empty→pending: enqueue exactly one FIFO placeholder.
                    systemLane.offer(token);
                }
                return;
            }
            prev = slot.get();
        }
        // an equal-or-newer tick is already pending: drop the stale candidate.
    }

    private static int tickOf(IntentEnvelope env) {
        Intent i = env.intent();
        if (i instanceof PowerIntent.PowerTick pt) {
            return pt.tick();
        }
        if (i instanceof EconomyIntent.TaxSweep ts) {
            return ts.tick();
        }
        return Integer.MIN_VALUE;
    }

    private IntentEnvelope envelope(Origin origin, Intent intent) {
        return new IntentEnvelope(0L, clock.getAsLong(), tick.getAsInt(),
                ThreadLocalRandom.current().nextLong(), origin, intent);
    }

    private static IntentEnvelope token() {
        return new IntentEnvelope(-1L, 0L, Integer.MIN_VALUE, 0L, Origin.SYSTEM_ORIGIN, null);
    }
}
