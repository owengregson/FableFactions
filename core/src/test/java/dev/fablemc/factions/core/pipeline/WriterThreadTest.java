package dev.fablemc.factions.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.reduce.Reducer;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.KernelState;

/**
 * The writer's failure boundary + AM-5 continuation contract (work order W2b §3). Exercised
 * synchronously via the package-private {@code drainAndProcess} seam and an injected
 * {@link ReduceStep} stub, so the tests are deterministic without the background thread.
 */
final class WriterThreadTest {

    private static final Logger QUIET = quietLogger();

    private static Logger quietLogger() {
        Logger l = Logger.getAnonymousLogger();
        l.setLevel(Level.OFF);   // the failure-boundary path logs SEVERE by design; keep tests silent
        return l;
    }

    /** Collects everything handed to a sink so tests can assert the journaled/fanned-out batch. */
    private static final class Capture implements EffectSink {
        final List<Effect> effects = new ArrayList<>();
        long lastSeq = Long.MIN_VALUE;

        @Override
        public void accept(List<Effect> batch, long seq) {
            effects.addAll(batch);
            lastSeq = seq;
        }
    }

    private static IntentBus newBus() {
        return new IntentBus(1024, () -> 0L, () -> 0, () -> { });
    }

    @Test
    void throwingReducerRejectsAndTripsBreakerOnRepeat() {
        KernelState initial = KernelState.empty();
        IntentBus bus = newBus();
        SnapshotHub hub = new SnapshotHub(new KernelSnapshot(initial));
        Capture journal = new Capture();
        Capture fanout = new Capture();
        AtomicInteger reduceCalls = new AtomicInteger();

        ReduceStep throwing = (state, env) -> {
            reduceCalls.incrementAndGet();
            throw new IllegalStateException("kernel bug");
        };
        // Fixed clock: all three intents share one breaker window, so the repeat trips it.
        WriterThread writer = new WriterThread(bus, hub, initial, 0L, throwing, journal, fanout,
                FailureHandler.IGNORE, () -> 5_000L, QUIET);

        UUID actor = new UUID(1, 1);
        Origin o = Origin.player(actor);
        bus.submit(new PrefIntent.SetFly(actor, true), o);
        bus.submit(new PrefIntent.SetFly(actor, false), o);
        bus.submit(new PrefIntent.SetFly(actor, true), o);

        int processed = writer.drainAndProcess();
        assertEquals(3, processed, "all three intents are processed — the pipeline continues");

        // Two reductions threw; the third was short-circuited by the tripped breaker (no reduce call).
        assertEquals(2, reduceCalls.get(), "the breaker rejects the repeat before reducing");
        assertEquals(3, journal.effects.size());
        assertEquals(ReasonCode.INTERNAL_ERROR, reason(journal.effects.get(0)));
        assertEquals(ReasonCode.INTERNAL_ERROR, reason(journal.effects.get(1)));
        assertEquals(ReasonCode.BUSY, reason(journal.effects.get(2)));

        // Journal and fanout receive the identical batch, and state stays at the pre-intent value.
        assertEquals(3, fanout.effects.size());
        assertSame(initial, writer.currentState(), "a throwing reduce leaves state unchanged (AM-9)");
        assertSame(initial, hub.current().state());
    }

    @Test
    void continuationEffectIsReEnqueuedAndNotJournaled() {
        KernelState initial = KernelState.empty();
        IntentBus bus = newBus();
        SnapshotHub hub = new SnapshotHub(new KernelSnapshot(initial));
        Capture journal = new Capture();
        Capture fanout = new Capture();

        Intent nextPage = new EconomyIntent.TaxSweepPage(9, 1024);
        // Stub emits one domain effect + one ContinuationRequested (the AM-5 paging control effect).
        ReduceStep paging = (state, env) -> {
            List<Effect> out = new ArrayList<>();
            out.add(new ClaimEffect.ClaimRemoved(env.seq(), env.origin(), 0, 123L, -1));
            out.add(new SystemEffect.ContinuationRequested(env.seq(), env.origin(), nextPage));
            return new Reducer.Outcome(state, out);
        };
        WriterThread writer = new WriterThread(bus, hub, initial, 0L, paging, journal, fanout,
                FailureHandler.IGNORE, () -> 0L, QUIET);

        bus.submitSystem(new EconomyIntent.TaxSweep(9));   // seeds one intent to reduce
        writer.drainAndProcess();

        // The control effect is stripped: only the domain effect reaches journal + fanout.
        assertEquals(1, journal.effects.size(), "ContinuationRequested must not be journaled");
        assertTrue(journal.effects.get(0) instanceof ClaimEffect.ClaimRemoved);
        assertEquals(1, fanout.effects.size());
        assertTrue(fanout.effects.get(0) instanceof ClaimEffect.ClaimRemoved);

        // The continuation intent is re-enqueued on the system lane for the next drain.
        List<IntentEnvelope> out = new ArrayList<>();
        assertEquals(1, bus.drain(out, 16), "the continuation is re-enqueued");
        assertSame(nextPage, out.get(0).intent());
    }

    private static ReasonCode reason(Effect e) {
        return ((FeedbackEffect.Rejected) e).reason();
    }
}
