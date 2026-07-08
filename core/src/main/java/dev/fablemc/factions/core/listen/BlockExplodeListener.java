package dev.fablemc.factions.core.listen;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;

import dev.fablemc.factions.platform.life.ProbeGated;

/**
 * Block explosion protection ({@code BlockExplodeEvent}, 1.8.3+ — beds, respawn anchors, cannon
 * setups) at {@code HIGH}/{@code ignoreCancelled=true} (ref-engines.md §3.1.4, proposal-C §8.1,
 * version-deltas §3.8). Claimed blocks are removed from the blast per {@link ExplosionGuard}.
 *
 * <p><b>Owning thread(s):</b> the explosion's region/main thread — snapshot read only (CONTRACTS §4).
 * <b>Mutability:</b> immutable. {@code @ProbeGated}: {@code BlockExplodeEvent} is absent on
 * 1.7.10/1.8.0–1.8.2, so this class links and registers ONLY behind the {@code blockExplode}
 * capability, through {@link dev.fablemc.factions.platform.life.ListenerGate} (AM-13).
 */
@ProbeGated(capability = "blockExplode")
public final class BlockExplodeListener implements Listener {

    private final ListenerContext ctx;

    public BlockExplodeListener(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Removes protected blocks from a block-sourced blast (ref-engines.md §3.1.4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        ExplosionGuard.protect(ctx, event.getBlock().getWorld(), event.blockList());
    }
}
