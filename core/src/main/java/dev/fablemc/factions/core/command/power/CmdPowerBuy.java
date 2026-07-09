package dev.fablemc.factions.core.command.power;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.VaultBridge;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.core.pipeline.SubmitResult;
import dev.fablemc.factions.kernel.config.PowerConfig;
import dev.fablemc.factions.kernel.intent.PowerIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.MoneyMath;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f power buy <amount>} — buys power for Vault money (ref-commands-admin.md §7.2, AM-7).
 * Quotes the cost from the snapshot (clamped to the caller's headroom), debits the wallet via
 * {@link VaultBridge}, then submits {@link PowerIntent.BuyPower}; the reducer grants the power and
 * refunds any undelivered remainder through the escrow id.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> holds the injected {@link VaultBridge}.
 */
final class CmdPowerBuy extends CommandNode {

    private static final long NO_ESCROW = 0L;
    private static final String[] AMOUNT_HINTS = {"1", "2", "5"};
    private static final MessageKey SUCCESS = MessageKey.of("power.buy-success");

    private final VaultBridge vault;

    CmdPowerBuy(VaultBridge vault) {
        super("buy");
        setPermission("factions.cmd.power.buy");
        setRequiresPlayer(true);
        setDescription("Buy power");
        setRequiredArgs("amount");
        this.vault = vault;
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        PowerConfig power = snap.config().power();
        if (!power.buyEnabled()) {
            ctx.sendReason(ReasonCode.POWER_BUY_DISABLED);
            return;
        }
        if (!vault.present()) {
            ctx.sendReason(ReasonCode.POWER_BUY_NO_VAULT);
            return;
        }
        double amount = ArgParsers.parseMoney(ctx.arg(0));
        if (!ArgParsers.isValidAmount(amount) || amount <= 0.0 || amount > power.buyMaxPerPurchase()) {
            ctx.sendReason(ReasonCode.POWER_BUY_INVALID_AMOUNT, CommandFlow.fmt1(power.buyMaxPerPurchase()));
            return;
        }
        double current = currentPower(snap, actor);
        double maxPower = power.maxPower();
        if (current >= maxPower) {
            ctx.sendReason(ReasonCode.POWER_BUY_ALREADY_MAX);
            return;
        }
        double actual = Math.min(amount, maxPower - current);
        double cost = MoneyMath.round2(actual * power.buyCostPerPoint());
        if (!vault.withdraw(ctx.player(), cost)) {
            ctx.sendReason(ReasonCode.POWER_BUY_INSUFFICIENT_FUNDS, CommandFlow.money(cost));
            return;
        }
        SubmitResult result = CommandFlow.submitReporting(ctx, actor,
                new PowerIntent.BuyPower(actor, actual, cost, NO_ESCROW),
                SUCCESS, CommandFlow.fmt1(actual), CommandFlow.money(cost));
        if (result != SubmitResult.ACCEPTED) {
            // Wallet debited but BuyPower never reduced — refund rather than destroy (finding #18).
            vault.deposit(ctx.player(), cost);
        }
    }

    private static double currentPower(KernelSnapshot snap, UUID actor) {
        int ordinal = snap.memberOrdinal(actor);
        return ordinal < 0 ? 0.0 : snap.powerAt(ordinal, snap.tick());
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0
                ? Completions.matching(AMOUNT_HINTS, ctx.argOrEmpty(0).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
