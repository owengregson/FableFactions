package dev.fablemc.factions.platform.probe;

import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A manifest entry whose handle MUST resolve on every supported server — a miss is a
 * mapping break, not an expected absence (Mental Tier-2, CONTRACTS §3). The resolution
 * TECHNIQUE is supplied by the caller (a resolver name-probe); {@code Required} never
 * re-derives it, it only owns the outcome.
 *
 * <p>A missed {@link #owned owned} entry disables its feature (a plain feature-id string;
 * one loud log at {@link PlatformProfile#resolveDisabled}); a missed
 * {@link #engineCritical} entry declares no owner and fails the boot instead. The value
 * is captured once at construction; the public surface is presence-typed.
 *
 * <p>Owning thread(s): boot thread. Mutability class: immutable value.
 *
 * @param <T> the resolved handle type
 */
public final class Required<T> implements ManifestEntry {

    private final String name;
    private final @Nullable String owner; // null ⇒ engine-critical (boot fail on absence)
    private final @Nullable T value;

    private Required(@NotNull String name, @Nullable String owner, @Nullable T value) {
        this.name = name;
        this.owner = owner;
        this.value = value;
    }

    /** Resolve now via {@code resolver}; a {@code null} result disables feature {@code owner}. */
    public static <T> @NotNull Required<T> owned(
            @NotNull String name, @NotNull String owner, @NotNull Supplier<@Nullable T> resolver) {
        return new Required<>(name, owner, resolver.get());
    }

    /** A required boolean fact: present iff {@code fact}; {@code false} disables feature {@code owner}. */
    public static @NotNull Required<Boolean> owned(@NotNull String name, @NotNull String owner, boolean fact) {
        return new Required<>(name, owner, fact ? Boolean.TRUE : null);
    }

    /** Resolve now; a {@code null} result fails the boot (engine-critical, no owner). */
    public static <T> @NotNull Required<T> engineCritical(
            @NotNull String name, @NotNull Supplier<@Nullable T> resolver) {
        return new Required<>(name, null, resolver.get());
    }

    /** A required boolean fact whose absence fails the boot: present iff {@code fact}. */
    public static @NotNull Required<Boolean> engineCritical(@NotNull String name, boolean fact) {
        return new Required<>(name, null, fact ? Boolean.TRUE : null);
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public boolean present() {
        return value != null;
    }

    /** Whether a miss fails the boot rather than disabling a single feature. */
    public boolean engineCritical() {
        return owner == null;
    }

    /** The feature disabled when this entry is absent, or {@code null} when engine-critical. */
    public @Nullable String owner() {
        return owner;
    }

    /** The resolved handle; call only when {@link #present()}. */
    public @NotNull T get() {
        if (value == null) {
            throw new IllegalStateException("required platform handle " + name + " is absent");
        }
        return value;
    }

    @Override
    public @NotNull String describe() {
        if (present()) {
            return name + "=present";
        }
        return name + (engineCritical() ? "=MISSING(engine-critical)" : "=MISSING(disables " + owner + ")");
    }
}
