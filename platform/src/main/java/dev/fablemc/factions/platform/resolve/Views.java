package dev.fablemc.factions.platform.resolve;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Boot-resolved {@link MethodHandle}s over {@link InventoryView} plus rawSlot fallback
 * math (CONTRACTS §3, version-deltas Risk #2). In 1.21 {@code InventoryView} changed from
 * an abstract class to an interface, so a compiled {@code invokevirtual}/{@code invokeinterface}
 * against it throws {@code IncompatibleClassChangeError} on the other half of the range.
 * {@code findVirtual} dispatches correctly for either runtime shape, so every view-method
 * call goes through a handle here — never a direct invocation in shared code.
 *
 * <p>{@link #clickedInventory} prefers the native {@code getClickedInventory()} (probed)
 * and otherwise derives the clicked inventory from stable rawSlot arithmetic on
 * {@code InventoryEvent#getInventory()} / {@code getWhoClicked()}.
 *
 * <p>Owning thread(s): the caller's (GUI events on the region/main thread). Mutability
 * class: static-only, immutable handles.
 */
public final class Views {

    private static final @Nullable MethodHandle TOP;
    private static final @Nullable MethodHandle BOTTOM;
    private static final @Nullable MethodHandle TITLE;
    private static final @Nullable MethodHandle CLICKED;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        TOP = findVirtualQuiet(lookup, InventoryView.class, "getTopInventory",
                MethodType.methodType(Inventory.class));
        BOTTOM = findVirtualQuiet(lookup, InventoryView.class, "getBottomInventory",
                MethodType.methodType(Inventory.class));
        TITLE = findVirtualQuiet(lookup, InventoryView.class, "getTitle",
                MethodType.methodType(String.class));
        CLICKED = findVirtualQuiet(lookup, InventoryClickEvent.class, "getClickedInventory",
                MethodType.methodType(Inventory.class));
    }

    private Views() {}

    /** The top inventory of {@code view} (ICCE-safe via a handle). */
    public static @Nullable Inventory topInventory(@NotNull InventoryView view) {
        return invokeInventory(TOP, view);
    }

    /** The bottom (player) inventory of {@code view} (ICCE-safe via a handle). */
    public static @Nullable Inventory bottomInventory(@NotNull InventoryView view) {
        return invokeInventory(BOTTOM, view);
    }

    /** The view title (ICCE-safe via a handle), or {@code null} if the accessor is absent. */
    public static @Nullable String title(@NotNull InventoryView view) {
        if (TITLE == null) {
            return null;
        }
        try {
            return (String) TITLE.invoke(view);
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * The inventory that was clicked: the native {@code getClickedInventory()} when present,
     * otherwise rawSlot arithmetic — {@code rawSlot < top.getSize()} ⇒ the top inventory,
     * a negative rawSlot ⇒ {@code null} (clicked outside), else the clicker's own inventory.
     */
    public static @Nullable Inventory clickedInventory(@NotNull InventoryClickEvent event) {
        if (CLICKED != null) {
            try {
                return (Inventory) CLICKED.invoke(event);
            } catch (Throwable ignored) {
                // fall through to rawSlot math
            }
        }
        int raw = event.getRawSlot();
        if (raw < 0) {
            return null;
        }
        Inventory top = event.getInventory();
        if (raw < top.getSize()) {
            return top;
        }
        return event.getWhoClicked().getInventory();
    }

    /** Whether {@code event}'s rawSlot lies in the top inventory (stable rawSlot math). */
    public static boolean isTopSlot(@NotNull InventoryClickEvent event) {
        int raw = event.getRawSlot();
        return raw >= 0 && raw < event.getInventory().getSize();
    }

    /** For the boot report: whether the native clicked-inventory accessor resolved. */
    public static String describe() {
        return "clickedInventory=" + (CLICKED != null ? "native" : "rawSlot-fallback");
    }

    private static @Nullable Inventory invokeInventory(@Nullable MethodHandle handle, @NotNull InventoryView view) {
        if (handle == null) {
            return null;
        }
        try {
            return (Inventory) handle.invoke(view);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static @Nullable MethodHandle findVirtualQuiet(
            @NotNull MethodHandles.Lookup lookup, @NotNull Class<?> owner, @NotNull String name,
            @NotNull MethodType type) {
        try {
            return lookup.findVirtual(owner, name, type);
        } catch (ReflectiveOperationException | LinkageError absent) {
            return null;
        }
    }
}
