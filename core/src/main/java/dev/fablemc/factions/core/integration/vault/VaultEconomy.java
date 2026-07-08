package dev.fablemc.factions.core.integration.vault;

import org.bukkit.OfflinePlayer;

/**
 * The economy façade the escrow saga and warp/power-buy flows consult (ref-integrations §1,
 * proposal-C §10.3). One interface so the pipeline never imports {@code net.milkbowl.vault} — the
 * live implementation resolves Vault reflectively and the {@link NoopVaultEconomy} covers absence.
 *
 * <p><b>Owning thread(s):</b> called from the escrow executor / command flows on their region
 * thread; every method is side-effecting on the player wallet, so callers order it around the
 * durable escrow journal (AM-7). <b>Mutability:</b> implementations are stateless or hold only a
 * lazily-resolved provider handle.
 *
 * <p>Contract (ref-integrations §1.2): every method fails soft — a mutation returns {@code false}
 * when no provider is registered or the amount is non-positive, and never throws.
 */
public interface VaultEconomy {

    /** {@code true} when Vault is present AND an economy provider is registered right now. */
    boolean isEnabled();

    /** The player's wallet balance, or {@code 0} when no provider is available. */
    double getBalance(OfflinePlayer player);

    /** Withdraws {@code amount}; {@code false} when no provider, {@code amount <= 0}, or Vault denies. */
    boolean withdraw(OfflinePlayer player, double amount);

    /** Deposits {@code amount}; {@code false} when no provider, {@code amount <= 0}, or Vault denies. */
    boolean deposit(OfflinePlayer player, double amount);
}
