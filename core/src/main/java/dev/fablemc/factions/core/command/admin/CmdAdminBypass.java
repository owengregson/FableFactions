package dev.fablemc.factions.core.command.admin;

import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;
import dev.fablemc.factions.kernel.state.PlayerLedger;

/**
 * {@code /fa bypass} — toggles the sender's persisted protection-override ("overriding") bit
 * (ref-commands-admin.md §2.1). Reads the current bit from the snapshot member and submits a
 * {@link PrefIntent.SetOverriding} with the flipped value; the reducer persists it and the feedback
 * fan-out reports the new state (CONTRACTS §6.4).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread. <b>Mutability:</b>
 * a leaf node configured once at construction.
 */
public final class CmdAdminBypass extends CommandNode {

    /** Builds the bypass command. */
    public CmdAdminBypass() {
        super("bypass");
        setCommandPath("/fa");
        setPermission("factions.admin");
        setRequiresPlayer(true);
        setDescription("Toggle protection bypass for yourself.");
    }

    @Override
    protected void perform(CommandContext ctx) {
        KernelSnapshot snap = ctx.snap();
        UUID actor = ctx.player().getUniqueId();
        boolean current = false;
        int ordinal = snap.memberOrdinal(actor);
        if (ordinal >= 0) {
            MemberView member = snap.member(ordinal);
            if (member != null) {
                current = PlayerLedger.pref(member.prefsBits(), PlayerLedger.PREF_OVERRIDING);
            }
        }
        CommandKit.submit(ctx, new PrefIntent.SetOverriding(actor, !current), CommandKit.adminOrigin(ctx.sender()));
    }
}
