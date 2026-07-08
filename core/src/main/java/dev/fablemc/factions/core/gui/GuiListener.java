package dev.fablemc.factions.core.gui;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

import dev.fablemc.factions.platform.gui.MenuHolder;

/**
 * The GUI click/drag/close router (proposal-C §7.5/§8.1). A menu is "ours" iff the view's top
 * inventory holder is a {@link MenuHolder} — universal custom-holder identification with <b>no
 * {@code InventoryView} call</b>, sidestepping the 1.21 view class→interface hazard entirely. Every
 * click and drag inside our menu is cancelled (menus are read-only, and cancelling the whole click
 * also neutralises number-key / off-hand swaps that would otherwise shovel items in — AM-16); a
 * top-slot click is forwarded to {@link MenuManager#handleClick} for its action, and a close is
 * forwarded to {@link MenuManager#handleClose}.
 *
 * <p><b>Owning thread(s):</b> the inventory event's region/main thread (the clicker's own thread),
 * where the confined {@link MenuSession} may be touched. <b>Mutability:</b> immutable (only the
 * injected manager). Floor-safe baseline listener — every event and field descriptor is 1.7.10-present.
 */
public final class GuiListener implements Listener {

    private final MenuManager manager;

    public GuiListener(MenuManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    /** Cancels the click and, for a top-slot click, runs the item's action. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof MenuHolder menuHolder)) {
            return;
        }
        // Cancel unconditionally: menus are read-only, and this also blocks hotbar / off-hand swaps.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getInventory().getSize()) {
            return; // a click in the player's own inventory — cancelled, no action
        }
        manager.handleClick(player, menuHolder, rawSlot);
    }

    /** Cancels any drag that touches one of our menus (read-only). */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    /** Drops the tracked open-menu state when the player closes one of our menus. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder
                && event.getPlayer() instanceof Player player) {
            manager.handleClose(player.getUniqueId());
        }
    }
}
