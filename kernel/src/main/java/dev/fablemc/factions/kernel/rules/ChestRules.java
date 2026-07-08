package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.ChestTable;

/**
 * Team-chest validation (ref-services.md §7.9, ref-commands-misc.md chest). Pure static helpers.
 *
 * <p><b>Owning thread(s):</b> pure static. <b>Mutability:</b> stateless. <b>Reducer rule:</b>
 * a content commit is guarded by a session nonce so a stale open session cannot overwrite newer
 * contents (fixes the team-chest concurrent-access bug); creation is capped by
 * {@code limits.maxTeamChests} (0 = unlimited).
 */
public final class ChestRules {

    private ChestRules() {
    }

    /** Reference team-chest inventory size in slots. */
    public static final int CHEST_SIZE = 54;

    /** {@code null} when {@code faction} may create a new chest named {@code name}. */
    public static ReasonCode validateCreate(ConfigImage config, ChestTable chests, int factionOrdinal,
                                            String name) {
        if (name == null || name.trim().isEmpty()) {
            return ReasonCode.INVALID_AMOUNT;
        }
        int max = config.limits().maxTeamChests();
        if (max > 0 && chests.countForFaction(factionOrdinal) >= max
                && chests.get(factionOrdinal, name) == null) {
            return ReasonCode.WARP_LIMIT_REACHED;
        }
        return null;
    }

    /** {@code true} when {@code faction} owns a chest named {@code name}. */
    public static boolean exists(ChestTable chests, int factionOrdinal, String name) {
        return chests.get(factionOrdinal, name) != null;
    }
}
