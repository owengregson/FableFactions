package dev.fablemc.factions.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.config.ConfigImage;

/**
 * Pins the config parser: warn-and-fallback readers over an empty input reproduce
 * {@link ConfigImage#defaults()} byte-for-byte (deep equality over records + arrays), the aliased
 * max-power keys collapse to one canonical value, and a wrong-typed key degrades to its default
 * while recording an issue.
 */
final class ConfigParserTest {

    private static ConfigImage parse(YamlConfiguration config, List<String> issues) {
        ConfigParser.Sources sources = new ConfigParser.Sources(config, new YamlConfiguration(),
                new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration(),
                new YamlConfiguration());
        return ConfigParser.parse(sources, null, issues);
    }

    @Test
    void parsingEmptyConfigReproducesDefaults() {
        List<String> issues = new ArrayList<>();
        ConfigImage parsed = ConfigParser.parse(ConfigParser.Sources.empty(), null, issues);
        assertTrue(issues.isEmpty(), () -> "absent keys must not warn: " + issues);
        assertDeepEquals(ConfigImage.defaults(), parsed, "ConfigImage");
    }

    @Test
    void aliasedMaxPowerKeysCollapseToOneCanonicalValue() {
        // Only the constraints alias is set: BOTH per-player-max and the constraint must read it.
        List<String> issues = new ArrayList<>();
        YamlConfiguration cfg = load("factions:\n  power:\n    constraints:\n      max-power: 25.0\n");
        ConfigImage image = parse(cfg, issues);
        assertEquals(25.0, image.power().perPlayerMax(), 0.0);
        assertEquals(25.0, image.power().maxPower(), 0.0);
        assertTrue(issues.isEmpty(), () -> "single alias must not warn: " + issues);
    }

    @Test
    void disagreeingMaxPowerAliasesWarnAndPreferPerPlayerMax() {
        List<String> issues = new ArrayList<>();
        YamlConfiguration cfg = load(
                "factions:\n  power:\n    per-player-max: 12.0\n    constraints:\n      max-power: 30.0\n");
        ConfigImage image = parse(cfg, issues);
        assertEquals(12.0, image.power().perPlayerMax(), 0.0);
        assertEquals(12.0, image.power().maxPower(), 0.0);
        assertFalse(issues.isEmpty(), "disagreeing aliases must record a deprecation issue");
    }

    @Test
    void wrongTypedKeyFallsBackToDefaultAndRecordsAnIssue() {
        List<String> issues = new ArrayList<>();
        YamlConfiguration cfg = load("factions:\n  max-members: not-a-number\n");
        ConfigImage image = parse(cfg, issues);
        assertEquals(50, image.limits().maxMembers(), "wrong-typed key must fall back to default");
        assertFalse(issues.isEmpty(), "a present-but-wrong-typed key must record an issue");
    }

    @Test
    void presentValuesOverrideDefaults() {
        List<String> issues = new ArrayList<>();
        YamlConfiguration cfg = load(
                "factions:\n  max-members: 12\n  chat:\n    show-tag: true\n"
                        + "  power:\n      multipliers:\n        zones:\n          warzone: 2.0\n");
        ConfigImage image = parse(cfg, issues);
        assertEquals(12, image.limits().maxMembers());
        assertTrue(image.chat().showTag());
        assertEquals(2.0, image.power().zoneMultipliers().warzone(), 0.0);
        assertEquals(2.0, image.baked().zoneMultiplier(1), 0.0, "baked warzone multiplier tracks config");
    }

    private static YamlConfiguration load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }

    // ── deep equality over records + arrays (record equals uses array identity, not contents) ──

    private static void assertDeepEquals(Object expected, Object actual, String path) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, path);
            return;
        }
        Class<?> type = expected.getClass();
        if (type.isArray()) {
            int len = Array.getLength(expected);
            assertEquals(len, Array.getLength(actual), path + ".length");
            for (int i = 0; i < len; i++) {
                assertDeepEquals(Array.get(expected, i), Array.get(actual, i), path + "[" + i + "]");
            }
            return;
        }
        if (type.isRecord()) {
            assertEquals(type, actual.getClass(), path + ".class");
            for (RecordComponent rc : type.getRecordComponents()) {
                assertDeepEquals(invoke(rc, expected), invoke(rc, actual), path + "." + rc.getName());
            }
            return;
        }
        assertTrue(Objects.equals(expected, actual),
                () -> path + " expected <" + expected + "> but was <" + actual + ">");
    }

    private static Object invoke(RecordComponent rc, Object owner) {
        try {
            return rc.getAccessor().invoke(owner);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("could not read record component " + rc.getName(), ex);
        }
    }
}
