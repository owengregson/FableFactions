package dev.fablemc.factions.core.messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;

import dev.fablemc.factions.core.messages.LocaleTables.Template;
import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * Loads the eight shipped locale bundles into an immutable {@link LocaleTables}, parsing every
 * MiniMessage template exactly ONCE (ARCHITECTURE AM-1). This is the only place the shaded
 * MiniMessage parser runs — {@link LocaleTables#render} never parses at send time.
 *
 * <p><b>Owning thread(s):</b> {@link #load} runs on the boot thread (or the reload parse thread);
 * it reads classpath resources and does no Bukkit-runtime or kernel-state work. <b>Mutability:</b>
 * static-only; the produced {@link LocaleTables} is immutable.
 *
 * <p>Templates are compiled by replacing each ordered {@code {placeholder}} with a single
 * private-use sentinel char BEFORE the MiniMessage parse, so a placeholder that sits inside a tag
 * span (e.g. {@code <gold>{name}</gold>}) is parsed in one piece and later substituted with the
 * arg as literal text.
 */
public final class CatalogLoader {

    /**
     * The shipped locale tags, in interned-index order (index 0 = {@code en}). English only for
     * now: the translations are not yet maintainable, so they were removed rather than shipped
     * stale — every locale request resolves to {@code en} via the waterfall. Re-add a tag here (and
     * its {@code messages_<tag>.yml}) once a bundle is translated and kept in parity with {@code en}.
     */
    public static final String[] SHIPPED_LOCALES = {
            "en"
    };

    private static final String RESOURCE_PREFIX = "messages/messages_";
    private static final String RESOURCE_SUFFIX = ".yml";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private CatalogLoader() {
    }

    /**
     * Loads every shipped locale via {@code resourceOpener} (which resolves a resource name to a
     * stream, e.g. {@code getResourceAsStream("/"+name)}) and returns the parse-once catalog with its
     * default locale resolved from {@code defaultLocaleTag}. A locale whose resource is missing or
     * unreadable is recorded in {@code issues} and skipped; {@code en} is always attempted so the
     * final fallback tier exists.
     */
    public static LocaleTables load(Function<String, InputStream> resourceOpener,
                                    String defaultLocaleTag, List<String> issues) {
        Objects.requireNonNull(resourceOpener, "resourceOpener");
        Objects.requireNonNull(issues, "issues");

        List<String> tags = new ArrayList<>();
        Map<String, Integer> tagIndex = new HashMap<>();
        List<Map<MessageKey, Template>> byLocale = new ArrayList<>();

        for (String locale : SHIPPED_LOCALES) {
            Map<MessageKey, Template> table = loadLocale(resourceOpener, locale, issues);
            if (table == null) {
                continue;
            }
            int idx = tags.size();
            tags.add(locale);
            tagIndex.put(LocaleTables.normalize(locale), idx);
            byLocale.add(table);
        }

        int enIdx = tagIndex.getOrDefault(LocaleTables.normalize("en"), 0);
        int defaultIdx = resolveDefault(defaultLocaleTag, tagIndex, enIdx);
        return new LocaleTables(tags.toArray(new String[0]), tagIndex, byLocale, defaultIdx, enIdx);
    }

    private static Map<MessageKey, Template> loadLocale(Function<String, InputStream> opener,
                                                        String locale, List<String> issues) {
        String name = RESOURCE_PREFIX + locale + RESOURCE_SUFFIX;
        InputStream in = opener.apply(name);
        if (in == null) {
            issues.add("locale bundle '" + name + "' not found on the classpath");
            return null;
        }
        YamlConfiguration cfg;
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            cfg = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException ex) {
            issues.add("could not read locale bundle '" + name + "': " + ex.getMessage());
            return null;
        }
        Map<MessageKey, Template> table = new HashMap<>();
        for (String path : cfg.getKeys(true)) {
            if (cfg.isString(path)) {
                table.put(MessageKey.of(path), compile(cfg.getString(path)));
            }
        }
        return table;
    }

    private static int resolveDefault(String defaultLocaleTag, Map<String, Integer> tagIndex, int enIdx) {
        if (defaultLocaleTag == null) {
            return enIdx;
        }
        String normalized = LocaleTables.normalize(defaultLocaleTag);
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
        return enIdx;
    }

    /**
     * Compiles one raw MiniMessage string into a {@link Template}: each ordered {@code {name}}
     * placeholder becomes a single private-use sentinel char, then the whole string is parsed once.
     */
    static Template compile(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        int argCount = 0;
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c == '{' && argCount < LocaleTables.MAX_PLACEHOLDERS) {
                int close = raw.indexOf('}', i + 1);
                if (close > i && isPlaceholderName(raw, i + 1, close)) {
                    sb.append((char) (LocaleTables.SENTINEL_BASE + argCount));
                    argCount++;
                    i = close + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        Component root = MINI.deserialize(sb.toString());
        return new Template(root, argCount);
    }

    private static boolean isPlaceholderName(String raw, int start, int end) {
        if (end <= start) {
            return false;
        }
        for (int i = start; i < end; i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
