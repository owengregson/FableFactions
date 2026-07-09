package dev.fablemc.factions.core.gui;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import dev.fablemc.factions.platform.gui.MenuItemModel;
import dev.fablemc.factions.platform.gui.MenuModel;

/**
 * Parses the {@code gui.yml} {@code menus:} tree into the immutable {@link MenuModel} catalog the
 * {@link MenuManager} renders (proposal-C §7.5, ref-resources.md §8). Each item's declarative shape
 * (slot / material / name / lore / glow / action) becomes a {@link MenuItemModel}; the per-action
 * data key ({@code command} / {@code menu} / {@code locale}) is folded into the single
 * {@code actionData} field {@link MenuManager} dispatches on. Titles, names and lore stay as raw
 * MiniMessage source (core renders them per-viewer — no Bukkit type or Adventure {@code Component}
 * appears here, AM-1).
 *
 * <p><b>Owning thread(s):</b> {@link #parse} / {@link #bundledDefaults} run on the boot (or reload
 * parse) thread; they read a classpath resource and do no Bukkit-runtime or kernel-state work.
 * <b>Mutability:</b> static-only; the produced map is immutable.
 *
 * <p>Parsing is best-effort: a malformed menu or item is skipped rather than aborting the load, so a
 * single bad entry never blanks the whole GUI. The menu id is the section key; item order follows
 * the file (a {@link YamlConfiguration} preserves insertion order).
 */
public final class MenuCatalog {

    private static final String RESOURCE = "/gui.yml";
    private static final int DEFAULT_SIZE = 54;

    private MenuCatalog() {
    }

    /**
     * Parses the {@code gui.menus} section of a loaded {@code gui.yml} root into an immutable
     * {@code menuId -> MenuModel} map. Returns an empty map when {@code fileRoot} is {@code null} or
     * carries no {@code gui.menus} section.
     */
    public static Map<String, MenuModel> parse(ConfigurationSection fileRoot) {
        if (fileRoot == null) {
            return Map.of();
        }
        ConfigurationSection menusSection = fileRoot.getConfigurationSection("gui.menus");
        if (menusSection == null) {
            return Map.of();
        }
        Map<String, MenuModel> menus = new LinkedHashMap<>();
        for (String menuId : menusSection.getKeys(false)) {
            ConfigurationSection menu = menusSection.getConfigurationSection(menuId);
            if (menu == null || menuId.isEmpty()) {
                continue;
            }
            MenuModel model = parseMenu(menuId, menu);
            if (model != null) {
                menus.put(menuId, model);
            }
        }
        return Map.copyOf(menus);
    }

    /**
     * Loads and parses the shipped {@code gui.yml} bundled on the classpath. This is the fallback
     * catalog the renderer uses until the boot layer parses the operator's data-folder {@code gui.yml}
     * and injects it; it never throws (a missing or unreadable resource yields an empty map).
     */
    public static Map<String, MenuModel> bundledDefaults() {
        try (InputStream in = MenuCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return Map.of();
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(YamlConfiguration.loadConfiguration(reader));
            }
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static MenuModel parseMenu(String menuId, ConfigurationSection menu) {
        String title = menu.getString("title", "");
        int size = menu.getInt("size", DEFAULT_SIZE);
        List<MenuItemModel> items = new ArrayList<>();
        ConfigurationSection itemsSection = menu.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String itemKey : itemsSection.getKeys(false)) {
                ConfigurationSection item = itemsSection.getConfigurationSection(itemKey);
                if (item == null) {
                    continue;
                }
                MenuItemModel parsed = parseItem(item);
                if (parsed != null) {
                    items.add(parsed);
                }
            }
        }
        try {
            return new MenuModel(menuId, size, title, items);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static MenuItemModel parseItem(ConfigurationSection item) {
        String material = item.getString("material", "");
        if (material.isEmpty()) {
            return null; // a materialless icon can't be rendered — skip it
        }
        int slot = item.getInt("slot", -1);
        String name = item.getString("name", "");
        List<String> lore = item.getStringList("lore");
        boolean glow = item.getBoolean("glow", false);
        String action = item.getString("action");
        String actionData = actionData(item);
        try {
            return new MenuItemModel(slot, material, name, lore, glow, action, actionData);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** The single action argument, folded from the per-action key {@code command} / {@code menu} / {@code locale}. */
    private static String actionData(ConfigurationSection item) {
        String command = item.getString("command");
        if (command != null) {
            return command;
        }
        String menu = item.getString("menu");
        if (menu != null) {
            return menu;
        }
        return item.getString("locale");
    }
}
