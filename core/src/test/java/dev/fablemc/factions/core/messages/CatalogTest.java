package dev.fablemc.factions.core.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.messages.LocaleTables.Template;
import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * Pins the message catalog: all eight shipped bundles carry the identical key set (the reference
 * parity contract), the locale waterfall (preferred → default → en → raw key) resolves correctly,
 * BCP-47 tags normalize to the right index, and a user-supplied arg is substituted as LITERAL text
 * (never re-parsed as MiniMessage — the tag-injection kill, AM-1). Reads the real {@code .yml}
 * resources off the classpath.
 */
final class CatalogTest {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static final Function<String, InputStream> RESOURCES =
            name -> CatalogTest.class.getResourceAsStream("/" + name);

    @Test
    void allEightBundlesHaveTheIdenticalKeySet() {
        TreeSet<String> reference = leafKeys("en");
        assertFalse(reference.isEmpty(), "en bundle must not be empty");
        for (String locale : CatalogLoader.SHIPPED_LOCALES) {
            TreeSet<String> keys = leafKeys(locale);
            TreeSet<String> missing = new TreeSet<>(reference);
            missing.removeAll(keys);
            TreeSet<String> extra = new TreeSet<>(keys);
            extra.removeAll(reference);
            assertTrue(missing.isEmpty() && extra.isEmpty(),
                    () -> locale + " diverges from en — missing=" + missing + " extra=" + extra);
        }
    }

    @Test
    void catalogLoadsEveryShippedLocaleWithoutIssues() {
        List<String> issues = new ArrayList<>();
        LocaleTables catalog = CatalogLoader.load(RESOURCES, "en", issues);
        assertTrue(issues.isEmpty(), () -> "catalog load issues: " + issues);
        assertEquals(CatalogLoader.SHIPPED_LOCALES.length, catalog.localeCount());
    }

    @Test
    void bcp47TagsNormalizeToTheRightIndex() {
        LocaleTables catalog = CatalogLoader.load(RESOURCES, "en", new ArrayList<>());
        int ptBr = catalog.localeIndex("pt-BR");
        assertEquals("pt-BR", catalog.tagAt(ptBr));
        assertEquals(ptBr, catalog.localeIndex("pt_BR"), "underscore form normalizes to the same index");
        assertEquals(catalog.localeIndex("de"), catalog.localeIndex("DE"), "case-insensitive");
        assertEquals(catalog.localeIndex("de"), catalog.localeIndex("de_AT"),
                "region-only miss degrades to the language");
        assertEquals(catalog.defaultLocale(), catalog.localeIndex("xx"), "unknown tag → default locale");
    }

    @Test
    void translatedKeyRendersInTheChosenLocale() {
        LocaleTables catalog = CatalogLoader.load(RESOURCES, "en", new ArrayList<>());
        int de = catalog.localeIndex("de");
        String rendered = LEGACY.serialize(catalog.render(de, MessageKey.of("faction.name-taken")));
        assertTrue(rendered.contains("Fraktion"), () -> "expected a German string, got: " + rendered);
    }

    @Test
    void argumentIsSubstitutedAsLiteralTextNotReparsedAsMiniMessage() {
        LocaleTables catalog = CatalogLoader.load(RESOURCES, "en", new ArrayList<>());
        // member.joined = "<green>You joined <yellow>{faction}</yellow>."
        Component out = catalog.render(catalog.localeIndex("en"), MessageKey.of("member.joined"),
                "<bold>Wolves");
        String legacy = LEGACY.serialize(out);
        assertTrue(legacy.contains("<bold>Wolves"), () -> "arg must be literal, got: " + legacy);
        assertFalse(legacy.contains("§l"), () -> "arg's <bold> must NOT become a format code: " + legacy);
        assertTrue(legacy.contains("You joined"), () -> "template text must survive: " + legacy);
    }

    @Test
    void localeWaterfallFallsThroughToEnThenRawKey() {
        // Two synthetic locales: default = de (index 1), en = index 0. 'only-en' is absent in de.
        MessageKey both = MessageKey.of("k.both");
        MessageKey onlyEn = MessageKey.of("k.only-en");
        MessageKey missing = MessageKey.of("k.nowhere");

        Map<MessageKey, Template> en = new HashMap<>();
        en.put(both, CatalogLoader.compile("EN both"));
        en.put(onlyEn, CatalogLoader.compile("EN only"));
        Map<MessageKey, Template> de = new HashMap<>();
        de.put(both, CatalogLoader.compile("DE both"));

        Map<String, Integer> tagIndex = new HashMap<>();
        tagIndex.put("en", 0);
        tagIndex.put("de", 1);
        LocaleTables catalog = new LocaleTables(new String[] {"en", "de"}, tagIndex,
                List.of(en, de), /*defaultIdx=de*/ 1, /*enIdx*/ 0);

        assertEquals("DE both", LEGACY.serialize(catalog.render(1, both)));
        // absent in de → absent in default(de) → present in en tier
        assertEquals("EN only", LEGACY.serialize(catalog.render(1, onlyEn)));
        // absent everywhere → raw key text, never null
        assertEquals("k.nowhere", LEGACY.serialize(catalog.render(1, missing)));
        // out-of-range index → default locale (de)
        assertEquals("DE both", LEGACY.serialize(catalog.render(99, both)));
    }

    @Test
    void prefixKeyIsPresentAndParses() {
        LocaleTables catalog = CatalogLoader.load(RESOURCES, "en", new ArrayList<>());
        Component prefix = catalog.render(catalog.localeIndex("en"), MessageKey.of("prefix"));
        assertNotNull(prefix);
        assertTrue(LEGACY.serialize(prefix).contains("Factions"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private static TreeSet<String> leafKeys(String locale) {
        String name = "/messages/messages_" + locale + ".yml";
        InputStream in = CatalogTest.class.getResourceAsStream(name);
        assertNotNull(in, () -> "missing resource " + name);
        YamlConfiguration cfg;
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            cfg = YamlConfiguration.loadConfiguration(reader);
        } catch (Exception ex) {
            throw new AssertionError("could not read " + name, ex);
        }
        TreeSet<String> keys = new TreeSet<>();
        for (String path : cfg.getKeys(true)) {
            if (cfg.isString(path)) {
                keys.add(path);
            }
        }
        return keys;
    }
}
