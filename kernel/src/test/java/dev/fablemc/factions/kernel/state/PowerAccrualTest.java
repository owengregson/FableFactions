package dev.fablemc.factions.kernel.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.config.PowerConfig;

/**
 * The lazy-accrual clamp-equivalence property (proposal-C §4.5): settling power with the single
 * closed-form clamp {@code KernelSnapshot.powerAt} must equal iterating the per-tick clamp for a
 * constant regen rate over a base within {@code [min, max]}.
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b>
 * test-confined fixtures; no shared state between tests.
 */
class PowerAccrualTest {

    private static KernelSnapshot snapshotWith(ConfigImage cfg, boolean online, double base,
                                               long asOfTick, boolean frozen) {
        PlayerLedger ledger = PlayerLedger.empty()
                .withNewMember(0, UUID.randomUUID(), "P")
                .withPower(0, base, asOfTick)
                .withPowerFrozen(0, frozen);
        OnlineSet set = online ? OnlineSet.empty().with(0) : OnlineSet.empty();
        KernelState st = KernelState.empty(cfg).withLedger(ledger).withOnline(set);
        return new KernelSnapshot(st);
    }

    /**
     * The per-SERVER-TICK regen rate (the configured amount is per power-tick interval; dt is in
     * server ticks). Mirror of {@code PowerMath.perTickRegen} — finding #5: without the division the
     * accrual was ~{@code tickIntervalSeconds*20}× too fast.
     */
    private static double perTick(PowerConfig pc, boolean online) {
        double perInterval = online ? pc.sourceRegenOnlineAmount() : pc.sourceRegenOfflineAmount();
        return perInterval / (Math.max(1, pc.tickIntervalSeconds()) * 20.0);
    }

    /** Iterated per-tick clamp reference. */
    private static double iterate(double base, double rate, double min, double max, int ticks) {
        double p = base;
        for (int i = 0; i < ticks; i++) {
            p = Math.min(max, Math.max(min, p + rate));
        }
        return p;
    }

    @Test
    void onlineAccrualEqualsIteratedClamp() {
        ConfigImage cfg = ConfigImage.defaults();
        PowerConfig pc = cfg.power();
        double rate = perTick(pc, true);            // 6.0 / (60*20) = 0.005 per server tick
        double min = pc.minPower();                 // 0.0
        double max = pc.maxPower();                 // 10.0

        KernelSnapshot snap = snapshotWith(cfg, true, 0.0, 0L, false);
        // Cross the linear region AND the max clamp: at 0.005/tick, 0→10 takes 2000 ticks (100s).
        for (int tick : new int[] {0, 1, 500, 1200, 2000, 2400, 3000}) {
            double expected = iterate(0.0, rate, min, max, tick);
            assertEquals(expected, snap.powerAt(0, tick), 1e-9, "online tick=" + tick);
        }
        // 6.0 power accrues over exactly one power-tick interval (1200 server ticks), matching the
        // reference's per-interval regen — NOT per server tick.
        assertEquals(6.0, snap.powerAt(0, 1200), 1e-9, "one interval = configured amount");
    }

    @Test
    void offlineAccrualEqualsIteratedClampWithLongerLinearRegion() {
        ConfigImage cfg = ConfigImage.defaults();
        PowerConfig pc = cfg.power();
        double rate = perTick(pc, false);           // 3.0 / (60*20) = 0.0025 per server tick
        double min = pc.minPower();
        double max = pc.maxPower();

        KernelSnapshot snap = snapshotWith(cfg, false, 0.0, 0L, false);
        for (int tick : new int[] {0, 1, 500, 1200, 2400, 4000, 5000}) {
            double expected = iterate(0.0, rate, min, max, tick);
            assertEquals(expected, snap.powerAt(0, tick), 1e-9, "offline tick=" + tick);
        }
        assertEquals(3.0, snap.powerAt(0, 1200), 1e-9, "one interval = configured offline amount");
    }

    @Test
    void accrualFromNonZeroBaseAndOffsetTick() {
        ConfigImage cfg = ConfigImage.defaults();
        PowerConfig pc = cfg.power();
        double rate = perTick(pc, true);
        double min = pc.minPower();
        double max = pc.maxPower();

        double base = 2.5;
        long asOf = 100L;
        KernelSnapshot snap = snapshotWith(cfg, true, base, asOf, false);
        for (int dt : new int[] {0, 1, 400, 1200, 1600, 2000}) {
            double expected = iterate(base, rate, min, max, dt);
            assertEquals(expected, snap.powerAt(0, (int) asOf + dt), 1e-9, "dt=" + dt);
        }
        // A tick before the settlement point does not regress below the base.
        assertEquals(base, snap.powerAt(0, (int) asOf - 5), 1e-9);
    }

    @Test
    void frozenMemberDoesNotAccrue() {
        ConfigImage cfg = ConfigImage.defaults();
        double base = 4.0;
        KernelSnapshot snap = snapshotWith(cfg, true, base, 0L, true);
        for (int tick = 0; tick <= 5; tick++) {
            assertEquals(base, snap.powerAt(0, tick), 1e-9, "frozen holds at base, tick=" + tick);
        }
    }

    @Test
    void absentMemberReadsZero() {
        KernelSnapshot snap = new KernelSnapshot(KernelState.empty());
        assertEquals(0.0, snap.powerAt(999, 10), 1e-9);
    }
}
