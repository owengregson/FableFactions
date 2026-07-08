package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.kernel.intent.RoleIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f demote <player>} — demotes a member one rank (ref-commands-core.md §7.6).
 * Officer-or-above; offline-name resolution mirrors the reference. Submits
 * {@link RoleIntent.DemoteMember}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdDemote extends CommandNode {

    private static final MessageKey DEMOTED = MessageKey.of("custom.member.demoted");

    CmdDemote() {
        super("demote");
        setPermission("factions.cmd.demote");
        setRequiresPlayer(true);
        setDescription("Demote a member");
        setRequiredArgs("player");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOfficerOrAbove(snap, actor))) {
            return;
        }
        UUID target = ArgParsers.offlineId(ctx.arg(0));
        Faction faction = CommandGuards.factionOf(snap, actor);
        CommandFlow.submit(ctx, actor,
                new RoleIntent.DemoteMember(CommandFlow.handleOf(snap, faction), actor, target),
                DEMOTED, ctx.arg(0));
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0 ? CmdPromote.memberCompletions(ctx) : List.of();
    }
}
