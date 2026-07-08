package dev.fablemc.factions.kernel.config;

/**
 * Typed {@code factions.power.*} configuration (pvp-resources.md §1.3, pvp-services.md §5).
 *
 * <p><b>Owning thread(s):</b> parsed off-thread in {@code :core}, published as part of the
 * immutable snapshot; read on any thread. <b>Mutability:</b> immutable value. <b>Reducer
 * rule:</b> swapped whole via a {@code SwapConfig} intent (config is state).
 *
 * <p>Every field carries the reference default. Defaults that the reference derives are made
 * explicit here: online-regen 6.0 (= {@code regenPerSecond * 60}), offline-regen 3.0
 * (= {@code regenPerSecond * 30}), constraint max = {@code perPlayerMax}. Zone multipliers are
 * grouped in {@link ZoneMultipliers}; world multipliers are parsed name→value pairs (baked into
 * a per-{@code worldIdx} array in {@link BakedTables}).
 */
public record PowerConfig(
        double perPlayerMax,
        double regenPerSecond,
        double lossOnDeath,
        int gracePeriodSeconds,
        int tickIntervalSeconds,
        boolean gainOnKillEnabled,
        double gainOnKillAmount,
        boolean killScaleEnabled,
        double killScaleMinFactor,
        double killScaleMaxFactor,
        boolean inactiveExclusionEnabled,
        int inactiveExclusionDays,
        boolean deathStreakEnabled,
        int deathStreakWindowSeconds,
        double deathStreakMultiplier,
        boolean buyEnabled,
        double buyCostPerPoint,
        double buyMaxPerPurchase,
        boolean sourceRegenOnlineEnabled,
        boolean sourceRegenOfflineEnabled,
        boolean sourceDeathLossEnabled,
        boolean sourceKillGainEnabled,
        boolean sourceBuyEnabled,
        double sourceRegenOnlineAmount,
        double sourceRegenOfflineAmount,
        double sourceDeathLossAmount,
        double sourceKillGainAmount,
        double minPower,
        double maxPower,
        double maxChangePerEvent,
        boolean freezeBlocksAutomatic,
        boolean freezeBlocksRegen,
        boolean freezeAllowAdminBypass,
        boolean notifyActor,
        boolean notifyFaction,
        boolean notifyStaff,
        ZoneMultipliers zoneMultipliers,
        String[] worldMultiplierNames,
        double[] worldMultiplierValues) {

    /** Per-zone death/kill power multipliers ({@code factions.power.multipliers.zones.*}). */
    public record ZoneMultipliers(double safezone, double warzone, double ownClaimed,
                                  double enemyClaimed, double wilderness) {
        /** The all-1.0 reference default. */
        public static ZoneMultipliers defaults() {
            return new ZoneMultipliers(1.0, 1.0, 1.0, 1.0, 1.0);
        }
    }

    /** The complete reference-default power configuration. */
    public static PowerConfig defaults() {
        return new PowerConfig(
                10.0,   // perPlayerMax
                0.1,    // regenPerSecond
                4.0,    // lossOnDeath
                3600,   // gracePeriodSeconds
                60,     // tickIntervalSeconds
                true,   // gainOnKillEnabled
                2.0,    // gainOnKillAmount
                false,  // killScaleEnabled
                0.25,   // killScaleMinFactor
                2.0,    // killScaleMaxFactor
                false,  // inactiveExclusionEnabled
                7,      // inactiveExclusionDays
                false,  // deathStreakEnabled
                600,    // deathStreakWindowSeconds
                1.5,    // deathStreakMultiplier
                false,  // buyEnabled
                100.0,  // buyCostPerPoint
                5.0,    // buyMaxPerPurchase
                true,   // sourceRegenOnlineEnabled
                true,   // sourceRegenOfflineEnabled
                true,   // sourceDeathLossEnabled
                true,   // sourceKillGainEnabled
                true,   // sourceBuyEnabled
                6.0,    // sourceRegenOnlineAmount
                3.0,    // sourceRegenOfflineAmount
                4.0,    // sourceDeathLossAmount
                2.0,    // sourceKillGainAmount
                0.0,    // minPower
                10.0,   // maxPower
                0.0,    // maxChangePerEvent (0 = no clamp)
                true,   // freezeBlocksAutomatic
                true,   // freezeBlocksRegen
                true,   // freezeAllowAdminBypass
                true,   // notifyActor
                false,  // notifyFaction
                false,  // notifyStaff
                ZoneMultipliers.defaults(),
                new String[0],
                new double[0]);
    }
}
