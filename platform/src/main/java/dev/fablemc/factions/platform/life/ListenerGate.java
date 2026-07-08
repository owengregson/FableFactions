package dev.fablemc.factions.platform.life;

import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/**
 * Capability-gated listener registration recorded in a {@link Scope} (CONTRACTS §3,
 * AM-13). The supplier is invoked — and the listener registered — ONLY when the
 * gating capability is {@code true}; otherwise nothing links or registers.
 *
 * <p>This is the enrolment side of the {@code @ProbeGated} cross-check: a probe-gated
 * listener (one that mentions a post-floor Bukkit type) reaches Bukkit's
 * {@code registerEvents} exclusively through here, so a server missing the type never
 * constructs — let alone reflects over — the hazardous class (GAP 1 avoidance).
 *
 * <p>Owning thread(s): the plugin main/boot thread. Mutability class: static-only utility.
 */
public final class ListenerGate {

    private ListenerGate() {}

    /**
     * When {@code capability} is {@code true}, obtains the listener from {@code maker}
     * and registers it in {@code scope}; when {@code false}, does nothing (the supplier
     * is never invoked, so the probe-gated class is never even loaded).
     *
     * @param scope        the owning scope the registration is recorded in
     * @param capability   the boot-resolved capability boolean gating the registration
     * @param fqnOrLocal   a human label for the boot report / diagnostics (never used to load)
     * @param maker        supplies the listener instance, invoked only when {@code capability}
     */
    public static void register(
            @NotNull Scope scope,
            boolean capability,
            @NotNull String fqnOrLocal,
            @NotNull Supplier<Listener> maker) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fqnOrLocal, "fqnOrLocal");
        Objects.requireNonNull(maker, "maker");
        if (!capability) {
            return;
        }
        scope.listen(maker.get());
    }
}
