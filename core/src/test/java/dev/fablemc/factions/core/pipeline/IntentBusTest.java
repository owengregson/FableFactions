package dev.fablemc.factions.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.intent.PowerIntent;
import dev.fablemc.factions.kernel.intent.EconomyIntent;

/**
 * The MPSC bus contract (work order W2b §3): the bounded player lane answers
 * {@link SubmitResult#REJECTED_BUSY} at capacity (the game thread never blocks), and coalescing
 * keeps at most one pending {@code PowerTick}/{@code TaxSweep} — the newest tick wins (proposal-C
 * §3.5, AM-9).
 */
final class IntentBusTest {

    private IntentBus bus(int capacity) {
        return new IntentBus(capacity, () -> 1000L, () -> 7, () -> { });
    }

    @Test
    void boundedPlayerLaneRejectsAtCapacity() {
        IntentBus bus = bus(2);
        Origin o = Origin.player(new UUID(1, 1));
        assertEquals(SubmitResult.ACCEPTED, bus.submit(new PrefIntent.SetFly(o.actor(), true), o));
        assertEquals(SubmitResult.ACCEPTED, bus.submit(new PrefIntent.SetFly(o.actor(), false), o));
        // Third submit exceeds capacity → busy (never blocks the caller).
        assertEquals(SubmitResult.REJECTED_BUSY, bus.submit(new PrefIntent.SetFly(o.actor(), true), o));

        // Draining frees the lane so a later submit is accepted again.
        List<IntentEnvelope> out = new ArrayList<>();
        assertEquals(2, bus.drain(out, 16));
        assertEquals(SubmitResult.ACCEPTED, bus.submit(new PrefIntent.SetFly(o.actor(), true), o));
    }

    @Test
    void shutdownRejectsNewPlayerIntents() {
        IntentBus bus = bus(8);
        Origin o = Origin.player(new UUID(2, 2));
        bus.beginShutdown();
        assertTrue(bus.isShuttingDown());
        assertEquals(SubmitResult.REJECTED_SHUTDOWN, bus.submit(new PrefIntent.SetFly(o.actor(), true), o));
    }

    @Test
    void powerTickCoalescesToNewestPending() {
        IntentBus bus = bus(8);
        bus.submitSystem(new PowerIntent.PowerTick(1));
        bus.submitSystem(new PowerIntent.PowerTick(2));
        bus.submitSystem(new PowerIntent.PowerTick(3));
        // A stale (older) tick submitted after a newer one is dropped, not enqueued.
        bus.submitSystem(new PowerIntent.PowerTick(2));

        assertEquals(1, bus.depth(), "at most one PowerTick may be pending");

        List<IntentEnvelope> out = new ArrayList<>();
        int drained = bus.drain(out, 16);
        assertEquals(1, drained, "exactly one coalesced PowerTick is drained");
        Intent i = out.get(0).intent();
        assertTrue(i instanceof PowerIntent.PowerTick, "the drained intent is a PowerTick");
        assertEquals(3, ((PowerIntent.PowerTick) i).tick(), "the newest tick wins");
    }

    @Test
    void taxSweepCoalescesIndependentlyOfPowerTick() {
        IntentBus bus = bus(8);
        bus.submitSystem(new EconomyIntent.TaxSweep(5));
        bus.submitSystem(new EconomyIntent.TaxSweep(9));
        bus.submitSystem(new PowerIntent.PowerTick(4));

        List<IntentEnvelope> out = new ArrayList<>();
        int drained = bus.drain(out, 16);
        assertEquals(2, drained, "one TaxSweep + one PowerTick");

        boolean sawTax9 = false;
        boolean sawPower4 = false;
        for (IntentEnvelope env : out) {
            if (env.intent() instanceof EconomyIntent.TaxSweep ts && ts.tick() == 9) {
                sawTax9 = true;
            }
            if (env.intent() instanceof PowerIntent.PowerTick pt && pt.tick() == 4) {
                sawPower4 = true;
            }
        }
        assertTrue(sawTax9, "the newest TaxSweep survives");
        assertTrue(sawPower4, "the PowerTick is independent of TaxSweep coalescing");
    }

    @Test
    void systemLaneIsUnbounded() {
        IntentBus bus = bus(1);   // tiny player capacity must not constrain the system lane
        for (int i = 0; i < 100; i++) {
            bus.submitSystem(new SystemIntent.RetagPage(i));
        }
        List<IntentEnvelope> out = new ArrayList<>();
        assertEquals(100, bus.drain(out, 1000));
        // Ordering is FIFO on the system lane.
        assertSame(SystemIntent.RetagPage.class, out.get(0).intent().getClass());
        assertEquals(0, ((SystemIntent.RetagPage) out.get(0).intent()).cursor());
        assertEquals(99, ((SystemIntent.RetagPage) out.get(99).intent()).cursor());
    }
}
