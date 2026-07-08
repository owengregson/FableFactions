package dev.fablemc.factions.core.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.reduce.Reducer;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;

/**
 * The single {@code fable-kernel} writer (proposal-C §3.2, AM-9). It parks until signalled,
 * drains up to {@value #MAX_DRAIN} envelopes, reduces each inside its own failure boundary,
 * publishes exactly one snapshot per batch, then hands the ordered effect batch to the journal
 * sink and the fanout sink in that order.
 *
 * <p>Paged continuations (AM-5): a reducer step may emit an {@link SystemEffect.ContinuationRequested}
 * carrying the next page's {@link Intent}. That control effect is <b>not</b> a domain effect — it
 * is stripped from the batch before journaling/fanout, and its intent is re-enqueued on the
 * system lane <em>after</em> the intermediate snapshot is published, so interleaved intents
 * observe consistent shrinking-but-valid paged states.
 *
 * <p><b>Owning thread(s):</b> everything after construction runs on the one writer thread — it
 * is the ONLY mutator of {@link KernelState}, the seq counter and the circuit breaker.
 * {@link #wake()}/{@link #shutdown()} are callable from any thread. <b>Mutability:</b> confined
 * to the writer thread; cross-thread visibility of state is exclusively through
 * {@link SnapshotHub#publish}.
 *
 * <p>Failure boundary (AM-9): a throwing reduce step (a kernel bug) is caught per intent — it
 * emits {@code Rejected(INTERNAL_ERROR)}, logs the full stack once, leaves state at the
 * pre-intent value, and the writer continues. A second failure of the <em>same intent type</em>
 * within {@value #BREAKER_WINDOW_MS}ms trips a per-type circuit breaker: further intents of that
 * type are rejected ({@code Rejected(BUSY)}) without reducing until the window elapses. An
 * {@link Error} escaping the boundary kills the loop; the uncaught handler then calls
 * {@link FailureHandler#onWriterFailed} so the plugin is disabled loudly.
 */
public final class WriterThread {

    /** Maximum envelopes drained per batch before a publish (proposal-C §3.2). */
    public static final int MAX_DRAIN = 1024;

    /** Circuit-breaker window: a repeat same-type failure within this trips the breaker (AM-9). */
    public static final long BREAKER_WINDOW_MS = 60_000L;

    private final IntentBus bus;
    private final SnapshotHub hub;
    private final ReduceStep reduce;
    private final EffectSink journalSink;
    private final EffectSink fanoutSink;
    private final FailureHandler onFatal;
    private final LongSupplier clock;
    private final Logger logger;

    private final List<IntentEnvelope> drainBuffer = new ArrayList<>(MAX_DRAIN);
    private final Map<Class<?>, long[]> breaker = new HashMap<>();   // type -> {lastFailAt, trippedUntil}

    private KernelState state;
    private long nextSeq;

    private volatile Thread thread;
    private volatile boolean running;

    public WriterThread(IntentBus bus, SnapshotHub hub, KernelState initial, long startSeq,
                        ReduceStep reduce, EffectSink journalSink, EffectSink fanoutSink,
                        FailureHandler onFatal, LongSupplier clock, Logger logger) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.hub = Objects.requireNonNull(hub, "hub");
        this.state = Objects.requireNonNull(initial, "initial");
        this.nextSeq = startSeq;
        this.reduce = Objects.requireNonNull(reduce, "reduce");
        this.journalSink = Objects.requireNonNull(journalSink, "journalSink");
        this.fanoutSink = Objects.requireNonNull(fanoutSink, "fanoutSink");
        this.onFatal = Objects.requireNonNull(onFatal, "onFatal");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Starts the daemon writer thread. Idempotent (a second call is a no-op). */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        Thread t = new Thread(this::runLoop, "fable-kernel");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thr, ex) -> fail(ex));
        this.thread = t;
        t.start();
    }

    /** Unparks the writer so it drains a freshly-enqueued intent. Safe from any thread. */
    public void wake() {
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /** Stops the writer after the in-flight batch, draining nothing further; joins briefly. */
    public void shutdown() {
        running = false;
        wake();
        Thread t = thread;
        if (t != null) {
            try {
                t.join(5_000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        try {
            while (running) {
                int n = drainAndProcess();
                if (n == 0 && running) {
                    LockSupport.park(this);
                }
            }
        } catch (Throwable fatal) {
            fail(fatal);
        }
    }

    /**
     * Drains and processes one batch synchronously, publishing at most one snapshot. Returns the
     * number of intents processed (0 when both lanes were empty). Package-visible so tests can
     * drive the pipeline deterministically without the background thread.
     */
    int drainAndProcess() {
        drainBuffer.clear();
        int n = bus.drain(drainBuffer, MAX_DRAIN);
        if (n == 0) {
            return 0;
        }
        List<Effect> effects = new ArrayList<>();
        List<Intent> continuations = null;   // AM-5: re-enqueued after publish, lazily allocated
        KernelState working = state;
        long now = clock.getAsLong();
        long lastSeq = nextSeq;
        for (int i = 0; i < drainBuffer.size(); i++) {
            IntentEnvelope raw = drainBuffer.get(i);
            long seq = nextSeq++;
            lastSeq = seq;
            IntentEnvelope env = new IntentEnvelope(seq, raw.epochMillis(), raw.tick(),
                    raw.rngSeed(), raw.origin(), raw.intent());
            Class<?> type = env.intent().getClass();
            if (isTripped(type, now)) {
                effects.add(new FeedbackEffect.Rejected(seq, env.origin(), ReasonCode.BUSY, argsOf(type)));
                continue;
            }
            try {
                Reducer.Outcome outcome = reduce.apply(working, env);
                working = outcome.next();
                List<Effect> produced = outcome.effects();
                if (produced != null && !produced.isEmpty()) {
                    for (int j = 0; j < produced.size(); j++) {
                        Effect effect = produced.get(j);
                        if (effect instanceof SystemEffect.ContinuationRequested cr) {
                            // AM-5 paged-continuation control: NOT a domain effect — never journaled
                            // or fanned out. Re-enqueue its intent on the system lane after publish.
                            if (continuations == null) {
                                continuations = new ArrayList<>(2);
                            }
                            continuations.add(cr.next());
                        } else {
                            effects.add(effect);
                        }
                    }
                }
            } catch (RuntimeException reducerBug) {
                // AM-9: keep the pre-intent state, reject, log once, arm the breaker, continue.
                logger.log(Level.SEVERE,
                        "reduce failed for intent " + type.getName() + " (seq " + seq + ")",
                        reducerBug);
                recordFailure(type, now);
                effects.add(new FeedbackEffect.Rejected(seq, env.origin(), ReasonCode.INTERNAL_ERROR,
                        argsOf(type)));
            }
        }
        state = working;
        hub.publish(new KernelSnapshot(state));
        List<Effect> published = Collections.unmodifiableList(effects);
        journalSink.accept(published, lastSeq);
        fanoutSink.accept(published, lastSeq);
        // AM-5: re-enqueue continuation intents on the system lane BEHIND anything queued during
        // this batch, so interleaved intents observe consistent intermediate paged states.
        if (continuations != null) {
            for (int i = 0; i < continuations.size(); i++) {
                bus.submitSystem(continuations.get(i));
            }
        }
        return n;
    }

    /** The current authoritative state (writer-thread / test use only). */
    KernelState currentState() {
        return state;
    }

    /** The next seq the writer will assign (test use). */
    long nextSeq() {
        return nextSeq;
    }

    private boolean isTripped(Class<?> type, long now) {
        long[] rec = breaker.get(type);
        return rec != null && now < rec[1];
    }

    private void recordFailure(Class<?> type, long now) {
        long[] rec = breaker.get(type);
        if (rec == null) {
            breaker.put(type, new long[] {now, 0L});
            return;
        }
        long lastFail = rec[0];
        if (now - lastFail <= BREAKER_WINDOW_MS) {
            rec[1] = now + BREAKER_WINDOW_MS;   // trip
            logger.log(Level.SEVERE, "circuit breaker tripped for intent " + type.getName()
                    + " (repeat failure within " + BREAKER_WINDOW_MS + "ms)");
        }
        rec[0] = now;
    }

    private void fail(Throwable cause) {
        running = false;
        try {
            logger.log(Level.SEVERE, "fable-kernel writer thread died — disabling plugin", cause);
        } finally {
            onFatal.onWriterFailed(cause);
        }
    }

    private static String[] argsOf(Class<?> type) {
        return new String[] {type.getSimpleName()};
    }
}
