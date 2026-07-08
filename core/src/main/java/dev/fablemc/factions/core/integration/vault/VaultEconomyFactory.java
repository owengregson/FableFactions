package dev.fablemc.factions.core.integration.vault;

import java.util.logging.Logger;

import dev.fablemc.factions.core.integration.Reflect;

/**
 * Builds the {@link VaultEconomy} façade behind the Vault presence gate (ref-integrations §1.1).
 * Vault-present yields the reflective provider (its economy is resolved lazily per call); Vault
 * absent yields {@link NoopVaultEconomy}. Never throws.
 *
 * <p><b>Owning thread(s):</b> the boot thread. <b>Mutability:</b> stateless.
 */
public final class VaultEconomyFactory {

    private VaultEconomyFactory() {
    }

    /** Vault present ⇒ reflective economy (lazy provider); absent ⇒ Noop. Logs the outcome. */
    public static VaultEconomy create(Logger logger) {
        if (!Reflect.pluginPresent("Vault")) {
            logger.warning("Vault not found — economy features will be disabled.");
            return new NoopVaultEconomy();
        }
        logger.info("Vault found — economy provider will be resolved on first use.");
        return new ReflectiveVaultEconomy();
    }
}
