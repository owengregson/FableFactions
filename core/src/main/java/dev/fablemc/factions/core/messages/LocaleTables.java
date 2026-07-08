package dev.fablemc.factions.core.messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * The loaded, parse-once message catalog: holds each shipped locale's {@link MessageKey} → template
 * table and renders a key + positional args into a (shaded) Adventure {@link Component} with ZERO
 * per-send MiniMessage parsing (ARCHITECTURE AM-1, CONTRACTS §4). Every template was parsed ONCE at
 * load ({@link CatalogLoader}); placeholders survive parsing as single private-use sentinel chars,
 * and {@link #render} composes the pre-parsed tree by substituting those sentinels with the args as
 * <em>literal</em> text — so user text can never inject MiniMessage tags.
 *
 * <p><b>Owning thread(s):</b> {@link #render} is pure and called on any thread; {@link #localeIndex}
 * on boot/command threads. <b>Mutability:</b> immutable after construction (all tables frozen).
 *
 * <p>Locale resolution waterfall (proposal-C §9.2): a missing {@code (locale, key)} falls to the
 * {@linkplain #defaultLocale() default locale}, then to {@code en}, then to a component of the raw
 * key text — a lookup never returns {@code null}. {@link #localeIndex} normalizes a BCP-47 tag
 * (lower-cased, {@code _}→{@code -}, {@code pt-BR}-aware) to an interned index, degrading a
 * region-only miss to the language and finally to the default locale.
 */
public final class LocaleTables implements MessageCatalog {

    /** First private-use code point used as a placeholder marker; occurrence i → {@code BASE+i}. */
    static final char SENTINEL_BASE = '\uE000';

    /** Max placeholders a single template may carry (private-use runway is far larger). */
    static final int MAX_PLACEHOLDERS = 256;

    /** A single compiled template: the pre-parsed component tree plus its placeholder count. */
    public record Template(Component root, int argCount) {
    }

    private final String[] tags;                      // interned index -> original tag ("pt-BR")
    private final Map<String, Integer> tagIndex;      // normalized tag / language -> index
    private final List<Map<MessageKey, Template>> byLocale;
    private final int defaultIdx;
    private final int enIdx;

    LocaleTables(String[] tags, Map<String, Integer> tagIndex,
                 List<Map<MessageKey, Template>> byLocale, int defaultIdx, int enIdx) {
        this.tags = tags;
        this.tagIndex = tagIndex;
        this.byLocale = byLocale;
        this.defaultIdx = defaultIdx;
        this.enIdx = enIdx;
    }

    @Override
    public Component render(int localeIdx, MessageKey key, String... args) {
        Objects.requireNonNull(key, "key");
        int idx = (localeIdx >= 0 && localeIdx < byLocale.size()) ? localeIdx : defaultIdx;
        Template template = lookup(idx, key);
        if (template == null && idx != defaultIdx) {
            template = lookup(defaultIdx, key);
        }
        if (template == null && enIdx != defaultIdx) {
            template = lookup(enIdx, key);
        }
        if (template == null) {
            return Component.text(key.key());
        }
        if (template.argCount() == 0) {
            return template.root();
        }
        String[] safeArgs = args != null ? args : new String[0];
        return substitute(template.root(), safeArgs, template.argCount());
    }

    @Override
    public int localeIndex(String bcp47Tag) {
        if (bcp47Tag == null) {
            return defaultIdx;
        }
        String normalized = normalize(bcp47Tag);
        Integer exact = tagIndex.get(normalized);
        if (exact != null) {
            return exact;
        }
        int dash = normalized.indexOf('-');
        if (dash > 0) {
            Integer language = tagIndex.get(normalized.substring(0, dash));
            if (language != null) {
                return language;
            }
        }
        return defaultIdx;
    }

    @Override
    public int defaultLocale() {
        return defaultIdx;
    }

    /** The original tag interned at {@code idx} (for the boot report / GUI); {@code null} if invalid. */
    public String tagAt(int idx) {
        return idx >= 0 && idx < tags.length ? tags[idx] : null;
    }

    /** The number of loaded locales (for tests / boot report). */
    public int localeCount() {
        return tags.length;
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────

    private Template lookup(int idx, MessageKey key) {
        if (idx < 0 || idx >= byLocale.size()) {
            return null;
        }
        return byLocale.get(idx).get(key);
    }

    static String normalize(String tag) {
        return tag.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Walks the pre-parsed template tree, replacing sentinel markers in text content with the
     * matching arg as literal-text children (inheriting the surrounding style). Returns the input
     * node unchanged when nothing in its subtree references a placeholder, so shared prefixes and
     * un-templated branches are not re-allocated.
     */
    private static Component substitute(Component node, String[] args, int argCount) {
        List<Component> children = node.children();
        if (node instanceof TextComponent text && containsSentinel(text.content(), argCount)) {
            TextComponent.Builder builder = Component.text().style(node.style());
            appendSplit(builder, text.content(), args, argCount);
            for (Component child : children) {
                builder.append(substitute(child, args, argCount));
            }
            return builder.build();
        }
        if (children.isEmpty()) {
            return node;
        }
        List<Component> replaced = null;
        for (int i = 0; i < children.size(); i++) {
            Component child = children.get(i);
            Component mapped = substitute(child, args, argCount);
            if (mapped != child && replaced == null) {
                replaced = new ArrayList<>(children);
            }
            if (replaced != null) {
                replaced.set(i, mapped);
            }
        }
        return replaced == null ? node : node.children(replaced);
    }

    private static boolean containsSentinel(String content, int argCount) {
        for (int i = 0; i < content.length(); i++) {
            if (isSentinel(content.charAt(i), argCount)) {
                return true;
            }
        }
        return false;
    }

    private static void appendSplit(TextComponent.Builder builder, String content,
                                    String[] args, int argCount) {
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (isSentinel(c, argCount)) {
                if (literal.length() > 0) {
                    builder.append(Component.text(literal.toString()));
                    literal.setLength(0);
                }
                int slot = c - SENTINEL_BASE;
                String value = slot < args.length && args[slot] != null ? args[slot] : "";
                builder.append(Component.text(value));
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            builder.append(Component.text(literal.toString()));
        }
    }

    private static boolean isSentinel(char c, int argCount) {
        return c >= SENTINEL_BASE && c < SENTINEL_BASE + argCount;
    }
}
