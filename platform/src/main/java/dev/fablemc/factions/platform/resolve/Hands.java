package dev.fablemc.factions.platform.resolve;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import dev.fablemc.factions.platform.probe.Probes;

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
        MethodType sig = MethodType.methodType(ItemStack.class);
        MethodHandle modern = Probes.virtualHandle(HumanEntity.class, "getItemInMainHand", sig);
        MODERN = modern != null;
        MAIN_HAND = modern != null ? modern : Probes.virtualHandle(HumanEntity.class, "getItemInHand", sig);
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
