package dev.fablemc.factions.core.integration.vault;

import org.bukkit.OfflinePlayer;

/**
 * The Vault-absent economy façade: every balance reads {@code 0} and every mutation returns
 * {@code false} (ref-integrations §1.4). Installed when Vault is not on the server, so economy
 * consumers surface an "economy required/unavailable" message rather than NPE.
 *
 * <p><b>Owning thread(s):</b> any. <b>Mutability:</b> stateless singleton-shaped value.
 */
public final class NoopVaultEconomy implements VaultEconomy {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return 0.0;
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        return false;
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        return false;
    }
}
