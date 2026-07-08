package dev.fablemc.factions.platform.gui;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The marker {@link InventoryHolder} that identifies a FableFactions menu across the whole
 * version range (CONTRACTS §3, proposal-C §7.5). Custom-holder identification is universal
 * 1.7.10 → 26.x and sidesteps the 1.21 {@code InventoryView} class→interface hazard
 * entirely: a click is "ours" iff {@code event.getInventory().getHolder() instanceof
 * MenuHolder}, with no view-method call.
 *
 * <p>Carries the menu id and the owning player's UUID so the click router knows which menu
 * fired and for whom. The inventory reference is set once, immediately after
 * {@code createInventory}, resolving the holder/inventory bootstrap cycle.
 *
 * <p>Owning thread(s): the owner's region/main thread. Mutability class: confined (the
 * inventory back-reference is written once on the owning thread).
 */
public final class MenuHolder implements InventoryHolder {

    private final String menuId;
    private final UUID owner;
    private @Nullable Inventory inventory;

    public MenuHolder(@NotNull String menuId, @NotNull UUID owner) {
        this.menuId = Objects.requireNonNull(menuId, "menuId");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** The id of the menu this holder identifies. */
    public @NotNull String menuId() {
        return menuId;
    }

    /** The UUID of the player this menu was opened for. */
    public @NotNull UUID owner() {
        return owner;
    }

    /** Binds the created inventory back to this holder (called once after createInventory). */
    public void setInventory(@NotNull Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("MenuHolder inventory not yet bound (call setInventory first)");
        }
        return inventory;
    }
}
