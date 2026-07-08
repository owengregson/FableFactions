package dev.fablemc.factions.core.listen;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.spigotmc.event.entity.EntityMountEvent;

import dev.fablemc.factions.platform.life.ProbeGated;

/**
 * Vehicle-mount protection on the Spigot event ({@code org.spigotmc.event.entity.EntityMountEvent} —
 * the 1.8-era package, before the 1.20.3 move to {@code org.bukkit}), at {@code HIGH}/{@code
 * ignoreCancelled=true} (proposal-C §8.1, version-deltas §3.8). Delegates to the shared
 * {@link MountGuard}.
 *
 * <p><b>Owning thread(s):</b> the mount event's region/main thread — snapshot read + feedback only.
 * <b>Mutability:</b> immutable. {@code @ProbeGated}: the {@code org.spigotmc} mount event is a
 * post-floor type, so this class links and registers ONLY behind the {@code mountSpigot} capability
 * via {@link dev.fablemc.factions.platform.life.ListenerGate} (AM-13). Its {@code org.bukkit}
 * counterpart is the reflectively-registered {@link MountListenerBukkit} (absent from the 1.13 API).
 */
@ProbeGated(capability = "mountSpigot")
public final class MountListenerSpigot implements Listener {

    private final ListenerContext ctx;

    public MountListenerSpigot(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Cancels a player mounting a vehicle in a claim they may not use (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        MountGuard.check(ctx, event.getEntity(), event);
    }
}
