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
import dev.fablemc.factions.kernel.state.MemberView;

/**
 * {@code /f join [faction]} — joins a faction by invite or its OPEN flag, or lists the caller's
 * pending invites when no name is given (ref-commands-core.md §7.24). Fires the cancellable
 * {@link FactionJoinEvent} then submits {@link MembershipIntent.JoinFaction} (the reducer removes
 * the accepted invite atomically with the join).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdJoin extends CommandNode {

    private static final MessageKey JOINED = MessageKey.of("member.joined");
    private static final MessageKey NO_INVITES = MessageKey.of("invite.no-invite-pending");
    private static final MessageKey SUMMARY = MessageKey.of("invite.summary");
    private static final MessageKey SUMMARY_LINE = MessageKey.of("invite.summary-line");

    CmdJoin() {
        super("join");
        setPermission("factions.cmd.join");
        setRequiresPlayer(true);
        setDescription("Join a faction");
        setOptionalArgs("faction");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandGuards.factionOf(snap, actor) != null) {
            ctx.sendReason(ReasonCode.ALREADY_IN_FACTION);
            return;
        }
        if (ctx.argCount() == 0) {
            listInvites(ctx, snap, actor);
            return;
        }
        Faction faction = ArgParsers.factionByName(snap, ctx.arg(0));
        if (faction == null) {
            ctx.sendReason(ReasonCode.FACTION_NOT_FOUND, ctx.arg(0));
            return;
        }
        long viaInviteId;
        boolean open = faction.flag(Faction.FLAG_OPEN,
                snap.config().flagDefaults().defaultOf(Faction.FLAG_OPEN));
        if (open) {
            viaInviteId = MembershipIntent.OPEN_JOIN;
        } else {
            InviteTable.Invite invite = snap.state().invites().find(faction.idx(), actor);
            if (invite == null) {
                ctx.sendReason(ReasonCode.NO_INVITE_PENDING);
                return;
            }
            viaInviteId = invite.id();
        }
        if (CommandFlow.fireCancelled(new FactionJoinEvent(faction.id(), actor))) {
            return;
        }
        CommandFlow.submit(ctx, actor,
                new MembershipIntent.JoinFaction(CommandFlow.handleOf(snap, faction), actor, viaInviteId),
                JOINED, faction.name());
    }

    private static void listInvites(CommandContext ctx, KernelSnapshot snap, UUID actor) {
        InviteTable.Invite[] invites = snap.state().invites().forInvitee(actor);
        if (invites.length == 0) {
            ctx.send(NO_INVITES);
            return;
        }
        ctx.send(SUMMARY, Integer.toString(invites.length));
        for (InviteTable.Invite invite : invites) {
            Faction faction = snap.state().factions().at(invite.factionOrdinal());
            String factionName = faction != null ? faction.name() : "Unknown";
            ctx.send(SUMMARY_LINE, factionName, inviterName(snap, invite.inviter()));
        }
    }

    private static String inviterName(KernelSnapshot snap, UUID inviter) {
        int ordinal = snap.memberOrdinal(inviter);
        if (ordinal >= 0) {
            MemberView member = snap.member(ordinal);
            if (member != null && member.nameLast() != null) {
                return member.nameLast();
            }
        }
        return inviter.toString();
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0
                ? Completions.factionNames(ctx.snap(), ctx.argOrEmpty(0).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
