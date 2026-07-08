package dev.fablemc.factions.core.command.member;

import java.util.UUID;

import dev.fablemc.factions.api.event.FactionLeaveEvent;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.Rank;

/**
 * {@code /f leave} — removes the caller from their faction (ref-commands-core.md §7.3). The owner
 * cannot leave (transfer or disband first). Fires the cancellable {@link FactionLeaveEvent} then
 * submits {@link MembershipIntent.LeaveFaction}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdLeave extends CommandNode {

    private static final MessageKey LEFT = MessageKey.of("member.left");
    private static final MessageKey OWNER_CANNOT_LEAVE = MessageKey.of("custom.member.owner-cannot-leave");

    CmdLeave() {
        super("leave");
        setPermission("factions.cmd.leave");
        setRequiresPlayer(true);
        setDescription("Leave your faction");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        Faction faction = CommandGuards.factionOf(snap, actor);
        if (faction == null) {
            ctx.sendReason(ReasonCode.NOT_IN_FACTION);
            return;
        }
        Rank rank = CommandGuards.rankOf(snap, actor);
        if (rank != null && rank.isOwner()) {
            ctx.send(OWNER_CANNOT_LEAVE);
            return;
        }
        if (CommandFlow.fireCancelled(new FactionLeaveEvent(faction.id(), actor, false))) {
            return;
        }
        CommandFlow.submit(ctx, actor,
                new MembershipIntent.LeaveFaction(CommandFlow.handleOf(snap, faction), actor),
                LEFT, faction.name());
    }
}
