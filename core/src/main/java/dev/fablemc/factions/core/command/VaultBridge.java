package dev.fablemc.factions.core.command;

import org.bukkit.entity.Player;

/**
 * The minimal Vault wallet boundary the money-moving commands ({@code /f bank deposit|withdraw},
 * {@code /f power buy}) cross (ARCHITECTURE AM-7, ref-commands-misc.md §1.6, ref-commands-admin.md
 * §7.2). A deposit debits the player's wallet here <b>before</b> the {@code CreditBank} intent is
 * submitted; a power purchase debits here before {@code BuyPower}. The reducer remains the
 * authority over the faction bank / player power — this seam only touches the external economy.
 *
 * <p><b>Owning thread(s):</b> called from a command {@code perform} on the caller's region/main
 * thread (a synchronous Vault call is safe there). The implementation lands in W3e
 * ({@code economy.VaultAdapter}); when no economy plugin is present {@link #present()} is
 * {@code false} and the command reports {@code general.economy-disabled} before touching money.
 * <b>Mutability:</b> the implementation wraps the (stateful) Vault provider; this interface is a
 * pure seam.
 */
public interface VaultBridge {

    /** {@code true} when an economy provider is registered and enabled (Vault present). */
    boolean present();

    /**
     * Debits {@code amount} from {@code player}'s wallet, returning {@code true} on success and
     * {@code false} when the withdrawal was refused (insufficient funds / provider error). The
     * caller only proceeds to the bank-credit / power-grant intent when this returns {@code true}.
     */
    boolean withdraw(Player player, double amount);

    /** Credits {@code amount} back to {@code player}'s wallet (refund / withdrawal payout). */
    void deposit(Player player, double amount);
}
