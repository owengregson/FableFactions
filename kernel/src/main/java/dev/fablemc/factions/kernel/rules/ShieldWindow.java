package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.state.Faction;

/**
 * War-shield active-window computation (F6), transcribed from the reference
 * {@code FactionModel.isShieldActive()} (pvp-data.md §war-shield; pvp-commands-admin.md §2.8).
 *
 * <p><b>Owning thread(s):</b> pure static — the reducer's overclaim gate and the command layer
 * both call it. <b>Mutability:</b> stateless. <b>Reducer rule:</b> the kernel reads time only via
 * the envelope's {@code epochMillis}, so {@link #utcHour} derives the UTC hour deterministically
 * (no wall clock).
 *
 * <p>The window is {@code [start:00 UTC, start+duration)} on a rolling 24-hour clock: inactive
 * when {@code duration <= 0} or the start hour is unset; otherwise active iff the current UTC
 * hour equals {@code (base + i) % 24} for some {@code i} in {@code [0, duration)} (wraps midnight).
 */
public final class ShieldWindow {

    private ShieldWindow() {
    }

    private static final long MS_PER_HOUR = 3_600_000L;

    /** The UTC hour-of-day (0..23) for an epoch-millis instant. */
    public static int utcHour(long epochMillis) {
        long h = Math.floorMod(epochMillis / MS_PER_HOUR, 24L);
        return (int) h;
    }

    /** {@code true} when a shield {@code (startHour, durationHours)} is active at {@code epochMillis}. */
    public static boolean isActive(int startHour, int durationHours, long epochMillis) {
        if (durationHours <= 0 || startHour == Faction.NO_SHIELD) {
            return false;
        }
        if (durationHours >= 24) {
            return true;
        }
        int base = Math.floorMod(startHour, 24);
        int current = utcHour(epochMillis);
        for (int i = 0; i < durationHours; i++) {
            if ((base + i) % 24 == current) {
                return true;
            }
        }
        return false;
    }

    /** {@code true} when {@code faction}'s shield is active at {@code epochMillis}. */
    public static boolean isActive(Faction faction, long epochMillis) {
        return faction != null
                && isActive(faction.shieldStartHour(), faction.shieldDurationHours(), epochMillis);
    }
}
