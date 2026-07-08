package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.WarpTable;

/**
 * Home / warp validation (pvp-services.md §7.3, pvp-commands-misc.md travel). Pure static helpers.
 *
 * <p><b>Owning thread(s):</b> pure static. <b>Mutability:</b> stateless. <b>Reducer rule:</b>
 * a NEW warp is capped by {@code limits.maxWarps}; the per-use cost is clamped {@code >= 0} at
 * intake; passwords are set/cleared verbatim.
 */
public final class TravelRules {

    private TravelRules() {
    }

    /** {@code null} when {@code faction} may set (create/update) warp {@code name}. */
    public static ReasonCode validateSetWarp(ConfigImage cfg, WarpTable warps, int factionOrdinal,
                                             String name) {
        if (name == null || name.trim().isEmpty()) {
            return ReasonCode.WARP_NOT_FOUND;
        }
        boolean isNew = warps.get(factionOrdinal, name) == null;
        int max = cfg.limits().maxWarps();
        if (isNew && max > 0 && warps.countForFaction(factionOrdinal) >= max) {
            return ReasonCode.WARP_LIMIT_REACHED;
        }
        return null;
    }

    /** {@code null} when {@code faction} owns warp {@code name} (delete / password / cost target). */
    public static ReasonCode requireWarp(WarpTable warps, int factionOrdinal, String name) {
        return warps.get(factionOrdinal, name) == null ? ReasonCode.WARP_NOT_FOUND : null;
    }

    /** Clamps a warp use-cost to {@code >= 0} (reference {@code max(0, cost)}). */
    public static double clampCost(double cost) {
        return Math.max(0.0, cost);
    }
}
