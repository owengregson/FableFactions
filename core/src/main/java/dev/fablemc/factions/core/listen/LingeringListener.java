package dev.fablemc.factions.core.listen;

import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.LingeringPotionSplashEvent;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.rules.Action;
import dev.fablemc.factions.kernel.rules.Verdict;
import dev.fablemc.factions.kernel.rules.Verdicts;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.actor.DamageAttribution;
import dev.fablemc.factions.platform.life.ProbeGated;

/**
 * Lingering-potion combat protection ({@code LingeringPotionSplashEvent}, 1.9+): a player-thrown
 * lingering cloud landing where the thrower may not fight (a PvP-off claim) is cancelled, at
 * {@code HIGH}/{@code ignoreCancelled=true} (proposal-C §8.1, version-deltas §3.8 — lingering rides a
 * probe while the instant {@code PotionSplashEvent} stays on the baseline
 * {@link CombatProtectionListener}). The true thrower is resolved through {@link DamageAttribution}.
 *
 * <p><b>Owning thread(s):</b> the event's region/main thread — snapshot read only. <b>Mutability:</b>
 * immutable. {@code @ProbeGated}: the lingering event is absent on 1.7.10, so this class links and
 * registers ONLY behind the {@code lingering} capability via
 * {@link dev.fablemc.factions.platform.life.ListenerGate} (AM-13).
 */
@ProbeGated(capability = "lingering")
public final class LingeringListener implements Listener {

    private final ListenerContext ctx;

    public LingeringListener(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Cancels a player's lingering cloud where PvP is disabled (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLingering(LingeringPotionSplashEvent event) {
        if (!(DamageAttribution.resolveAttacker(event.getEntity()) instanceof Player thrower)) {
            return;
        }
        KernelSnapshot snap = ctx.snapshots().current();
        long actor = ProtectionSupport.playerActorBits(snap, thrower);
        Location loc = event.getEntity().getLocation();
        int worldIdx = ctx.worlds().indexOf(loc.getWorld());
        long chunkKey = ChunkKeys.fromBlock(loc.getBlockX(), loc.getBlockZ());
        if (!Verdict.allowed(Verdicts.decide(snap, actor, worldIdx, chunkKey, Action.PVP))) {
            event.setCancelled(true);
        }
    }
}
