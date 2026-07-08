package dev.fablemc.factions.core.command.bank;

import java.util.UUID;

import dev.fablemc.factions.api.BankTransactionType;
import dev.fablemc.factions.api.event.FactionBankTransactionEvent;
import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.VaultBridge;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.EconomyRules;
import dev.fablemc.factions.kernel.rules.MoneyMath;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f bank deposit <amount>} — moves money from the caller's wallet into the faction bank
 * (ref-commands-misc.md §1.2, AM-7). Pre-validates the amount / economy, fires the cancellable
 * {@link FactionBankTransactionEvent} (amount is mutable), debits the wallet via {@link VaultBridge},
 * then submits {@link EconomyIntent.CreditBank}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread (the Vault debit is
 * a synchronous main-thread call). <b>Mutability:</b> holds the injected {@link VaultBridge}.
 *
 * <p><b>Escrow note (AM-7):</b> the durable escrow-open journal that must precede the Vault debit is
 * owned by W3e's {@code EscrowExecutor}; this command performs the wallet debit then credits the
 * bank with a zero escrow id (the reducer uses the id only on the vanished-faction refund path).
 */
final class CmdBankDeposit extends CommandNode {

    private static final long NO_ESCROW = 0L;
    private static final MessageKey DEPOSITED = MessageKey.of("bank.deposited");
    private static final MessageKey WALLET_INSUFFICIENT = MessageKey.of("bank.insufficient-funds");

    private final VaultBridge vault;

    CmdBankDeposit(VaultBridge vault) {
        super("deposit");
        setPermission("factions.cmd.bank");
        setRequiresPlayer(true);
        setDescription("Deposit into the faction bank");
        setRequiredArgs("amount");
        this.vault = vault;
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
        if (!vault.present()) {
            ctx.sendReason(ReasonCode.ECONOMY_DISABLED);
            return;
        }
        if (CommandFlow.blocked(ctx, EconomyRules.validateDeposit(snap.config().economy(), amount))) {
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        FactionBankTransactionEvent event =
                new FactionBankTransactionEvent(faction.id(), BankTransactionType.DEPOSIT, amount, actor, null);
        if (CommandFlow.fireCancelled(event)) {
            return;
        }
        double finalAmount = MoneyMath.round2(event.getAmount());
        if (finalAmount <= 0.0) {
            ctx.sendReason(ReasonCode.INVALID_AMOUNT);
            return;
        }
        if (!vault.withdraw(ctx.player(), finalAmount)) {
            ctx.send(WALLET_INSUFFICIENT, CommandFlow.money(finalAmount));
            return;
        }
        double newBalance = MoneyMath.round2(faction.bank() + finalAmount);
        CommandFlow.submit(ctx, actor,
                new EconomyIntent.CreditBank(CommandFlow.handleOf(snap, faction), finalAmount, actor, NO_ESCROW),
                DEPOSITED, CommandFlow.money(finalAmount), CommandFlow.money(newBalance));
    }
}
