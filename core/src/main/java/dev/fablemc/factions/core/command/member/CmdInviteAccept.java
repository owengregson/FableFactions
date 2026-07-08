package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.api.event.FactionJoinEvent;
import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f invite accept <faction>} — accepts a pending invite from a named faction
 * (ref-commands-misc.md §3.3; permission {@code factions.cmd.join}). Fires the cancellable
 * {@link FactionJoinEvent} then submits {@link MembershipIntent.JoinFaction} against the invite id.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdInviteAccept extends CommandNode {

    private static final MessageKey ACCEPTED = MessageKey.of("invite.accepted");

    CmdInviteAccept() {
        super("accept");
        setPermission("factions.cmd.join");
        setRequiresPlayer(true);
        setDescription("Accept an invite");
        setRequiredArgs("faction");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandGuards.factionOf(snap, actor) != null) {
            ctx.sendReason(ReasonCode.ALREADY_IN_FACTION);
            return;
        }
        Faction faction = ArgParsers.factionByName(snap, ctx.arg(0));
        if (faction == null) {
            ctx.sendReason(ReasonCode.FACTION_NOT_FOUND, ctx.arg(0));
            return;
        }
        InviteTable.Invite invite = snap.state().invites().find(faction.idx(), actor);
        if (invite == null) {
            ctx.sendReason(ReasonCode.NOT_INVITED);
            return;
        }
        if (CommandFlow.fireCancelled(new FactionJoinEvent(faction.id(), actor))) {
            return;
        }
        CommandFlow.submit(ctx, actor,
                new MembershipIntent.JoinFaction(CommandFlow.handleOf(snap, faction), actor, invite.id()),
                ACCEPTED, faction.name());
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        if (argIndex != 0 || !ctx.isPlayer()) {
            return List.of();
        }
        return CmdInviteDecline.inviteFactionNames(ctx, ctx.argOrEmpty(0).toLowerCase(Locale.ROOT));
    }
}
