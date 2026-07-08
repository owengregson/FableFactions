package dev.fablemc.factions.core.listen;

import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.rules.Action;
import dev.fablemc.factions.kernel.rules.Verdict;
import dev.fablemc.factions.kernel.rules.Verdicts;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.actor.DamageAttribution;
import dev.fablemc.factions.platform.life.ProbeGated;

/**
 * Armor-stand protection (1.8+): manipulating or breaking an {@code ArmorStand} in a claim the actor
 * may not use is cancelled — right-click via {@code PlayerInteractAtEntityEvent}, break via the
 * player-attributed {@code EntityDamageByEntityEvent}, both at {@code HIGH}/{@code ignoreCancelled=true}
 * (proposal-C §8.1, D-4). Armor stands hold items, so the decision is {@link Action#CONTAINER}.
 *
 * <p><b>Owning thread(s):</b> the event's region/main thread — snapshot read + feedback only.
 * <b>Mutability:</b> immutable. {@code @ProbeGated}: {@code ArmorStand} is absent on 1.7.10, so this
 * class links and registers ONLY behind the {@code armorStands} capability via
 * {@link dev.fablemc.factions.platform.life.ListenerGate} (AM-13).
 */
@ProbeGated(capability = "armorStands")
public final class ArmorStandListener implements Listener {

    private final ListenerContext ctx;

    public ArmorStandListener(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Gates right-click manipulation of an armor stand (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof ArmorStand stand) {
            guard(event.getPlayer(), stand, event);
        }
    }

    /** Gates breaking an armor stand by a player (directly or via projectile/pet) (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ArmorStand stand)) {
            return;
        }
        Entity attacker = DamageAttribution.resolveAttacker(event.getDamager());
        if (attacker instanceof Player player) {
            guard(player, stand, event);
        }
    }

    private void guard(Player player, Entity stand, Cancellable event) {
        KernelSnapshot snap = ctx.snapshots().current();
        long actor = ProtectionSupport.playerActorBits(snap, player);
        Location loc = stand.getLocation();
        int worldIdx = ctx.worlds().indexOf(loc.getWorld());
        long chunkKey = ChunkKeys.fromBlock(loc.getBlockX(), loc.getBlockZ());
        if (!Verdict.allowed(Verdicts.decide(snap, actor, worldIdx, chunkKey, Action.CONTAINER))) {
            event.setCancelled(true);
            ctx.messages().to(player, ProtectionText.NO_CONTAINER);
        }
    }
}
