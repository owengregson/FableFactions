package dev.fablemc.factions.core.command.admin;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.config.PredefinedConfig;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

import java.util.List;

/**
 * {@code /fa disband <faction>} — force-disband a faction (ref-commands-admin.md §2.6). Console
 * capable. Pre-validates the target and the predefined block-disband protection, then submits a
 * paged {@link LifecycleIntent.DisbandFaction} with {@code byAdmin=true} (bypasses the ownership
 * check); the reducer scrubs inbound references and emits the terminal effect.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread. <b>Mutability:</b>
 * a leaf node configured once at construction.
 */
public final class CmdAdminDisband extends CommandNode {

    /** Builds the admin-disband command. */
    public CmdAdminDisband() {
        super("disband");
        setCommandPath("/fa");
        setPermission("factions.cmd.disband.other");
        setDescription("Force-disband a faction.");
        setRequiredArgs("faction");
    }

    @Override
    protected void perform(CommandContext ctx) {
        KernelSnapshot snap = ctx.snap();
        Faction target = ArgParsers.factionByName(snap, ctx.arg(0));
        if (target == null) {
            ctx.sendReason(ReasonCode.FACTION_NOT_FOUND, ctx.argOrEmpty(0));
            return;
        }
        PredefinedConfig predefined = snap.config().predefined();
        if (predefined.enabled() && predefined.blockDisband() && predefined.isPredefinedName(target.name())) {
            ctx.sendReason(ReasonCode.PREDEFINED_DISBAND_BLOCKED);
            return;
        }
        CommandKit.submit(ctx, new LifecycleIntent.DisbandFaction(CommandKit.handleOf(snap, target),
                true, CommandKit.adminOrigin(ctx.sender()).actor()), CommandKit.adminOrigin(ctx.sender()));
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0 ? Completions.factionNames(ctx.snap(), ctx.argOrEmpty(0)) : List.of();
    }
}
