package dev.fablemc.factions.core.integration.worldguard;

import java.util.logging.Logger;

import dev.fablemc.factions.core.integration.Reflect;

/**
 * Builds the {@link TerritoryGuard} façade behind the config-AND-presence gate (ref-integrations
 * §2.2): config toggle on AND a plugin named {@code WorldGuard} installed. Any miss ⇒ Noop.
 *
 * <p><b>Owning thread(s):</b> the boot thread. <b>Mutability:</b> stateless.
 */
public final class TerritoryGuardFactory {

    private TerritoryGuardFactory() {
    }

    /** Config-on AND {@code WorldGuard} present ⇒ reflective guard (with {@code syncEnabled}); else Noop. */
    public static TerritoryGuard create(boolean configEnabled, boolean syncEnabled, Logger logger) {
        if (!configEnabled) {
            logger.info("WorldGuard integration disabled in config.");
            return new NoopTerritoryGuard();
        }
        if (!Reflect.pluginPresent("WorldGuard")) {
            logger.info("WorldGuard not found — territory guard disabled.");
            return new NoopTerritoryGuard();
        }
        logger.info("WorldGuard detected — territory guard enabled.");
        return new ReflectiveTerritoryGuard(syncEnabled);
    }
}
