package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.entity.Player;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f invite <player>} (alias {@code inv}) — invites an online player, or routes to the
 * {@code list/revoke/accept/decline/declineall} children (ref-commands-core.md §7.23,
 * ref-commands-misc.md §3). The group's own {@code perform} is the send action; officer-or-above.
 * Submits {@link MembershipIntent.SendInvite}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdInvite extends CommandNode {

    private static final MessageKey SENT = MessageKey.of("custom.invite.sent");
    private static final MessageKey TARGET_IN_FACTION = MessageKey.of("custom.invite.target-already-in-faction");
    private static final MessageKey USAGE = MessageKey.of("general.invalid-args");

    CmdInvite() {
        super("invite", "inv");
        setPermission("factions.cmd.invite");
        setRequiresPlayer(true);
        setDescription("Invite a player to your faction");
        setOptionalArgs("player");
        addChild(new CmdInviteList());
        addChild(new CmdInviteRevoke());
        addChild(new CmdInviteAccept());
        addChild(new CmdInviteDecline());
        addChild(new CmdInviteDeclineAll());
    }

    @Override
    protected void perform(CommandContext ctx) {
        if (ctx.argCount() == 0) {
            ctx.send(USAGE, getUsage());
            return;
        }
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOfficerOrAbove(snap, actor))) {
            return;
        }
        Player target = ArgParsers.onlinePrefix(ctx.arg(0));
        if (target == null) {
            ctx.sendReason(ReasonCode.PLAYER_NOT_FOUND, ctx.arg(0));
            return;
        }
        UUID targetId = target.getUniqueId();
        if (CommandGuards.factionOf(snap, targetId) != null) {
            ctx.send(TARGET_IN_FACTION);
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        CommandFlow.submit(ctx, actor,
                new MembershipIntent.SendInvite(CommandFlow.handleOf(snap, faction), actor, targetId),
                SENT, target.getName());
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0
                ? Completions.onlinePlayers(ctx.argOrEmpty(argIndex).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
