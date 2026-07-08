package dev.fablemc.factions.core.command.travel;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.TravelRules;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f warp cost <warpName> <amount>} — sets a warp's per-use Vault cost (ref-commands-misc.md
 * §5.7). Officer-or-above; a non-negative amount ({@code 0} makes the warp free). Submits
 * {@link TravelIntent.SetWarpCost}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdWarpCost extends CommandNode {

    private static final MessageKey COST_SET = MessageKey.of("warp.cost-set");

    CmdWarpCost() {
        super("cost");
        setPermission("factions.cmd.warp.cost");
        setRequiresPlayer(true);
        setDescription("Set a warp's use cost");
        setRequiredArgs("warpName", "amount");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOfficerOrAbove(snap, actor))) {
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        String name = ctx.arg(0).toLowerCase(Locale.ROOT);
        if (CommandFlow.blocked(ctx, TravelRules.requireWarp(snap.state().warps(), faction.idx(), name))) {
            return;
        }
        OptionalDouble amount = ArgParsers.parseDouble(ctx.arg(1));
        if (amount.isEmpty() || amount.getAsDouble() < 0.0) {
            ctx.sendReason(ReasonCode.INVALID_AMOUNT);
            return;
        }
        double cost = TravelRules.clampCost(amount.getAsDouble());
        CommandFlow.submit(ctx, actor,
                new TravelIntent.SetWarpCost(CommandFlow.handleOf(snap, faction), name, cost, actor),
                COST_SET, name, CommandFlow.money(cost));
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0 ? WarpCompletions.names(ctx) : List.of();
    }
}
