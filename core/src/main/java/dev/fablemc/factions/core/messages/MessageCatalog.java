package dev.fablemc.factions.core.messages;

import net.kyori.adventure.text.Component;

import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * The locale-aware, parse-once message catalog: turns a {@link MessageKey} plus positional
 * arguments into a rendered (shaded) Adventure {@link Component} in the recipient's locale
 * (ARCHITECTURE AM-1, CONTRACTS §4). This is the INTERFACE only; the implementation lands in W3d
 * ({@code core.messages.LocaleTables}) and owns the eight shipped locale bundles.
 *
 * <p><b>Owning thread(s):</b> {@link #render} is called from the message layer on any thread (it is
 * pure and side-effect-free); {@link #localeIndex}/{@link #defaultLocale} on the boot and command
 * threads. <b>Mutability:</b> implementations are immutable after load.
 *
 * <p><b>Zero-per-send parse contract.</b> Every MiniMessage template is parsed <b>once at catalog
 * load</b> into a per-{@code (locale, key)} pre-parsed {@link Component} (with ordered placeholder
 * slots). {@link #render} therefore does NO MiniMessage parsing per call — it composes the
 * pre-parsed template with {@code args}, substituting the template's ordered {@code {placeholder}}
 * tokens positionally ({@code args[0]} → the first placeholder, and so on). This keeps the chat /
 * feedback fan-out allocation-light (AM-1). A missing {@code (locale, key)} falls back to the
 * {@linkplain #defaultLocale() default locale}, then to a component of the raw key text, so a
 * lookup never returns {@code null}.
 */
public interface MessageCatalog {

    /**
     * Renders {@code key} in the locale interned at {@code localeIdx} with {@code args} substituted
     * into its ordered placeholders. Never returns {@code null}; unknown keys degrade to the raw key
     * text. {@code localeIdx} out of range falls back to the default locale.
     */
    Component render(int localeIdx, MessageKey key, String... args);

    /**
     * The interned index for a BCP-47 language tag (e.g. {@code "en"}, {@code "pt-BR"}), or the
     * {@linkplain #defaultLocale() default locale} index when the tag is unknown or unsupported.
     */
    int localeIndex(String bcp47Tag);

    /** The server default locale index (from {@code factions.language.default}). */
    int defaultLocale();
}
