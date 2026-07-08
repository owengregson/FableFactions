package dev.fablemc.factions.core.integration.placeholderapi;

import java.util.logging.Logger;

import dev.fablemc.factions.core.integration.Reflect;

/**
 * The PlaceholderAPI presence hook (ref-integrations §3.1). The FableFactions expansion identifier
 * is {@code fable}, so all placeholders render as {@code %fable_<param>%} ({@link PlaceholderData}
 * resolves each).
 *
 * <p><b>Isolation note (proposal-C §10.3, W5):</b> {@code PlaceholderExpansion} is an
 * <em>abstract class</em>, not an interface, so it cannot be subclassed by a runtime dynamic proxy —
 * registering the expansion requires the typed {@code me.clip.placeholderapi} compile dependency.
 * This wave is reflection-only (no new compile deps), so the data provider ships complete and the
 * typed expansion registration is a documented W5 task. When PAPI is present this hook logs that the
 * provider is ready; the wiring flips on once the typed expansion class lands.
 *
 * <p><b>Owning thread(s):</b> the boot thread. <b>Mutability:</b> immutable (holds the ready flag).
 */
public final class PlaceholderHook {

    /** The FableFactions PlaceholderAPI expansion identifier (placeholders are {@code %fable_*%}). */
    public static final String IDENTIFIER = "fable";

    private final boolean ready;

    private PlaceholderHook(boolean ready) {
        this.ready = ready;
    }

    /** {@code true} when PlaceholderAPI is present and the {@code %fable_*%} data provider is live. */
    public boolean ready() {
        return ready;
    }

    /**
     * Detects PlaceholderAPI by presence only (the reference gates PAPI on presence, not the config
     * toggle — ref-integrations §3.1). Returns a hook whose {@link #ready()} reflects availability.
     */
    public static PlaceholderHook create(Logger logger) {
        if (!Reflect.pluginPresent("PlaceholderAPI")) {
            return new PlaceholderHook(false);
        }
        logger.info("PlaceholderAPI present — %fable_*% data provider ready "
                + "(typed expansion registration lands in W5).");
        return new PlaceholderHook(true);
    }
}
