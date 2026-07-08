package dev.fablemc.factions.core.command.travel;

import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f home} — teleports the caller to their faction home (ref-commands-core.md §7.12). The
 * command only pre-checks faction membership; the {@link dev.fablemc.factions.core.session.Travel}
 * seam owns the jail gate, the no-home case, the warmup countdown and the teleport itself, reporting
 * every outcome through the message layer.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdHome extends CommandNode {

    CmdHome() {
        super("home");
        setPermission("factions.cmd.home");
        setRequiresPlayer(true);
        setDescription("Teleport to your faction home");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireFaction(snap, actor))) {
            return;
        }
        ctx.services().travel().beginHome(ctx.player(), snap);
    }
}
