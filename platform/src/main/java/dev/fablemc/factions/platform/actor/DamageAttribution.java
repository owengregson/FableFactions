package dev.fablemc.factions.platform.actor;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import dev.fablemc.factions.platform.probe.ProbeTarget;

/**
 * Resolves the TRUE attacker behind an indirect damage source (CONTRACTS §3, AM-16) — the
 * player who fired the arrow, primed the TNT, threw the lingering potion, or owns the pet —
 * so claim PvP verdicts attribute damage to a person, not a projectile.
 *
 * <p>Every post-1.7.10 entity type is kept body-only behind a probe: {@code AreaEffectCloud}
 * (1.9+) lives in the nested {@link Clouds} helper, invoked only when its class probes
 * present, so this always-loaded class links no sub-floor type. {@code Projectile},
 * {@code TNTPrimed} and {@code Tameable} are floor-present and handled directly. This is a
 * plain (non-{@code Listener}) helper — no descriptor here mentions a post-floor type.
 *
 * <p>Owning thread(s): the region/main thread of the combat event. Mutability class:
 * static-only, stateless.
 */
public final class DamageAttribution {

    /** {@code AreaEffectCloud} is 1.9+ — probe its presence once, gate the {@link Clouds} helper. */
    private static final boolean AREA_EFFECT_CLOUD = ProbeTarget.AREA_EFFECT_CLOUD.present();

    /** Bounds the shooter→source→owner chain so a pathological cycle can never spin. */
    private static final int MAX_DEPTH = 4;

    private DamageAttribution() {}

    /**
     * The player-or-entity ultimately responsible for {@code damager}'s damage, or
     * {@code null} when no living attacker can be attributed (e.g. environmental TNT).
     */
    public static @Nullable Entity resolveAttacker(@NotNull Entity damager) {
        return resolve(damager, 0);
    }

    private static @Nullable Entity resolve(@Nullable Entity damager, int depth) {
        if (damager == null || depth >= MAX_DEPTH) {
            return null;
        }
        if (damager instanceof Player) {
            return damager;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Entity firer ? resolve(firer, depth + 1) : null;
        }
        if (damager instanceof TNTPrimed tnt) {
            // TNTPrimed is floor-present; getSource() semantics vary pre-1.8 but never throws here.
            Entity source = tnt.getSource();
            return source != null ? resolve(source, depth + 1) : null;
        }
        if (AREA_EFFECT_CLOUD) {
            Entity cloudSource = Clouds.source(damager);
            if (cloudSource != null) {
                return resolve(cloudSource, depth + 1);
            }
        }
        if (damager instanceof Tameable pet && pet.getOwner() instanceof Player owner) {
            return owner;
        }
        return null;
    }

    /**
     * The 1.9+ {@code AreaEffectCloud} source reader, isolated in its own class so the
     * {@code AreaEffectCloud} type is referenced (and linked) ONLY when the boot probe found
     * it present. Loading this class is triggered lazily by the guarded call site in
     * {@link #resolve}; on a pre-1.9 server it is never touched.
     */
    private static final class Clouds {

        private Clouds() {}

        static @Nullable Entity source(@NotNull Entity entity) {
            if (!(entity instanceof org.bukkit.entity.AreaEffectCloud cloud)) {
                return null;
            }
            return cloud.getSource() instanceof Entity thrower ? thrower : null;
        }
    }
}
