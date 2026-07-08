package dev.fablemc.factions.platform.resolve;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cross-version main-hand item read (CONTRACTS §3, version-deltas §3.8). The dual-wield
 * accessor {@code HumanEntity#getItemInMainHand()} is 1.9+; below it the single-hand
 * {@code getItemInHand()} is the only shape. Both are resolved ONCE at class load via
 * {@link MethodHandle}; both return {@code ItemStack} and take {@code HumanEntity}, so
 * neither descriptor references a post-floor type.
 *
 * <p>Owning thread(s): the caller's (reads a live entity). Mutability class: static-only,
 * immutable handle.
 */
public final class Hands {

    private static final @Nullable MethodHandle MAIN_HAND;
    private static final boolean MODERN;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType sig = MethodType.methodType(ItemStack.class);
        MethodHandle handle = null;
        boolean modern = false;
        try {
            handle = lookup.findVirtual(HumanEntity.class, "getItemInMainHand", sig);
            modern = true;
        } catch (ReflectiveOperationException | LinkageError mainHandAbsent) {
            try {
                handle = lookup.findVirtual(HumanEntity.class, "getItemInHand", sig);
                modern = false;
            } catch (ReflectiveOperationException | LinkageError legacyAbsent) {
                handle = null;
            }
        }
        MAIN_HAND = handle;
        MODERN = modern;
    }

    private Hands() {}

    /** The item in {@code human}'s main hand, or {@code null} if neither accessor resolved. */
    public static @Nullable ItemStack mainHand(@NotNull HumanEntity human) {
        if (MAIN_HAND == null) {
            return null;
        }
        try {
            return (ItemStack) MAIN_HAND.invoke(human);
        } catch (Throwable failure) {
            throw new IllegalStateException("Hands.mainHand invocation failed", failure);
        }
    }

    /** For the boot report: which hand accessor this server uses. */
    public static String describe() {
        if (MAIN_HAND == null) {
            return "none";
        }
        return MODERN ? "getItemInMainHand()" : "getItemInHand() (pre-1.9)";
    }
}
