package dev.fablemc.factions.core.gui;

import org.bukkit.entity.Player;

/**
 * The GUI-open seam the menu-facing commands ({@code /f gui}, {@code /f language}, and the bare
 * {@code /f} default-menu path) call (ref-commands-core.md §7.16/§7.31, ref-commands-misc.md §7).
 * Menus are config-driven; the command layer only asks this seam to open one and the implementation
 * (W3e {@code MenuManager}) renders it from {@code gui.yml} on the player's region thread.
 *
 * <p><b>Owning thread(s):</b> called from a command {@code perform} on the player's region/main
 * thread. <b>Mutability:</b> the implementation tracks the open menu per player, confined to the
 * region/main thread; this interface is a pure seam.
 */
public interface Menus {

    /**
     * Opens the configured default menu for {@code player}; returns {@code false} when the GUI system
     * is disabled or the default menu is not configured (the bare {@code /f} path falls back to help).
     */
    boolean openDefault(Player player);

    /**
     * Opens the menu with id {@code menuId} for {@code player}; returns {@code false} when no such
     * menu is configured (the caller then reports {@code custom.gui.menu-not-found}).
     */
    boolean open(Player player, String menuId);

    /**
     * Opens the language-selector menu for {@code player}; returns {@code false} when the GUI or the
     * language override system is unavailable (the caller then prints the textual language status).
     */
    boolean openLanguage(Player player);
}
