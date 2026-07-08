package dev.fablemc.factions.core.command.member;

import java.util.UUID;

import dev.fablemc.factions.api.event.FactionDisbandEvent;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.kernel.config.PredefinedConfig;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f disband} — dissolves the caller's faction (ref-commands-core.md §7.2). Requires
 * ownership; the predefined subsystem may block disband of a predefined faction. Fires the
 * cancellable {@link FactionDisbandEvent} before submitting the paged
 * {@link LifecycleIntent.DisbandFaction}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdDisband extends CommandNode {

    private static final MessageKey DISBANDED = MessageKey.of("custom.faction.disbanded");

    CmdDisband() {
        super("disband");
        setPermission("factions.cmd.disband");
        setRequiresPlayer(true);
        setDescription("Disband your faction");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOwner(snap, actor))) {
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        PredefinedConfig predefined = snap.config().predefined();
        if (predefined.enabled() && predefined.blockDisband() && predefined.isPredefinedName(faction.name())) {
            ctx.sendReason(ReasonCode.PREDEFINED_DISBAND_BLOCKED);
            return;
        }
        if (CommandFlow.fireCancelled(new FactionDisbandEvent(faction.id(), faction.name(), actor))) {
            return;
        }
        CommandFlow.submit(ctx, actor,
                new LifecycleIntent.DisbandFaction(CommandFlow.handleOf(snap, faction), false, actor),
                DISBANDED);
    }
}
