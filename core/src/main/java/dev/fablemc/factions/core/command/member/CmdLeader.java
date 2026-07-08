package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f leader <player> [confirm]} — transfers faction ownership (ref-commands-core.md §7.7).
 * Owner-only; a self-transfer requires the literal {@code confirm} token. Submits
 * {@link LifecycleIntent.TransferOwnership}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdLeader extends CommandNode {

    private static final String CONFIRM_TOKEN = "confirm";
    private static final MessageKey CONFIRM_SELF = MessageKey.of("custom.member.leader-confirm-self");
    private static final MessageKey TRANSFERRED = MessageKey.of("custom.member.leader-transferred");

    CmdLeader() {
        super("leader");
        setPermission("factions.cmd.leader");
        setRequiresPlayer(true);
        setDescription("Transfer faction ownership");
        setRequiredArgs("player");
        setOptionalArgs("confirm");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOwner(snap, actor))) {
            return;
        }
        UUID target = ArgParsers.offlineId(ctx.arg(0));
        if (actor.equals(target) && !ctx.argOrEmpty(1).equalsIgnoreCase(CONFIRM_TOKEN)) {
            ctx.send(CONFIRM_SELF, ctx.arg(0));
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        CommandFlow.submit(ctx, actor,
                new LifecycleIntent.TransferOwnership(CommandFlow.handleOf(snap, faction), target, actor),
                TRANSFERRED, ctx.arg(0));
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0 ? CmdPromote.memberCompletions(ctx) : List.of();
    }
}
