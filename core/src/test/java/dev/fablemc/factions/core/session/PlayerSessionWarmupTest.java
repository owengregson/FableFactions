package dev.fablemc.factions.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * Warmup lifecycle on the confined {@link PlayerSession}: the arm / complete / abort split that keeps
 * a paid-warp refund exactly-once (findings #14/#27/#36/#59). Headless — a session is opened with a
 * {@code null} player handle and a hand-rolled {@link TaskHandle}; the abort refund is a counter.
 */
class PlayerSessionWarmupTest {

    /** A minimal task handle recording cancellation — no live scheduler needed. */
    private static final class FakeHandle implements TaskHandle {
        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }
    }

    private static PlayerSession session() {
        return new SessionRegistry().open(UUID.randomUUID(), null);
    }

    @Test
    void completeCancelsTheTaskButNeverRefunds() {
        PlayerSession s = session();
        FakeHandle handle = new FakeHandle();
        AtomicInteger refunds = new AtomicInteger();

        s.armWarmup(handle, refunds::incrementAndGet);
        assertSame(handle, s.warmupTask());

        s.completeWarmup();

        assertNull(s.warmupTask());
        assertTrue(handle.cancelled());
        assertEquals(0, refunds.get(), "a completed teleport must not refund");
    }

    @Test
    void abortCancelsTheTaskAndRefundsOnce() {
        PlayerSession s = session();
        FakeHandle handle = new FakeHandle();
        AtomicInteger refunds = new AtomicInteger();

        s.armWarmup(handle, refunds::incrementAndGet);
        s.abortWarmup();

        assertNull(s.warmupTask());
        assertTrue(handle.cancelled());
        assertEquals(1, refunds.get(), "teardown during a paid warmup must refund");
    }

    @Test
    void aRetireAndTeardownFiringTogetherRefundAtMostOnce() {
        PlayerSession s = session();
        AtomicInteger refunds = new AtomicInteger();

        s.armWarmup(new FakeHandle(), refunds::incrementAndGet);
        s.abortWarmup();
        s.abortWarmup();

        assertEquals(1, refunds.get());
    }

    @Test
    void completeThenTeardownDoesNotRefund() {
        PlayerSession s = session();
        AtomicInteger refunds = new AtomicInteger();

        s.armWarmup(new FakeHandle(), refunds::incrementAndGet);
        s.completeWarmup();   // teleport reached / saga already settled the money
        s.abortWarmup();      // a later teardown must find nothing to refund

        assertEquals(0, refunds.get());
    }

    @Test
    void closeAbortsAnInFlightPaidWarmup() {
        SessionRegistry registry = new SessionRegistry();
        UUID id = UUID.randomUUID();
        PlayerSession s = registry.open(id, null);
        AtomicInteger refunds = new AtomicInteger();

        s.armWarmup(new FakeHandle(), refunds::incrementAndGet);
        registry.close(id);   // logout / kick teardown routes through onClose → abortWarmup

        assertEquals(1, refunds.get());
    }

    @Test
    void anUnpaidWarmupAbortsWithoutAnAbortHook() {
        PlayerSession s = session();
        FakeHandle handle = new FakeHandle();

        s.armWarmup(handle, null);   // a /f home warmup has no cost to refund
        s.abortWarmup();             // must not throw

        assertNull(s.warmupTask());
        assertTrue(handle.cancelled());
    }
}
