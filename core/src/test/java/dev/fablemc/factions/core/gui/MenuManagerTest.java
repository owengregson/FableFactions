package dev.fablemc.factions.core.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.platform.gui.MenuModel;

/**
 * The Bukkit-free bits of the GUI renderer: {@link MenuModel#normalizeSize} slot math and the
 * in-house MiniMessage→legacy converter ({@link MenuManager#toLegacy}) that keeps {@code net.kyori}
 * out of {@code core.gui} (AM-1). Rendering itself needs a live server and is exercised on the matrix.
 */
class MenuManagerTest {

    @Test
    void sizeNormalisesToAChestLegalMultipleOfNine() {
        assertEquals(9, MenuModel.normalizeSize(1));
        assertEquals(9, MenuModel.normalizeSize(9));
        assertEquals(18, MenuModel.normalizeSize(10));
        assertEquals(18, MenuModel.normalizeSize(18));
        assertEquals(27, MenuModel.normalizeSize(19));
        assertEquals(54, MenuModel.normalizeSize(50));
        assertEquals(54, MenuModel.normalizeSize(54));
        assertEquals(54, MenuModel.normalizeSize(64)); // capped at a double chest
    }

    @Test
    void namedColoursMapToLegacyCodes() {
        assertEquals("§6Faction Info", MenuManager.toLegacy("<gold>Faction Info"));
        assertEquals("§7Power: §f{power}", MenuManager.toLegacy("<gray>Power: <white>{power}"));
        assertEquals("§cUnclaim", MenuManager.toLegacy("<red>Unclaim"));
    }

    @Test
    void decorationsMapAndClosingTagsAreDropped() {
        assertEquals("§lBold", MenuManager.toLegacy("<bold>Bold"));
        assertEquals("§aon", MenuManager.toLegacy("<green>on</green>"));
        assertEquals("§rreset", MenuManager.toLegacy("<reset>reset"));
    }

    @Test
    void gradientsAndUnknownTagsAreStripped() {
        assertEquals("Factions Control",
                MenuManager.toLegacy("<gradient:#f6d365:#fda085>Factions Control</gradient>"));
        assertEquals("plain", MenuManager.toLegacy("<hover:show_text:'x'>plain</hover>"));
    }

    @Test
    void hexTagsBecomeSectionXSequences() {
        assertEquals("§x§f§f§0§0§0§0Hi",
                MenuManager.toLegacy("<#ff0000>Hi"));
    }

    @Test
    void escapedAndUnclosedAnglesAreLiteral() {
        assertEquals("<not a tag>", MenuManager.toLegacy("\\<not a tag>"));
        assertEquals("a < b", MenuManager.toLegacy("a < b"));
    }

    @Test
    void plainTextPassesThroughUnchanged() {
        assertEquals("just text", MenuManager.toLegacy("just text"));
    }
}
