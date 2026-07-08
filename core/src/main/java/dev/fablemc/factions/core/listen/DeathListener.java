package dev.fablemc.factions.core.listen;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.core.session.PlayerSession;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.intent.PowerIntent;

/**
 * The death hook ({@code PlayerDeathEvent} at {@code MONITOR}/{@code ignoreCancelled=true}): captures
 * the death location and killer on the region thread and emits one {@code RecordDeath} intent
 * (ref-engines.md §3.7.3, proposal-C §8.1). The zone context (safezone skip, own/enemy-claimed power
 * multiplier) is resolved by the reducer from the atlas at the carried {@code (worldIdx, chunkKey)} —
 * the listener stays a thin snapshot-free capture, keeping every death-streak / kill-scaling decision
 * in the single writer.
 *
 * <p><b>Owning thread(s):</b> the dead player's region/main thread — captures Bukkit state, submits
 * one intent on the unbounded system lane (a death's power change must never be dropped), and clears
 * the confined combat tag (AM-14). <b>Mutability:</b> immutable. Floor-safe baseline listener.
 */
public final class DeathListener implements Listener {

    private final ListenerContext ctx;

    public DeathListener(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Emits {@code RecordDeath} and clears the dead player's combat tag (ref-engines.md §3.7.3). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        UUID deadId = dead.getUniqueId();
        @Nullable Player killer = dead.getKiller();
        UUID killerId = killer != null ? killer.getUniqueId() : null;
        Location loc = dead.getLocation();
        int worldIdx = ctx.worlds().indexOf(loc.getWorld());
        long chunkKey = ChunkKeys.fromBlock(loc.getBlockX(), loc.getBlockZ());
        ctx.bus().submitSystem(new PowerIntent.RecordDeath(deadId, killerId, worldIdx, chunkKey));

        PlayerSession session = ctx.sessions().get(deadId);
        if (session != null) {
            session.clearCombat();
        }
    }
}
