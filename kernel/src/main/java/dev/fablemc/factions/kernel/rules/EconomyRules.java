package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.config.EconomyConfig;
import dev.fablemc.factions.kernel.msg.ReasonCode;

/**
 * Bank deposit / withdraw / transfer validation (ref-engines.md §3.8). Pure static functions
 * shared by the command layer and reducer.
 *
 * <p><b>Owning thread(s):</b> pure static. <b>Mutability:</b> stateless. <b>Reducer rule:</b>
 * the reducer never lets a bank go negative — withdraw and transfer are gated on
 * {@code bank >= amount}, and every balance is rounded through {@link MoneyMath#round2}.
 *
 * <p>Common guards: amount must be {@code > 0} and the economy must be enabled.
 */
public final class EconomyRules {

    private EconomyRules() {
    }

    /** {@code null} when a deposit of {@code amount} is valid, else the rejection. */
    public static ReasonCode validateDeposit(EconomyConfig eco, double amount) {
        if (!eco.enabled()) {
            return ReasonCode.ECONOMY_DISABLED;
        }
        if (!(amount > 0.0)) {
            return ReasonCode.INVALID_AMOUNT;
        }
        return null;
    }

    /** {@code null} when a withdrawal of {@code amount} from {@code bank} is valid. */
    public static ReasonCode validateWithdraw(EconomyConfig eco, double bank, double amount) {
        if (!eco.enabled()) {
            return ReasonCode.ECONOMY_DISABLED;
        }
        if (!(amount > 0.0)) {
            return ReasonCode.INVALID_AMOUNT;
        }
        if (bank < amount) {
            return ReasonCode.INSUFFICIENT_FUNDS;
        }
        return null;
    }

    /** {@code null} when a transfer of {@code amount} from {@code fromBank} is valid. */
    public static ReasonCode validateTransfer(EconomyConfig eco, double fromBank, double amount) {
        return validateWithdraw(eco, fromBank, amount);
    }
}
