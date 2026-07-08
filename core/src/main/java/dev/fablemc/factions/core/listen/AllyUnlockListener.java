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
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.rules.ActorBits;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.RelationKind;

/**
 * Ally-unlock for WorldGuard-sync mode: un-cancels a WorldGuard build denial for allies and
 * admins overriding, at {@code HIGHEST}/{@code ignoreCancelled=false} — after both WorldGuard's
 * {@code NORMAL} pass and our own {@code HIGH} handler (ref-engines.md §3.1.2, proposal-C §8.1).
 * Registered ONLY when {@link TerritorySync#syncsBuildProtection()} — pure-DB servers never see it.
 *
 * <p><b>Owning thread(s):</b> the event's region/main thread — snapshot read only (CONTRACTS §4).
 * <b>Mutability:</b> immutable. This is a floor-safe listener (block events + {@code Location}
 * only); it is gated by the WG-sync <em>capability</em>, not a version probe, so it carries no
 * {@code @ProbeGated} descriptor hazard.
 */
public final class AllyUnlockListener implements Listener {

    private final ListenerContext ctx;
    private final TerritorySync territorySync;

    public AllyUnlockListener(ListenerContext ctx, TerritorySync territorySync) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.territorySync = Objects.requireNonNull(territorySync, "territorySync");
    }

    /** Un-cancels an allied/override break WorldGuard denied (ref-engines.md §3.1.2). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreakAllyUnlock(BlockBreakEvent event) {
        tryUnlock(event.getPlayer(), event.getBlock(), event);
    }

    /** Un-cancels an allied/override placement WorldGuard denied (ref-engines.md §3.1.2). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockPlaceAllyUnlock(BlockPlaceEvent event) {
        tryUnlock(event.getPlayer(), event.getBlockPlaced(), event);
    }

    private void tryUnlock(Player player, Block block, Cancellable event) {
        if (!territorySync.syncsBuildProtection() || !event.isCancelled()) {
            return;
        }
        if (allowedForAlly(player, block)) {
            event.setCancelled(false);
        }
    }

    private boolean allowedForAlly(Player player, Block block) {
        if (!territorySync.isFactionRegion(block.getLocation())) {
            return false; // not a mirrored faction region — let WorldGuard's decision stand
        }
        KernelSnapshot snap = ctx.snapshots().current();
        long actor = ProtectionSupport.playerActorBits(snap, player);
        if (ActorBits.bypass(actor) || ActorBits.overriding(actor)) {
            return true;
        }
        int actorHandle = ActorBits.factionHandle(actor);
        if (actorHandle == FactionHandle.WILDERNESS) {
            return false; // not in a faction
        }
        int owner = snap.claimOwnerAt(ctx.worlds().indexOf(block.getWorld()),
                ChunkKeys.fromBlock(block.getX(), block.getZ()));
        if (owner == FactionHandle.WILDERNESS) {
            return false;
        }
        int ownerOrd = FactionHandle.ordinal(owner);
        if (ownerOrd == FactionHandle.SAFEZONE_ORDINAL || ownerOrd == FactionHandle.WARZONE_ORDINAL) {
            return false;
        }
        if (FactionHandle.ordinal(actorHandle) == ownerOrd) {
            return true; // own territory (normally unreachable at this priority)
        }
        return snap.relationBetween(actorHandle, owner) == RelationKind.ALLY;
    }
}
