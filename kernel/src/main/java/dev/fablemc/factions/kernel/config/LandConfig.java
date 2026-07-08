package dev.fablemc.factions.kernel.config;

/**
 * Typed land / claiming / overclaim / raidable / war-shield configuration
 * ({@code factions.land.*}, {@code overclaiming.*}, {@code raidable.*}, {@code war.shield.*}).
 *
 * <p><b>Owning thread(s):</b> parsed in {@code :core}, read on any thread. <b>Mutability:</b>
 * immutable value. <b>Reducer rule:</b> swapped whole via {@code SwapConfig}.
 *
 * <p>Max land = {@code min(land.max, floor(totalMemberPower * land.per-power))} (pvp-services.md
 * §6.2). {@code bufferZone} is a reference-dead knob wired here (D-1) with a behavior-preserving
 * default of 0. Shield windows are UTC hours; {@code shieldMaxDurationHours} caps a single
 * {@code /fa shield} assignment.
 */
public record LandConfig(
        int bufferZone,
        int maxPerCommand,
        double perPower,
        int maxLand,
        boolean overclaimingEnabled,
        boolean overclaimRequireEnemyRelation,
        boolean offlineProtectionEnabled,
        boolean raidableBroadcastEnabled,
        boolean raidableBroadcastServerWide,
        boolean warShieldEnabled,
        int warShieldMaxDurationHours) {

    /** The complete reference-default land configuration. */
    public static LandConfig defaults() {
        return new LandConfig(
                0,      // bufferZone (D-1: wired, default 0 = disabled)
                200,    // maxPerCommand
                1.0,    // perPower
                500,    // maxLand
                false,  // overclaimingEnabled
                true,   // overclaimRequireEnemyRelation
                false,  // offlineProtectionEnabled (F5)
                true,   // raidableBroadcastEnabled (F4)
                false,  // raidableBroadcastServerWide
                false,  // warShieldEnabled (F6)
                8);     // warShieldMaxDurationHours
    }
}
