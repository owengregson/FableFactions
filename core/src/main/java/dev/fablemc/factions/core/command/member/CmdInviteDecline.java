package dev.fablemc.factions.core.command.member;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f invite decline <faction>} — declines a pending invite from a named faction
 * (ref-commands-misc.md §3.4). Submits {@link MembershipIntent.DeclineInvite}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdInviteDecline extends CommandNode {

    private static final MessageKey DECLINED = MessageKey.of("invite.declined");

    CmdInviteDecline() {
        super("decline");
        setPermission("factions.cmd.invite");
        setRequiresPlayer(true);
        setDescription("Decline an invite");
        setRequiredArgs("faction");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        Faction faction = ArgParsers.factionByName(snap, ctx.arg(0));
        if (faction == null) {
            ctx.sendReason(ReasonCode.FACTION_NOT_FOUND, ctx.arg(0));
            return;
        }
        CommandFlow.submit(ctx, actor,
                new MembershipIntent.DeclineInvite(CommandFlow.handleOf(snap, faction), actor),
                DECLINED, faction.name());
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0 && ctx.isPlayer()
                ? inviteFactionNames(ctx, ctx.argOrEmpty(0).toLowerCase(Locale.ROOT))
                : List.of();
    }

    /** Names of the factions the caller currently has pending invites from, prefix-filtered. */
    static List<String> inviteFactionNames(CommandContext ctx, String prefix) {
        KernelSnapshot snap = ctx.snap();
        InviteTable.Invite[] invites = snap.state().invites().forInvitee(ctx.player().getUniqueId());
        List<String> out = new ArrayList<>();
        for (InviteTable.Invite invite : invites) {
            Faction faction = snap.state().factions().at(invite.factionOrdinal());
            if (faction != null && faction.name().regionMatches(true, 0, prefix, 0, prefix.length())) {
                out.add(faction.name());
            }
        }
        return out;
    }
}
