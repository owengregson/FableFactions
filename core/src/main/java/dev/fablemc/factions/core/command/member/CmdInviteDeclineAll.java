package dev.fablemc.factions.core.command.member;

import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f invite declineall} — declines every pending invite the caller holds
 * (ref-commands-misc.md §3.5). Submits {@link MembershipIntent.DeclineAllInvites}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdInviteDeclineAll extends CommandNode {

    private static final MessageKey DECLINED_ALL = MessageKey.of("invite.declined-all");
    private static final MessageKey NONE = MessageKey.of("invite.no-invite-pending");

    CmdInviteDeclineAll() {
        super("declineall");
        setPermission("factions.cmd.invite");
        setRequiresPlayer(true);
        setDescription("Decline all pending invites");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        int count = snap.state().invites().forInvitee(actor).length;
        if (count == 0) {
            ctx.send(NONE);
            return;
        }
        CommandFlow.submit(ctx, actor, new MembershipIntent.DeclineAllInvites(actor),
                DECLINED_ALL, Integer.toString(count));
    }
}
