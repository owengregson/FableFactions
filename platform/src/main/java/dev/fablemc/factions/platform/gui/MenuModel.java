package dev.fablemc.factions.platform.gui;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * One immutable, pure-data GUI menu (CONTRACTS §3, proposal-C §7.5). Parsed from
 * {@code gui.yml} by {@code :core}; the size is normalised to a chest-legal multiple of 9
 * in {@code [9, 54]}. The {@code title} is the raw MiniMessage source core renders per
 * viewer — no Bukkit type or Adventure {@code Component} appears here (AM-1).
 *
 * <p>Owning thread(s): any (immutable). Mutability class: immutable value.
 *
 * @param id    the stable menu id (referenced by {@code OPEN_MENU} actions)
 * @param size  the inventory size, a multiple of 9 in {@code [9, 54]}
 * @param title the raw title source (MiniMessage)
 * @param items the item models (immutable copy)
 */
public record MenuModel(
        @NotNull String id,
        int size,
        @NotNull String title,
        @NotNull List<MenuItemModel> items) {

    public MenuModel {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("menu id must not be empty");
        }
        size = normalizeSize(size);
        items = List.copyOf(items);
    }

    /** Rounds {@code rawSize} up to a chest-legal multiple of 9 in {@code [9, 54]}. */
    public static int normalizeSize(int rawSize) {
        if (rawSize <= 9) {
            return 9;
        }
        if (rawSize >= 54) {
            return 54;
        }
        int rows = (rawSize + 8) / 9;
        return rows * 9;
    }
}
