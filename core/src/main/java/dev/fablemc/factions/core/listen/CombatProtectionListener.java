package dev.fablemc.factions.core.listen;

import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PotionSplashEvent;

import dev.fablemc.factions.core.session.PlayerSession;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.rules.Verdict;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.actor.DamageAttribution;

/**
 * Player-vs-player combat protection: direct melee/arrow/TNT/pet damage
 * ({@code EntityDamageByEntityEvent}), splash-potion harm ({@code PotionSplashEvent}) and
 * unattributable explosion damage in no-PvP land ({@code EntityDamageEvent}, see
 * {@link #onExplosionDamage}), all at {@code HIGH}/{@code ignoreCancelled=true} (ref-engines.md
 * §3.1.3, proposal-C §8.1). The true
 * attacker behind an indirect source is resolved ONCE through the platform's
 * {@link DamageAttribution} (AM-16) — before every combat verdict — so a proxied hit still attributes
 * to a person, then the decision routes through {@code Verdicts.decide} via {@link ProtectionSupport}.
 *
 * <p>On an allowed hit both parties are combat-tagged in their {@link PlayerSession} (the fly-threat
 * window {@link MoveListener} re-checks). {@code PotionSplashEvent} nullifies the potion's intensity
 * on each protected victim rather than cancelling the whole throw, so a splash across a border still
 * affects the thrower's own allies.
 *
 * <p><b>Owning thread(s):</b> the event's region/main thread — snapshot read + feedback only; the
 * combat-tag write hops to each player's region via {@code Scheduling.ensureOn} (a session is
 * confined to its owner's region thread, AM-14). <b>Mutability:</b> immutable. Floor-safe baseline
 * listener ({@code PotionSplashEvent} is universal at the floor; lingering potions ride the
 * probe-gated {@link LingeringListener}).
 */
public final class CombatProtectionListener implements Listener {

    /** Combat-tag lifetime after a hit (drives the fly-on-threat re-check). */
    private static final long COMBAT_TAG_MILLIS = 15_000L;

    private static final Runnable NO_RETIRED = () -> { };

    private final ListenerContext ctx;

    public CombatProtectionListener(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Gates direct player-vs-player damage; tags both on an allowed hit (ref-engines.md §3.1.3). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return; // only player victims
        }
        Entity attackerEntity = DamageAttribution.resolveAttacker(event.getDamager());
        if (!(attackerEntity instanceof Player attacker)) {
            return; // only player attackers (after shooter/TNT/pet attribution)
        }
        KernelSnapshot snap = ctx.snapshots().current();
        long actor = ProtectionSupport.playerActorBits(snap, attacker);
        int verdict = combatVerdict(snap, actor, attacker, victim);
        if (verdict == Verdict.ALLOW) {
            tagCombat(attacker);
            tagCombat(victim);
            return;
        }
        event.setCancelled(true);
        ctx.messages().to(attacker, denyKey(verdict));
    }

    /**
     * Protects a player from UNATTRIBUTABLE explosion damage in no-PvP land. An end crystal, a
     * bed / respawn-anchor blast in the Nether or End, or dispenser / redstone-ignited TNT resolves
     * to no player attacker, so {@link #onPvp} (which needs an attacker) skips it and the victim
     * would take the blast inside a safezone or a pvp-disabled claim. This handler is on the base
     * {@code EntityDamageEvent} — whose handler list is shared by both the entity-explosion
     * (by-entity) and block-explosion (by-block) shapes — filters to the two explosion causes, and
     * when a player attacker IS resolvable defers to {@link #onPvp}. Otherwise it cancels the damage
     * purely on the victim's location (finding: environmental explosions gate on where the victim
     * stands). {@code EntityDamageEvent} and the {@code BLOCK_EXPLOSION} / {@code ENTITY_EXPLOSION}
     * causes are floor-present, so this stays a baseline handler.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        DamageCause cause = event.getCause();
        if (cause != DamageCause.BLOCK_EXPLOSION && cause != DamageCause.ENTITY_EXPLOSION) {
            return; // only explosion damage
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return; // only player victims
        }
        // A player-attributable blast is decided by onPvp's attacker-based verdict — skip it here.
        if (event instanceof EntityDamageByEntityEvent byEntity
                && DamageAttribution.resolveAttacker(byEntity.getDamager()) instanceof Player) {
            return;
        }
        KernelSnapshot snap = ctx.snapshots().current();
        int worldIdx = ctx.worlds().indexOf(victim.getWorld());
        Location loc = victim.getLocation();
        long chunkKey = ChunkKeys.fromBlock(loc.getBlockX(), loc.getBlockZ());
        if (!ProtectionSupport.environmentalPvpAllowed(snap, worldIdx, chunkKey)) {
            event.setCancelled(true);
        }
    }

    /** Nullifies a splash potion's effect on each protected victim (ref-engines.md §3.1.3, D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();
        if (!(DamageAttribution.resolveAttacker(potion) instanceof Player attacker)) {
            return;
        }
        KernelSnapshot snap = ctx.snapshots().current();
        long actor = ProtectionSupport.playerActorBits(snap, attacker);
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (!(affected instanceof Player victim)) {
                continue;
            }
            if (combatVerdict(snap, actor, attacker, victim) != Verdict.ALLOW) {
                event.setIntensity(affected, 0.0);
            }
        }
    }

    private int combatVerdict(KernelSnapshot snap, long actor, Player attacker, Player victim) {
        Location loc = victim.getLocation();
        int worldIdx = ctx.worlds().indexOf(victim.getWorld());
        long chunkKey = ChunkKeys.fromBlock(loc.getBlockX(), loc.getBlockZ());
        return ProtectionSupport.combatVerdict(snap, actor, attacker, victim, worldIdx, chunkKey);
    }

    private static MessageKey denyKey(int verdict) {
        switch (verdict) {
            case Verdict.DENY_FRIENDLY_FIRE:
                return ProtectionText.FRIENDLY_FIRE;
            case Verdict.DENY_SAFEZONE:
                return ProtectionText.PVP_SAFEZONE;
            default:
                return ProtectionText.PVP_TERRITORY;
        }
    }

    private void tagCombat(Player player) {
        long until = System.currentTimeMillis() + COMBAT_TAG_MILLIS;
        ctx.scheduling().ensureOn(player, () -> {
            PlayerSession session = ctx.sessions().get(player.getUniqueId());
            if (session != null) {
                session.tagCombat(until);
            }
        }, NO_RETIRED);
    }
}
