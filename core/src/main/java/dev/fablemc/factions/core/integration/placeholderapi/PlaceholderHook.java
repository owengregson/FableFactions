package dev.fablemc.factions.core.integration.placeholderapi;

import java.util.logging.Logger;

import dev.fablemc.factions.core.integration.Reflect;
import dev.fablemc.factions.core.pipeline.SnapshotHub;

/**
 * The PlaceholderAPI hook (ref-integrations §3.1). The FableFactions expansion identifier is
 * {@code fable}, so all placeholders render as {@code %fable_<param>%} ({@link PlaceholderData}
 * resolves each).
 *
 * <p><b>Isolation note (proposal-C §10.3):</b> {@code PlaceholderExpansion} is an <em>abstract
 * class</em>, not an interface, so the typed subclass ({@link FableExpansion}) carries a
 * compileOnly dependency and is loaded ONLY by FQN string behind the presence gate below — the
 * name never appears as a compiled reference on an unconditional path, so servers without
 * PlaceholderAPI never attempt the link. Reflective failures degrade to a Noop hook
 * (ref-integrations §12.4), reported once at boot.
 *
 * <p><b>Owning thread(s):</b> created on the boot thread; {@link #unregister()} runs on the
 * disable/reload thread. <b>Mutability:</b> immutable (holds the registered expansion handle).
 */
public final class PlaceholderHook {

    /** The FableFactions PlaceholderAPI expansion identifier (placeholders are {@code %fable_*%}). */
    public static final String IDENTIFIER = "fable";

    private static final String EXPANSION_FQN =
            "dev.fablemc.factions.core.integration.placeholderapi.FableExpansion";

    private final Object expansion;

    private PlaceholderHook(Object expansion) {
        this.expansion = expansion;
    }

    /** {@code true} when PlaceholderAPI is present and the {@code %fable_*%} expansion registered. */
    public boolean ready() {
        return expansion != null;
    }

    /**
     * Detects PlaceholderAPI by presence only (the reference gates PAPI on presence, not the config
     * toggle — ref-integrations §3.1) and registers the {@code fable} expansion when present.
     */
    public static PlaceholderHook create(Logger logger, SnapshotHub snapshots, String pluginVersion) {
        if (!Reflect.pluginPresent("PlaceholderAPI")) {
            return new PlaceholderHook(null);
        }
        Object registered = registerExpansion(snapshots, pluginVersion);
        if (registered == null) {
            logger.warning("PlaceholderAPI present but the %fable_*% expansion failed to register - "
                    + "placeholders unavailable this session.");
            return new PlaceholderHook(null);
        }
        logger.info("PlaceholderAPI hooked - %fable_*% expansion registered.");
        return new PlaceholderHook(registered);
    }

    /**
     * Unregisters the expansion (plugin disable/reload). PlaceholderAPI may already be disabled
     * during server shutdown; per the Reflect swallow policy (ref-integrations §12.4) a reflective
     * failure here never blocks the teardown path.
     */
    public void unregister() {
        if (expansion == null) {
            return;
        }
        try {
            expansion.getClass().getMethod("unregister").invoke(expansion);
        } catch (ReflectiveOperationException | LinkageError alreadyGone) {
            // PAPI unloaded first during shutdown; the expansion died with it.
        }
    }

    /** FQN-loads {@link FableExpansion} and registers it; {@code null} on any reflective failure. */
    private static Object registerExpansion(SnapshotHub snapshots, String pluginVersion) {
        try {
            Class<?> type = Class.forName(EXPANSION_FQN);
            Object candidate = type.getConstructor(SnapshotHub.class, String.class)
                    .newInstance(snapshots, pluginVersion);
            Object ok = type.getMethod("register").invoke(candidate);
            return Boolean.TRUE.equals(ok) ? candidate : null;
        } catch (ReflectiveOperationException | LinkageError failed) {
            return null;
        }
    }
}
