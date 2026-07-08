package dev.fablemc.factions.core.command.bank;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.api.BankTransactionType;
import dev.fablemc.factions.api.event.FactionBankTransactionEvent;
import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.EconomyRules;
import dev.fablemc.factions.kernel.rules.MoneyMath;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f bank transfer <faction> <amount>} — moves money bank→bank to another faction
 * (ref-commands-misc.md §1.4). Officer-or-above; no wallet is involved. Submits
 * {@link EconomyIntent.TransferBank}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdBankTransfer extends CommandNode {

    private static final MessageKey TRANSFERRED = MessageKey.of("bank.transferred");

    CmdBankTransfer() {
        super("transfer");
        setPermission("factions.cmd.bank.transfer");
        setRequiresPlayer(true);
        setDescription("Transfer to another faction's bank");
        setRequiredArgs("faction", "amount");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOfficerOrAbove(snap, actor))) {
            return;
        }
        Faction source = CommandGuards.factionOf(snap, actor);
        Faction target = ArgParsers.factionByName(snap, ctx.arg(0));
        if (target == null) {
            ctx.sendReason(ReasonCode.FACTION_NOT_FOUND, ctx.arg(0));
            return;
        }
        if (source.idx() == target.idx()) {
            ctx.sendReason(ReasonCode.INVALID_AMOUNT);
            return;
        }
        double amount = ArgParsers.parseMoney(ctx.arg(1));
        if (!ArgParsers.isValidAmount(amount)) {
            ctx.sendReason(ReasonCode.INVALID_AMOUNT);
            return;
        }
        if (CommandFlow.blocked(ctx, EconomyRules.validateTransfer(snap.config().economy(), source.bank(), amount))) {
            return;
        }
        FactionBankTransactionEvent event = new FactionBankTransactionEvent(source.id(),
                BankTransactionType.TRANSFER, amount, actor, target.id());
        if (CommandFlow.fireCancelled(event)) {
            return;
        }
        double finalAmount = MoneyMath.round2(event.getAmount());
        CommandFlow.submit(ctx, actor,
                new EconomyIntent.TransferBank(CommandFlow.handleOf(snap, source),
                        CommandFlow.handleOf(snap, target), finalAmount, actor),
                TRANSFERRED, CommandFlow.money(finalAmount), source.name(), target.name());
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0
                ? Completions.factionNames(ctx.snap(), ctx.argOrEmpty(0).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
