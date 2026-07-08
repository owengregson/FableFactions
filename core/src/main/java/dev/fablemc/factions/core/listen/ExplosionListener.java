package dev.fablemc.factions.core.listen;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Entity explosion protection ({@code EntityExplodeEvent}) at {@code HIGH}/{@code ignoreCancelled=true}
 * — TNT, creepers, ghasts, ender-crystals (ref-engines.md §3.1.4, proposal-C §8.1). Claimed blocks
 * are removed from the blast per {@link ExplosionGuard}; wilderness is left untouched.
 *
 * <p><b>Owning thread(s):</b> the explosion's region/main thread — snapshot read only (CONTRACTS §4).
 * <b>Mutability:</b> immutable. {@code EntityExplodeEvent} is universal at the floor, so this is a
 * BASELINE listener; the 1.8.3+ {@code BlockExplodeEvent} rides the probe-gated
 * {@link BlockExplodeListener} (version-deltas §3.8).
 */
public final class ExplosionListener implements Listener {

    private final ListenerContext ctx;

    public ExplosionListener(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Removes protected blocks from an entity blast (ref-engines.md §3.1.4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        ExplosionGuard.protect(ctx, event.getLocation().getWorld(), event.blockList());
    }
}
