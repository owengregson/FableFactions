package dev.fablemc.factions.core.command.travel;

import java.util.List;
import java.util.Locale;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.state.Faction;

/**
 * Shared warp-name tab-completion for the {@code /f warp} children (delete / password / cost).
 *
 * <p><b>Owning thread(s):</b> pure static, called from tab-completion on the player's region/main
 * thread. <b>Mutability:</b> stateless.
 */
final class WarpCompletions {

    private WarpCompletions() {
    }

    /** The caller faction's warp names matching the first argument's prefix (empty for non-players). */
    static List<String> names(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            return List.of();
        }
        Faction faction = CommandGuards.factionOf(ctx.snap(), ctx.player().getUniqueId());
        if (faction == null) {
            return List.of();
        }
        return Completions.warpNames(ctx.snap(), CommandFlow.handleOf(ctx.snap(), faction),
                ctx.argOrEmpty(0).toLowerCase(Locale.ROOT));
    }
}
