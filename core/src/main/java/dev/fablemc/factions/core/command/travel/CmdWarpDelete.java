package dev.fablemc.factions.core.command.travel;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f warp delete <name>} (alias {@code remove}) — deletes a faction warp
 * (ref-commands-misc.md §5.4). Officer-or-above. Submits {@link TravelIntent.DeleteWarp}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdWarpDelete extends CommandNode {

    private static final MessageKey DELETED = MessageKey.of("warp.deleted");

    CmdWarpDelete() {
        super("delete", "remove");
        setPermission("factions.cmd.setwarp");
        setRequiresPlayer(true);
        setDescription("Delete a faction warp");
        setRequiredArgs("name");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOfficerOrAbove(snap, actor))) {
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        String name = ctx.arg(0).toLowerCase(Locale.ROOT);
        CommandFlow.submit(ctx, actor,
                new TravelIntent.DeleteWarp(CommandFlow.handleOf(snap, faction), name, actor), DELETED, name);
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0 ? WarpCompletions.names(ctx) : List.of();
    }
}
