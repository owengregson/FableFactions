package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.entity.Player;

import dev.fablemc.factions.api.event.FactionLeaveEvent;
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
 * {@code /f kick <player>} — removes an online member from the caller's faction
 * (ref-commands-core.md §7.4). Officer-or-above; cannot kick yourself or the owner. Fires the
 * cancellable {@link FactionLeaveEvent} ({@code kicked=true}) then submits
 * {@link MembershipIntent.KickMember}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdKick extends CommandNode {

    private static final MessageKey KICKED_ACTOR = MessageKey.of("custom.member.kick-actor");

    CmdKick() {
        super("kick");
        setPermission("factions.cmd.kick");
        setRequiresPlayer(true);
        setDescription("Kick a member from your faction");
        setRequiredArgs("player");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOfficerOrAbove(snap, actor))) {
            return;
        }
        Player target = ArgParsers.onlineExact(ctx.arg(0));
        if (target == null) {
            ctx.sendReason(ReasonCode.PLAYER_NOT_FOUND, ctx.arg(0));
            return;
        }
        UUID targetId = target.getUniqueId();
        if (actor.equals(targetId)) {
            ctx.sendReason(ReasonCode.CANNOT_KICK_SELF);
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        if (targetId.equals(faction.ownerId())) {
            ctx.sendReason(ReasonCode.CANNOT_KICK_LEADER);
            return;
        }
        if (CommandFlow.fireCancelled(new FactionLeaveEvent(faction.id(), targetId, true))) {
            return;
        }
        CommandFlow.submit(ctx, actor,
                new MembershipIntent.KickMember(CommandFlow.handleOf(snap, faction), actor, targetId),
                KICKED_ACTOR, target.getName());
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0
                ? Completions.onlinePlayers(ctx.argOrEmpty(0).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
