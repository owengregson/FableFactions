package dev.fablemc.factions.kernel.config;

/**
 * Typed {@code factions.economy.*} configuration (pvp-resources.md §1.6, pvp-services.md §2.1).
 *
 * <p><b>Owning thread(s):</b> parsed in {@code :core}, read on any thread. <b>Mutability:</b>
 * immutable value. <b>Reducer rule:</b> swapped whole via {@code SwapConfig}.
 *
 * <p>{@code costCreate} and {@code costClaim} are reference-dead knobs wired here (D-7) with
 * behavior-preserving defaults. Tax uses the {@code round2} chain
 * ({@code round2(bank*rate)}, skip below {@code minCharge}/{@code minBank}). {@code historyPageSize}
 * is {@code factions.economy.bank.history.page-size}.
 */
public record EconomyConfig(
        boolean enabled,
        double costCreate,
        double costClaim,
        boolean taxEnabled,
        double taxRate,
        int taxIntervalHours,
        double taxMinBankBalance,
        double taxMinChargeAmount,
        int historyPageSize) {

    /** The complete reference-default economy configuration. */
    public static EconomyConfig defaults() {
        return new EconomyConfig(
                true,   // enabled
                50.0,   // costCreate (D-7)
                100.0,  // costClaim (D-7)
                false,  // taxEnabled
                0.05,   // taxRate
                24,     // taxIntervalHours
                0.0,    // taxMinBankBalance
                0.01,   // taxMinChargeAmount
                8);     // historyPageSize
    }
}
