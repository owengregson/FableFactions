package dev.fablemc.factions.core.integration.vault;

import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import dev.fablemc.factions.core.integration.Reflect;

/**
 * The reflection-backed {@link VaultEconomy}: resolves {@code net.milkbowl.vault.economy.Economy}
 * and its provider entirely by name so {@code :core} never imports Vault (ref-integrations §1.1).
 *
 * <p><b>Owning thread(s):</b> the escrow executor / command flows on their region thread.
 * <b>Mutability:</b> holds only lazily-cached reflective {@link Method} handles; the provider
 * itself is re-resolved on <em>every</em> call because economy plugins (e.g. EzEconomy) may
 * register with Vault <em>after</em> this hook is built — the lazy resolution is load-bearing.
 */
public final class ReflectiveVaultEconomy implements VaultEconomy {

    private static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";

    private final Class<?> economyClass;
    private volatile Method getBalance;
    private volatile Method withdrawPlayer;
    private volatile Method depositPlayer;

    /** Resolves the Vault {@code Economy} class once; individual providers are resolved per call. */
    public ReflectiveVaultEconomy() {
        this.economyClass = Reflect.findClass(ECONOMY_CLASS);
    }

    @Override
    public boolean isEnabled() {
        return provider() != null;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        Object provider = provider();
        if (provider == null) {
            return 0.0;
        }
        Method m = getBalance != null ? getBalance
                : (getBalance = Reflect.method(provider.getClass(), "getBalance", OfflinePlayer.class));
        Object result = Reflect.invoke(provider, m, player);
        return result instanceof Number number ? number.doubleValue() : 0.0;
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (amount <= 0.0) {
            return false;
        }
        Object provider = provider();
        if (provider == null) {
            return false;
        }
        Method m = withdrawPlayer != null ? withdrawPlayer : (withdrawPlayer =
                Reflect.method(provider.getClass(), "withdrawPlayer", OfflinePlayer.class, double.class));
        return transactionSuccess(Reflect.invoke(provider, m, player, amount));
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        if (amount <= 0.0) {
            return false;
        }
        Object provider = provider();
        if (provider == null) {
            return false;
        }
        Method m = depositPlayer != null ? depositPlayer : (depositPlayer =
                Reflect.method(provider.getClass(), "depositPlayer", OfflinePlayer.class, double.class));
        return transactionSuccess(Reflect.invoke(provider, m, player, amount));
    }

    /** Re-resolves the registered provider from Bukkit's services manager, or {@code null}. */
    private Object provider() {
        if (economyClass == null) {
            return null;
        }
        RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economyClass);
        return rsp != null ? rsp.getProvider() : null;
    }

    /** Reads {@code EconomyResponse.transactionSuccess()} reflectively; {@code false} on any miss. */
    private static boolean transactionSuccess(Object response) {
        Object ok = Reflect.call(response, "transactionSuccess");
        return ok instanceof Boolean bool && bool;
    }
}
