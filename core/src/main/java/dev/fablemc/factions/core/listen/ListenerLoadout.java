package dev.fablemc.factions.core.listen;

import java.util.Objects;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.platform.life.ListenerGate;
import dev.fablemc.factions.platform.life.Scope;
import dev.fablemc.factions.platform.probe.Capabilities;

/**
 * The single registration table for every protection / event listener, consumed by boot (work order
 * W3a; proposal-C §8.1/§8.3). One call to {@link #install} enrols the whole set into a feature
 * {@link Scope} for symmetric teardown (a reload closes the scope whole — no re-register-over,
 * BUG-3): baseline (floor-safe) listeners register directly; capability-gated rows go through the
 * platform {@link ListenerGate} so a {@code @ProbeGated} class links ONLY when its capability holds
 * (AM-13); and the two events absent from the 1.13 compile API (raids, the {@code org.bukkit} mount
 * event) register reflectively via {@link ReflectiveEvents} behind the same capability check.
 *
 * <p><b>Owning thread(s):</b> {@link #install} runs on the plugin boot/main thread (registration is a
 * main-thread operation). <b>Mutability:</b> immutable — holds only the injected collaborators; Wave 4
 * constructs it and supplies the {@link TerritorySync} and update-notice hook.
 */
public final class ListenerLoadout {

    private final ListenerContext ctx;
    private final TerritorySync territorySync;
    private final @Nullable Consumer<Player> updateNotice;

    /**
     * @param territorySync the WorldGuard-sync seam ({@link TerritorySync#NONE} for pure-DB mode)
     * @param updateNotice  the op-join update-notice hook, or {@code null} to disable it
     */
    public ListenerLoadout(ListenerContext ctx, TerritorySync territorySync,
                           @Nullable Consumer<Player> updateNotice) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.territorySync = Objects.requireNonNull(territorySync, "territorySync");
        this.updateNotice = updateNotice;
    }

    /** Registers every listener into {@code scope}, gating each optional row on {@code caps}. */
    public void install(Plugin plugin, Scope scope, Capabilities caps) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(caps, "caps");

        // ── baseline (floor-safe on every supported server) ──────────────────────────────────
        scope.listen(new BuildProtectionListener(ctx, territorySync));
        scope.listen(new CombatProtectionListener(ctx));
        scope.listen(new ExplosionListener(ctx));
        scope.listen(new GriefListener(ctx));
        scope.listen(new InteractProtectionListener(ctx));
        scope.listen(new MoveListener(ctx));
        scope.listen(new ChatListener(ctx));
        scope.listen(new SessionListener(ctx, updateNotice));
        scope.listen(new DeathListener(ctx));

        // ── ally-unlock: only when a WorldGuard bridge mirrors claims (capability, not a probe) ──
        ListenerGate.register(scope, territorySync.syncsBuildProtection(), "AllyUnlockListener",
                () -> new AllyUnlockListener(ctx, territorySync));

        // ── probe-gated (@ProbeGated + directly-typed, via ListenerGate) ─────────────────────────
        ListenerGate.register(scope, caps.blockExplode(), "BlockExplodeListener",
                () -> new BlockExplodeListener(ctx));
        ListenerGate.register(scope, caps.armorStands(), "ArmorStandListener",
                () -> new ArmorStandListener(ctx));
        ListenerGate.register(scope, caps.mountSpigot(), "MountListenerSpigot",
                () -> new MountListenerSpigot(ctx));
        ListenerGate.register(scope, caps.toggleGlide(), "GlideListener",
                () -> new GlideListener(ctx));
        ListenerGate.register(scope, caps.lingering(), "LingeringListener",
                () -> new LingeringListener(ctx));

        // ── probe-gated but ABSENT from the 1.13 API → reflective registration ───────────────────
        if (caps.raids()) {
            new RaidListener(ctx).register(plugin, scope);
        }
        if (caps.mountBukkit()) {
            new MountListenerBukkit(ctx).register(plugin, scope);
        }
    }
}
