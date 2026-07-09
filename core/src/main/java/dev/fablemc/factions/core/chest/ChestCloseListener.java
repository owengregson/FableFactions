package dev.fablemc.factions.core.chest;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Routes {@code InventoryCloseEvent} for a team-chest inventory into {@link ChestSessions#handleClose}
 * — the last-close commit + Folia exclusive-lock release (finding #12). A close is "ours" iff the
 * top inventory's holder is a {@link ChestSessions.ChestHolder}: universal custom-holder
 * identification with <b>no {@code InventoryView} call</b> (D-13/AM-16), matching how the chest
 * engine builds its inventories. Chests are editable (unlike read-only GUI menus), so they carry
 * their own holder and are intentionally NOT routed through the {@code GuiListener}/{@code MenuHolder}
 * path.
 *
 * <p><b>Owning thread(s):</b> the inventory event's region/main thread (the closing player's own
 * thread), where the confined chest session may be committed. <b>Mutability:</b> immutable (only the
 * injected engine). Floor-safe baseline listener — {@code InventoryCloseEvent} and every descriptor
 * used here is 1.7.10-present.
 */
public final class ChestCloseListener implements Listener {

    private final ChestSessions chests;

    public ChestCloseListener(ChestSessions chests) {
        this.chests = Objects.requireNonNull(chests, "chests");
    }

    /** Drops one viewer (and force-commits + evicts on the last close) when a chest view closes. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ChestSessions.ChestHolder && event.getPlayer() instanceof Player player) {
            chests.handleClose(player.getUniqueId());
        }
    }
}
