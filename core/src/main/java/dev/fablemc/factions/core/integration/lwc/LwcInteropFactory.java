package dev.fablemc.factions.core.integration.lwc;

import java.util.logging.Logger;

import dev.fablemc.factions.core.integration.IntegrationSettings;
import dev.fablemc.factions.core.integration.Reflect;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * Builds the {@link LwcInterop} façade behind the config-AND-presence gate (ref-integrations §6.1):
 * config toggle on AND a plugin named {@code LWC} or {@code LWCX} installed. Any miss ⇒ Noop. The
 * caller ({@link dev.fablemc.factions.core.integration.IntegrationsBootstrap}) registers the returned
 * interop's listeners.
 *
 * <p><b>Owning thread(s):</b> the boot thread. <b>Mutability:</b> stateless.
 */
public final class LwcInteropFactory {

    private LwcInteropFactory() {
    }

    /** Config-on AND ({@code LWC} or {@code LWCX}) present ⇒ reflective interop; else Noop. */
    public static LwcInterop create(IntegrationSettings settings, SnapshotHub snapshots, Worlds worlds,
                                    Messages messages, Logger logger) {
        if (!settings.lwcEnabled()) {
            logger.info("LWC integration disabled in config.");
            return new NoopLwcInterop();
        }
        if (!Reflect.pluginPresent("LWC") && !Reflect.pluginPresent("LWCX")) {
            logger.info("LWC/LWCX not found - integration disabled.");
            return new NoopLwcInterop();
        }
        logger.info("LWC/LWCX detected - integration enabled.");
        return new ReflectiveLwcInterop(snapshots, worlds, messages, logger,
                settings.lwcRequireBuildRightsToCreate(), settings.lwcRemoveIfNoBuildRights());
    }
}
