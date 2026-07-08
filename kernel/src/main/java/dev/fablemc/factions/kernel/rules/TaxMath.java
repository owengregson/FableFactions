package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.config.EconomyConfig;

/**
 * Periodic bank-tax math, transcribed from {@code EngineEconomy.applyFactionTaxesNow}
 * (ref-services.md §7 tax; ref-engines.md §3.8.5).
 *
 * <p><b>Owning thread(s):</b> pure static; the reducer's {@code TaxSweep} pages call it.
 * <b>Mutability:</b> stateless. <b>Reducer rule:</b> tax is a money sink, rounded through the
 * {@link MoneyMath#round2} chain exactly as the reference.
 *
 * <p>Chain: skip when {@code bank <= max(0, minBank)}; {@code computed = round2(bank*rate)};
 * skip when {@code computed <= 0 || computed < max(0, minCharge)}; charge
 * {@code min(bank, computed)}; new bank {@code round2(max(0, bank - charge))}.
 */
public final class TaxMath {

    private TaxMath() {
    }

    /** The tax charged against {@code bank}, or {@code 0.0} when this faction is skipped. */
    public static double taxFor(EconomyConfig eco, double bank) {
        if (!eco.enabled() || !eco.taxEnabled()) {
            return 0.0;
        }
        double rate = eco.taxRate();
        if (rate <= 0.0) {
            return 0.0;
        }
        double minBank = Math.max(0.0, eco.taxMinBankBalance());
        double minCharge = Math.max(0.0, eco.taxMinChargeAmount());
        if (bank <= minBank) {
            return 0.0;
        }
        double computed = MoneyMath.round2(bank * rate);
        if (computed <= 0.0 || computed < minCharge) {
            return 0.0;
        }
        return Math.min(bank, computed);
    }

    /** The post-tax bank balance for {@code bank} after charging {@code tax}. */
    public static double bankAfter(double bank, double tax) {
        return MoneyMath.round2(Math.max(0.0, bank - tax));
    }
}
