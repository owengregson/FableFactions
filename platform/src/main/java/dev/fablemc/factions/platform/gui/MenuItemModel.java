package dev.fablemc.factions.platform.gui;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One immutable, pure-data GUI item (CONTRACTS §3, proposal-C §7.5). Parsed from
 * {@code gui.yml} by {@code :core} and rendered per-viewer from the snapshot; this record
 * carries only the declarative shape — no Bukkit type, no Adventure {@code Component} (the
 * {@code name}/{@code lore} are raw MiniMessage source strings core renders later, keeping
 * Adventure out of {@code platform.gui} per AM-1).
 *
 * <p>Owning thread(s): any (immutable). Mutability class: immutable value.
 *
 * @param slot     the 0-based inventory slot
 * @param material the MODERN material name (resolved to an icon via {@code LegacyMaterials})
 * @param name     the raw display-name source (MiniMessage), never null (may be empty)
 * @param lore     the raw lore source lines (MiniMessage), never null (may be empty)
 * @param glow     whether the icon renders with the enchant glint
 * @param action   the click action id (e.g. {@code RUN_COMMAND}); null ⇒ inert icon
 * @param actionData the action argument (a command, a target menu id, …); null when none
 */
public record MenuItemModel(
        int slot,
        @NotNull String material,
        @NotNull String name,
        @NotNull List<String> lore,
        boolean glow,
        @Nullable String action,
        @Nullable String actionData) {

    public MenuItemModel {
        if (material.isEmpty()) {
            throw new IllegalArgumentException("menu item material must not be empty");
        }
        lore = List.copyOf(lore);
    }
}
