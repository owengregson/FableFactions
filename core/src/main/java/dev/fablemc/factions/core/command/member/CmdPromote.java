package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.intent.RoleIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f promote <player>} — promotes a member one rank (ref-commands-core.md §7.5).
 * Officer-or-above; the offline-name resolution mirrors the reference (a typo yields a bogus UUID
 * whose reducer lookup simply fails). Submits {@link RoleIntent.PromoteMember}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdPromote extends CommandNode {

    private static final MessageKey PROMOTED = MessageKey.of("custom.member.promoted");

    CmdPromote() {
        super("promote");
        setPermission("factions.cmd.promote");
        setRequiresPlayer(true);
        setDescription("Promote a member");
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
                new RoleIntent.PromoteMember(CommandFlow.handleOf(snap, faction), actor, target),
                PROMOTED, ctx.arg(0));
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0 ? memberCompletions(ctx) : List.of();
    }

    static List<String> memberCompletions(CommandContext ctx) {
        Faction faction = ctx.isPlayer()
                ? CommandGuards.factionOf(ctx.snap(), ctx.player().getUniqueId()) : null;
        if (faction == null) {
            return List.of();
        }
        return Completions.memberNames(ctx.snap(), CommandFlow.handleOf(ctx.snap(), faction),
                ctx.argOrEmpty(0).toLowerCase(Locale.ROOT));
    }
}
