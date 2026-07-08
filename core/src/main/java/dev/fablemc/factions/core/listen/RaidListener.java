package dev.fablemc.factions.core.listen;

import java.lang.reflect.Method;
import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.life.Scope;

/**
 * Raid suppression in claimed territory ({@code RaidTriggerEvent}, ~1.14/1.15+): a pillager raid
 * triggered inside any claimed or system-zone chunk is cancelled (proposal-C §8.1, version-deltas
 * §3.8). Registered at {@code HIGH}/{@code ignoreCancelled=true} ONLY behind the {@code raids}
 * capability — but through {@link ReflectiveEvents}, because {@code RaidTriggerEvent} is absent from
 * the 1.13 compile API, so this class references it only by name and reads it via base-{@code Event}
 * reflection (no descriptor mentions the absent type).
 *
 * <p><b>Owning thread(s):</b> {@link #register} on boot; {@link #onRaidTrigger} on the raid's
 * region/main thread — snapshot read only (CONTRACTS §4). <b>Mutability:</b> immutable.
 */
public final class RaidListener implements Listener {

    private static final String RAID_TRIGGER_EVENT = "org.bukkit.event.raid.RaidTriggerEvent";

    private final ListenerContext ctx;

    public RaidListener(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Registers the reflective raid handler; returns {@code false} if the event class is absent. */
    public boolean register(Plugin plugin, Scope scope) {
        return ReflectiveEvents.register(plugin, scope, RAID_TRIGGER_EVENT, EventPriority.HIGH, true,
                this, this::onRaidTrigger);
    }

    private void onRaidTrigger(Event event) {
        if (!(event instanceof Cancellable cancellable)) {
            return;
        }
        Player player = raidPlayer(event);
        if (player == null) {
            return;
        }
        KernelSnapshot snap = ctx.snapshots().current();
        Location loc = player.getLocation();
        int worldIdx = ctx.worlds().indexOf(loc.getWorld());
        long chunkKey = ChunkKeys.fromBlock(loc.getBlockX(), loc.getBlockZ());
        if (snap.claimOwnerAt(worldIdx, chunkKey) != FactionHandle.WILDERNESS) {
            cancellable.setCancelled(true); // no raids in any claimed / system-zone land
        }
    }

    private static Player raidPlayer(Event event) {
        try {
            Method getPlayer = event.getClass().getMethod("getPlayer");
            Object player = getPlayer.invoke(event);
            return player instanceof Player p ? p : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }
}
