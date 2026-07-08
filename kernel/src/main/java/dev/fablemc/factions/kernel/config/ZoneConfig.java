package dev.fablemc.factions.kernel.config;

/**
 * Typed {@code factions.zones.*} configuration.
 *
 * <p><b>Owning thread(s):</b> parsed in {@code :core}, read on any thread. <b>Mutability:</b>
 * immutable value. <b>Reducer rule:</b> swapped whole via {@code SwapConfig}.
 *
 * <p>When a zone system is disabled, its chunks are treated as wilderness by {@code Verdicts}
 * and power math (pvp-resources.md §1.11).
 */
public record ZoneConfig(boolean safeZoneEnabled, boolean warZoneEnabled) {

    /** The reference-default zone configuration (both enabled). */
    public static ZoneConfig defaults() {
        return new ZoneConfig(true, true);
    }
}
