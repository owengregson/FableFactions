package dev.fablemc.factions.core.listen;

import java.util.Objects;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.rules.Action;
import dev.fablemc.factions.kernel.rules.Verdict;
import dev.fablemc.factions.kernel.rules.Verdicts;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * Territory build protection: block break / place gated through {@link Verdicts#decide}
 * (ref-engines.md §3.1.1, proposal-C §8.1). Registered at {@code HIGH}/{@code ignoreCancelled=true}
 * — after WorldGuard's {@code NORMAL} pass when a bridge is synced. In pure-DB mode the verdict
 * engine is authoritative; in WG-sync mode the fast path allows inside a mirrored region and only
 * falls back to the verdict for wilderness / unsynced blocks (ref-engines.md §3.1.1
 * {@code canModifyWgSync}).
 *
 * <p><b>Owning thread(s):</b> the event's region/main thread — snapshot read + {@code Verdicts} +
 * {@link dev.fablemc.factions.core.text.Messages}, never JDBC or kernel-state construction
 * (CONTRACTS §4). The actor is packed exactly once per event via {@link ProtectionSupport}.
 * <b>Mutability:</b> immutable (injected collaborators only).
 *
 * <p>This is a BASELINE listener: every event type and enum constant in its descriptors is
 * floor-present (1.7.10), so it links on every supported server (AM-13 {@code verifyDescriptorFloor}).
 */
public final class BuildProtectionListener implements Listener {

    private final ListenerContext ctx;
    private final TerritorySync territorySync;

    public BuildProtectionListener(ListenerContext ctx, TerritorySync territorySync) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.territorySync = Objects.requireNonNull(territorySync, "territorySync");
    }

    /** Cancels a break the actor may not perform in claimed land (ref-engines.md §3.1.1). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        guardBuild(event.getPlayer(), event.getBlock(), event, ProtectionText.NO_BREAK);
    }

    /** Cancels a placement the actor may not perform in claimed land (ref-engines.md §3.1.1). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        guardBuild(event.getPlayer(), event.getBlockPlaced(), event, ProtectionText.NO_PLACE);
    }

    private void guardBuild(Player player, Block block, Cancellable event, MessageKey denyKey) {
        // WG-sync fast path: WG already enforced membership at NORMAL for a mirrored region.
        if (territorySync.syncsBuildProtection() && territorySync.isFactionRegion(block.getLocation())) {
            return;
        }
        KernelSnapshot snap = ctx.snapshots().current();
        long actor = ProtectionSupport.playerActorBits(snap, player);
        int worldIdx = ctx.worlds().indexOf(block.getWorld());
        long chunkKey = ChunkKeys.fromBlock(block.getX(), block.getZ());
        int verdict = Verdicts.decide(snap, actor, worldIdx, chunkKey, Action.BUILD);
        if (!Verdict.allowed(verdict)) {
            event.setCancelled(true);
            ctx.messages().to(player, denyKey);
        }
    }
}
