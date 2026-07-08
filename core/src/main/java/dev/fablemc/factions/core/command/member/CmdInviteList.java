package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;

/**
 * {@code /f invite list [faction]} — lists the caller's pending invites, or (with
 * {@code factions.admin}) another faction's pending invites (ref-commands-misc.md §3.6). Pure
 * snapshot read; no intent submitted.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdInviteList extends CommandNode {

    private static final String ADMIN_PERMISSION = "factions.admin";
    private static final MessageKey NO_INVITES = MessageKey.of("invite.no-invite-pending");
    private static final MessageKey SUMMARY = MessageKey.of("invite.summary");
    private static final MessageKey SUMMARY_LINE = MessageKey.of("invite.summary-line");

    CmdInviteList() {
        super("list");
        setPermission("factions.cmd.invite.list");
        setRequiresPlayer(true);
        setDescription("List your pending invites");
        setOptionalArgs("faction");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (ctx.argCount() == 0) {
            listSelf(ctx, snap, actor);
            return;
        }
        if (!ctx.sender().hasPermission(ADMIN_PERMISSION)) {
            ctx.sendReason(ReasonCode.NO_PERMISSION);
            return;
        }
        Faction faction = ArgParsers.factionByName(snap, ctx.arg(0));
        if (faction == null) {
            ctx.sendReason(ReasonCode.FACTION_NOT_FOUND, ctx.arg(0));
            return;
        }
        InviteTable.Invite[] invites = snap.state().invites().forFaction(faction.idx());
        if (invites.length == 0) {
            ctx.send(NO_INVITES);
            return;
        }
        ctx.send(SUMMARY, Integer.toString(invites.length));
        for (InviteTable.Invite invite : invites) {
            ctx.send(SUMMARY_LINE, faction.name(), nameOf(snap, invite.invitee()));
        }
    }

    private static void listSelf(CommandContext ctx, KernelSnapshot snap, UUID actor) {
        InviteTable.Invite[] invites = snap.state().invites().forInvitee(actor);
        if (invites.length == 0) {
            ctx.send(NO_INVITES);
            return;
        }
        ctx.send(SUMMARY, Integer.toString(invites.length));
        for (InviteTable.Invite invite : invites) {
            Faction faction = snap.state().factions().at(invite.factionOrdinal());
            String factionName = faction != null ? faction.name() : "Unknown";
            ctx.send(SUMMARY_LINE, factionName, nameOf(snap, invite.inviter()));
        }
    }

    private static String nameOf(KernelSnapshot snap, UUID player) {
        int ordinal = snap.memberOrdinal(player);
        if (ordinal >= 0) {
            MemberView member = snap.member(ordinal);
            if (member != null && member.nameLast() != null) {
                return member.nameLast();
            }
        }
        return player.toString();
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0
                ? Completions.factionNames(ctx.snap(), ctx.argOrEmpty(0).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
