package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.config.PowerConfig;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.PlayerLedger;

/**
 * Derived faction aggregates (member count, total power, any-member-online) computed by scanning
 * the ledger. These mirror the reference {@code getFactionPower}/{@code countMembers} scans
 * (ref-services.md §5.4/§6.2).
 *
 * <p><b>Owning thread(s):</b> pure static; the reducer's claim / power / membership branches use
 * them. <b>Mutability:</b> stateless. <b>Reducer rule:</b> the {@link Faction} record carries a
 * {@code landCount} field (incrementally maintained), but has no {@code memberCount} field, so
 * member counts and total power are recomputed from the ledger here (the aggregate==recompute
 * property test pins land; power is recomputed on demand for cap/raidable decisions).
 *
 * <p>Total power = faction {@code powerBoost} + Σ settled member power, excluding members past the
 * inactivity horizon when {@code inactiveExclusionEnabled}.
 */
public final class FactionAggregates {

    private FactionAggregates() {
    }

    private static final long MS_PER_DAY = 24L * 3600L * 1000L;

    /** Number of live members whose faction handle resolves to {@code factionHandle}'s ordinal. */
    public static int memberCount(KernelState state, int factionHandle) {
        int ord = FactionHandle.ordinal(factionHandle);
        PlayerLedger ledger = state.ledger();
        int hw = ledger.highWater();
        int n = 0;
        for (int i = 0; i < hw; i++) {
            if (ledger.has(i) && FactionHandle.ordinal(ledger.factionHandle(i)) == ord) {
                n++;
            }
        }
        return n;
    }

    /** {@code true} when {@code member} is excluded from power totals by inactivity. */
    public static boolean excludedByInactivity(PowerConfig pc, long lastActivity, long nowMillis) {
        if (!pc.inactiveExclusionEnabled()) {
            return false;
        }
        long horizon = (long) pc.inactiveExclusionDays() * MS_PER_DAY;
        return lastActivity > 0 && (nowMillis - lastActivity) > horizon;
    }

    /**
     * Total faction power at {@code tick}: {@code powerBoost} plus each active member's settled
     * power. Uses {@code nowMillis} for the inactivity horizon.
     */
    public static double totalPower(KernelState state, Faction faction, int tick, long nowMillis) {
        if (faction == null) {
            return 0.0;
        }
        int ord = faction.idx();
        PowerConfig pc = state.config().power();
        PlayerLedger ledger = state.ledger();
        boolean online;
        int hw = ledger.highWater();
        double total = faction.powerBoost();
        for (int i = 0; i < hw; i++) {
            if (!ledger.has(i) || FactionHandle.ordinal(ledger.factionHandle(i)) != ord) {
                continue;
            }
            if (excludedByInactivity(pc, ledger.lastActivity(i), nowMillis)) {
                continue;
            }
            online = state.online().contains(i);
            total += PowerMath.settle(pc, online, ledger.powerBase(i), ledger.powerAsOfTick(i),
                    ledger.powerFrozen(i), tick);
        }
        return total;
    }

    /** {@code true} when at least one member of {@code factionHandle} is in the online set. */
    public static boolean anyMemberOnline(KernelState state, int factionHandle) {
        int ord = FactionHandle.ordinal(factionHandle);
        PlayerLedger ledger = state.ledger();
        int hw = ledger.highWater();
        for (int i = 0; i < hw; i++) {
            if (ledger.has(i) && FactionHandle.ordinal(ledger.factionHandle(i)) == ord
                    && state.online().contains(i)) {
                return true;
            }
        }
        return false;
    }
}
