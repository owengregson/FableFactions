package dev.fablemc.factions.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.fablemc.factions.kernel.config.ConfigImage;

/**
 * Pins the two-layer write model (proposal-C §9.1): an {@link OverlayStore} value wins over the
 * on-disk file when stamped onto the base config before the parse, and survives a save/load round
 * trip.
 */
final class OverlayStoreTest {

    @Test
    void overlayValueWinsOverTheBaseFile(@TempDir File dir) {
        YamlConfiguration base = YamlConfiguration.loadConfiguration(
                new StringReader("factions:\n  max-members: 10\n"));

        OverlayStore overlay = new OverlayStore(new File(dir, "overrides.yml"));
        overlay.set("factions.max-members", 99);
        overlay.applyOnto(base);

        assertEquals(99, base.getInt("factions.max-members"), "overlay must win over the file value");

        List<String> issues = new ArrayList<>();
        ConfigImage image = ConfigParser.parse(new ConfigParser.Sources(base, new YamlConfiguration(),
                new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration(),
                new YamlConfiguration()), null, issues);
        assertEquals(99, image.limits().maxMembers(), "the parsed image reflects the overlay");
        assertTrue(issues.isEmpty(), () -> issues.toString());
    }

    @Test
    void overlaySurvivesSaveAndReload(@TempDir File dir) throws IOException {
        File file = new File(new File(dir, "state"), "overrides.yml");
        OverlayStore writer = new OverlayStore(file);
        writer.set("factions.chat.show-tag", true);
        writer.save();
        assertTrue(file.isFile(), "save must create the overrides file under state/");

        OverlayStore reader = new OverlayStore(file);
        reader.load();
        assertTrue(reader.contains("factions.chat.show-tag"));
        assertEquals(Boolean.TRUE, reader.get("factions.chat.show-tag"));

        reader.clear("factions.chat.show-tag");
        assertFalse(reader.contains("factions.chat.show-tag"), "clear must remove the overlay value");
    }
}
