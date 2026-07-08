package dev.fablemc.factions.platform.probe;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The primitive boot-time probes every resolver and {@link Capabilities} builds on
 * (CONTRACTS §3): "does this class / method / enum-constant exist on THIS server?".
 * A class either exists or it does not — version strings are never parsed for
 * behaviour (Mental rule, {@code mental-seam.md} §1).
 *
 * <p>Every probe catches {@link LinkageError} in addition to the ordinary reflective
 * exceptions: a partially-present or mismatched type surfaces as a {@code LinkageError}
 * (e.g. {@code NoClassDefFoundError} for an absent transitive type), and a probe that
 * let it escape would abort boot instead of degrading to a typed fallback.
 *
 * <p>Owning thread(s): the boot thread (resolvers call these once at class-load).
 * Mutability class: static-only utility.
 */
public final class Probes {

    private Probes() {}

    /** True iff {@code className} loads on this server. */
    public static boolean classPresent(@NotNull String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /** True iff {@code owner} declares a public {@code name(paramTypes...)} method. */
    public static boolean methodPresent(@NotNull Class<?> owner, @NotNull String name, @NotNull Class<?>... paramTypes) {
        try {
            owner.getMethod(name, paramTypes);
            return true;
        } catch (NoSuchMethodException | LinkageError absent) {
            return false;
        }
    }

    /**
     * True iff {@code className} loads AND declares a public {@code name(paramClassNames...)}
     * method. Both the owner and every parameter type are resolved BY NAME so a
     * sub-floor descriptor type is a quiet {@code false}, never a hard link at the probe.
     */
    public static boolean methodPresent(
            @NotNull String className, @NotNull String name, @NotNull String... paramClassNames) {
        try {
            Class<?> owner = Class.forName(className);
            Class<?>[] params = new Class<?>[paramClassNames.length];
            for (int i = 0; i < paramClassNames.length; i++) {
                params[i] = Class.forName(paramClassNames[i]);
            }
            owner.getMethod(name, params);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError absent) {
            return false;
        }
    }

    /**
     * The enum constant {@code name} of {@code enumType}, or {@code null} when this
     * server's copy of the enum lacks it — the sticky-{@code NoSuchFieldError} cure
     * ({@code mental-seam.md} §2b). Resolve once at boot, cache the nullable result,
     * and NEVER {@code getstatic} the constant directly.
     *
     * @param <E> the (floor-present) enum type
     */
    public static <E extends Enum<E>> @Nullable E enumConstant(@NotNull Class<E> enumType, @NotNull String name) {
        try {
            return Enum.valueOf(enumType, name);
        } catch (IllegalArgumentException | LinkageError absent) {
            return null;
        }
    }
}
