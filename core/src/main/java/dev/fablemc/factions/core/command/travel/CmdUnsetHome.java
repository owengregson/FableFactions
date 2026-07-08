package dev.fablemc.factions.core.command.travel;

import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f unsethome [confirm]} — clears the faction home (ref-commands-core.md §7.14). Shares the
 * {@code factions.cmd.sethome} node and requires officer-or-above; the literal {@code confirm} token
 * is required. Submits {@link TravelIntent.UnsetHome}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdUnsetHome extends CommandNode {

    private static final String CONFIRM_TOKEN = "confirm";
    private static final MessageKey CONFIRM = MessageKey.of("custom.home.unset-confirm");
    private static final MessageKey UNSET = MessageKey.of("custom.home.unset");

    CmdUnsetHome() {
        super("unsethome");
        setPermission("factions.cmd.sethome");
        setRequiresPlayer(true);
        setDescription("Remove your faction home");
        setOptionalArgs("confirm");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOfficerOrAbove(snap, actor))) {
            return;
        }
        if (!ctx.argOrEmpty(0).equalsIgnoreCase(CONFIRM_TOKEN)) {
            ctx.send(CONFIRM);
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        CommandFlow.submit(ctx, actor,
                new TravelIntent.UnsetHome(CommandFlow.handleOf(snap, faction), actor), UNSET);
    }
}
