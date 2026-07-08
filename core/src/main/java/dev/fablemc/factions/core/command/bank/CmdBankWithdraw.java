package dev.fablemc.factions.core.command.bank;

import java.util.UUID;

import dev.fablemc.factions.api.BankTransactionType;
import dev.fablemc.factions.api.event.FactionBankTransactionEvent;
import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.EconomyRules;
import dev.fablemc.factions.kernel.rules.MoneyMath;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f bank withdraw <amount>} — moves money from the faction bank to the caller's wallet
 * (ref-commands-misc.md §1.3, AM-7). The reducer debits the bank and opens the payout escrow, so the
 * command only pre-validates, fires the cancellable {@link FactionBankTransactionEvent}, and submits
 * {@link EconomyIntent.RequestBankWithdrawal}; W3e's escrow executor performs the Vault payout.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdBankWithdraw extends CommandNode {

    private static final MessageKey WITHDREW = MessageKey.of("bank.withdrew");

    CmdBankWithdraw() {
        super("withdraw");
        setPermission("factions.cmd.bank");
        setRequiresPlayer(true);
        setDescription("Withdraw from the faction bank");
        setRequiredArgs("amount");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireFaction(snap, actor))) {
            return;
        }
        double amount = ArgParsers.parseMoney(ctx.arg(0));
        if (!ArgParsers.isValidAmount(amount)) {
            ctx.sendReason(ReasonCode.INVALID_AMOUNT);
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        if (CommandFlow.blocked(ctx,
                EconomyRules.validateWithdraw(snap.config().economy(), faction.bank(), amount))) {
            return;
        }
        FactionBankTransactionEvent event =
                new FactionBankTransactionEvent(faction.id(), BankTransactionType.WITHDRAW, amount, actor, null);
        if (CommandFlow.fireCancelled(event)) {
            return;
        }
        double finalAmount = MoneyMath.round2(event.getAmount());
        if (finalAmount <= 0.0) {
            ctx.sendReason(ReasonCode.INVALID_AMOUNT);
            return;
        }
        double newBalance = MoneyMath.round2(faction.bank() - finalAmount);
        CommandFlow.submit(ctx, actor,
                new EconomyIntent.RequestBankWithdrawal(CommandFlow.handleOf(snap, faction), finalAmount, actor),
                WITHDREW, CommandFlow.money(finalAmount), CommandFlow.money(newBalance));
    }
}
