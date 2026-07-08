package dev.fablemc.factions.core.integration.essentials;

import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;

import dev.fablemc.factions.core.integration.Reflect;

/**
 * Builds the {@link EssentialsInterop} façade behind the config-AND-presence gate
 * (ref-integrations §4.2): config toggle on, a plugin named {@code Essentials} installed, and that
 * plugin actually implementing {@code com.earth2me.essentials.IEssentials}. Any miss ⇒ Noop.
 *
 * <p><b>Owning thread(s):</b> the boot thread. <b>Mutability:</b> stateless.
 */
public final class EssentialsInteropFactory {

    private static final String IESSENTIALS = "com.earth2me.essentials.IEssentials";

    private EssentialsInteropFactory() {
    }

    /** Config-on AND {@code Essentials} present AND {@code IEssentials} ⇒ reflective; else Noop. */
    public static EssentialsInterop create(boolean configEnabled, Logger logger) {
        if (!configEnabled) {
            logger.info("EssentialsX interop disabled in config.");
            return new NoopEssentialsInterop();
        }
        Plugin essentials = Reflect.plugin("Essentials");
        if (essentials == null) {
            logger.info("EssentialsX not found — interop disabled.");
            return new NoopEssentialsInterop();
        }
        Class<?> iEssentials = Reflect.findClass(IESSENTIALS);
        if (iEssentials == null || !iEssentials.isInstance(essentials)) {
            logger.warning("Found plugin named 'Essentials' but it does not implement IEssentials "
                    + "— interop disabled.");
            return new NoopEssentialsInterop();
        }
        logger.info("EssentialsX detected — home/warp teleport interop enabled.");
        return new ReflectiveEssentialsInterop(essentials, logger);
    }
}
