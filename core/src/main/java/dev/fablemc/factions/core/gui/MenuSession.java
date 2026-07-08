package dev.fablemc.factions.core.gui;

import java.util.Objects;
import java.util.UUID;

import dev.fablemc.factions.platform.gui.MenuHolder;

/**
 * The per-player open-menu state (proposal-C §7.5), the opaque handle {@link
 * dev.fablemc.factions.core.session.PlayerSession#guiSession()} carries. It records which menu the
 * player currently has open (for {@code REFRESH} / re-render) and the {@link MenuHolder} identifying
 * the live inventory.
 *
 * <p><b>Owning thread(s):</b> created, mutated and read only on the owning player's region thread
 * (it lives inside the confined {@code PlayerSession}, AM-14). <b>Mutability:</b> confined mutable
 * value — never touch it from another thread.
 */
public final class MenuSession {

    private final UUID player;
    private String menuId;
    private MenuHolder holder;

    /** Opens a session tracking {@code menuId} for {@code player}, identified by {@code holder}. */
    public MenuSession(UUID player, String menuId, MenuHolder holder) {
        this.player = Objects.requireNonNull(player, "player");
        this.menuId = Objects.requireNonNull(menuId, "menuId");
        this.holder = Objects.requireNonNull(holder, "holder");
    }

    /** The owning player's UUID. */
    public UUID player() {
        return player;
    }

    /** The id of the menu currently open. */
    public String menuId() {
        return menuId;
    }

    /** The holder identifying the live inventory. */
    public MenuHolder holder() {
        return holder;
    }

    /** Re-points this session at a newly opened menu (menu navigation / refresh). */
    public void update(String menuId, MenuHolder holder) {
        this.menuId = Objects.requireNonNull(menuId, "menuId");
        this.holder = Objects.requireNonNull(holder, "holder");
    }
}
