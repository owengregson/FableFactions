package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f invite revoke <player>} — cancels a pending invite (ref-commands-misc.md §3.7).
 * Officer-or-above; submits {@link MembershipIntent.RevokeInvite}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdInviteRevoke extends CommandNode {

    private static final MessageKey REVOKED = MessageKey.of("invite.revoked");

    CmdInviteRevoke() {
        super("revoke");
        setPermission("factions.cmd.invite.revoke");
        setRequiresPlayer(true);
        setDescription("Revoke a pending invite");
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
                new MembershipIntent.RevokeInvite(CommandFlow.handleOf(snap, faction), actor, target),
                REVOKED, ctx.arg(0));
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0
                ? Completions.onlinePlayers(ctx.argOrEmpty(0).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
