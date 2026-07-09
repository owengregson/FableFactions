package dev.fablemc.factions.core.listen;

import java.util.List;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.rules.Action;
import dev.fablemc.factions.kernel.rules.Verdict;
import dev.fablemc.factions.kernel.rules.Verdicts;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.actor.DamageAttribution;

/**
 * The event-complete grief matrix (D-4, proposal-C §8.1): fire spread / ignite / burn, liquid &
 * dragon-egg flow, piston push/pull (whole moved set, both ends), non-player block changes, hanging
 * break, structure growth and farmland trample — all at {@code HIGH}/{@code ignoreCancelled=true},
 * all through
 * {@code Verdicts.decide}. Every event here is universal at the 1.7.10 floor and every enum constant
 * referenced is floor-present, so this is a BASELINE listener (AM-13 {@code verifyDescriptorFloor}).
 *
 * <p>Two verdict shapes are used. <em>Player-triggered</em> grief (bucket, player-lit fire, hanging
 * break, trample) packs the player's actor and decides the target chunk. <em>Environmental flow</em>
 * (liquid, piston, structure growth) packs the SOURCE chunk's owner as the actor
 * ({@link ProtectionSupport#environmentActorBits}) and decides the DESTINATION chunk, so flow within
 * one owner or between allies is allowed and flow into a foreign claim is denied — border grief
 * expressed through the single verdict engine.
 *
 * <p><b>Owning thread(s):</b> the event's region/main thread — snapshot read + feedback only
 * (CONTRACTS §4). <b>Mutability:</b> immutable.
 */
public final class GriefListener implements Listener {

    private final ListenerContext ctx;

    public GriefListener(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    // ── fire ─────────────────────────────────────────────────────────────────────────────────

    /** Cancels fire spreading into a claim with the fire-spread flag off (ref-engines.md §3.1.5). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (event.getSource().getType() != Material.FIRE) {
            return; // only fire spread
        }
        Block target = event.getBlock();
        wildernessGuard(target.getWorld(), chunkOf(target), Action.FIRE_SPREAD, event);
    }

    /** Player-lit fire is build-like; environmental ignition is fire-spread-gated (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        if (player != null) {
            playerGuard(player, block.getWorld(), chunkOf(block), Action.BUILD, event,
                    ProtectionText.NO_BUILD);
            return;
        }
        wildernessGuard(block.getWorld(), chunkOf(block), Action.FIRE_SPREAD, event);
    }

    /**
     * Stops fire from burning a claim's blocks away when its fire-spread flag is off — the missing
     * counterpart to {@link #onBlockSpread} / {@link #onBlockIgnite}: the plugin already denies fire
     * spreading and igniting INTO a claim, but without this a fire lit at the border still consumed
     * the claimed blocks it touched (D-4). {@code BlockBurnEvent} is floor-present (1.7.10).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        Block block = event.getBlock();
        wildernessGuard(block.getWorld(), chunkOf(block), Action.FIRE_SPREAD, event);
    }

    // ── liquids ──────────────────────────────────────────────────────────────────────────────

    /** Blocks liquid / dragon-egg flow across a claim border (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block from = event.getBlock();
        Block to = event.getToBlock();
        flowGuard(from.getWorld(), chunkOf(from), chunkOf(to), Action.LIQUID, event);
    }

    /**
     * A player emptying a bucket is build-like at the block the liquid actually LANDS in — the face
     * pointed at ({@code getBlockClicked().getRelative(getBlockFace())}), not the clicked block
     * itself. Deciding on the clicked block let a player in their own/wilderness chunk pour lava or
     * water across a border into a claim (D-4).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block placed = event.getBlockClicked().getRelative(event.getBlockFace());
        playerGuard(event.getPlayer(), placed.getWorld(), chunkOf(placed), Action.LIQUID, event,
                ProtectionText.NO_BUILD);
    }

    /** A player filling a bucket is build-like at the clicked block (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlockClicked();
        playerGuard(event.getPlayer(), block.getWorld(), chunkOf(block), Action.LIQUID, event,
                ProtectionText.NO_BUILD);
    }

    // ── pistons (whole moved set, both ends) ──────────────────────────────────────────────────

    /**
     * Blocks a piston pushing blocks across a claim border. Every block in the moved set is checked
     * at BOTH its source chunk and the chunk it slides INTO — not just the single frontier block, so
     * a piston sitting 2+ blocks from the border can no longer shove blocks across it (D-4).
     * {@code BlockPistonExtendEvent#getBlocks()} is floor-present (version-deltas §3.8 — only the
     * RETRACT event's {@code getBlocks()} is 1.8+), so this stays a baseline handler.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Block piston = event.getBlock();
        BlockFace direction = event.getDirection();
        KernelSnapshot snap = ctx.snapshots().current();
        int worldIdx = ctx.worlds().indexOf(piston.getWorld());
        long pistonChunk = chunkOf(piston);
        long actor = ProtectionSupport.environmentActorBits(snap.claimOwnerAt(worldIdx, pistonChunk));
        // The head itself advances into the frontier even when nothing is pushed.
        if (pistonCrosses(snap, actor, worldIdx, pistonChunk, chunkOf(piston.getRelative(direction)))) {
            event.setCancelled(true);
            return;
        }
        List<Block> moved = event.getBlocks();
        for (int i = 0, n = moved.size(); i < n; i++) {
            Block block = moved.get(i);
            if (pistonCrosses(snap, actor, worldIdx, pistonChunk, chunkOf(block))
                    || pistonCrosses(snap, actor, worldIdx, pistonChunk,
                            chunkOf(block.getRelative(direction)))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Blocks a sticky piston pulling a block across a claim border. The RETRACT event's
     * {@code getBlocks()} is 1.8+ and {@code NoSuchMethodError}s on the 1.7.10 floor
     * (version-deltas §3.8), so the single sticky-pulled block is derived geometrically instead —
     * the block two ahead of the piston returns to one ahead — and both its source and destination
     * chunk are checked. A non-sticky retract pulls nothing, so it is left alone (D-4).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!event.isSticky()) {
            return; // a non-sticky retract moves no block — nothing to grief
        }
        Block piston = event.getBlock();
        BlockFace direction = event.getDirection();
        KernelSnapshot snap = ctx.snapshots().current();
        int worldIdx = ctx.worlds().indexOf(piston.getWorld());
        long pistonChunk = chunkOf(piston);
        long actor = ProtectionSupport.environmentActorBits(snap.claimOwnerAt(worldIdx, pistonChunk));
        long pulledFrom = chunkOf(piston.getRelative(direction, 2));
        long pulledTo = chunkOf(piston.getRelative(direction, 1));
        if (pistonCrosses(snap, actor, worldIdx, pistonChunk, pulledFrom)
                || pistonCrosses(snap, actor, worldIdx, pistonChunk, pulledTo)) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether the piston (whose own claim is packed into {@code actor}) may not act at
     * {@code targetChunk}. A block staying in the piston's own chunk is always fine; otherwise the
     * decision routes through {@link Verdicts#decide} so within-claim and ally movement is allowed
     * and movement into a foreign claim is denied — the same border rule liquid flow uses.
     */
    private static boolean pistonCrosses(KernelSnapshot snap, long actor, int worldIdx,
                                         long pistonChunk, long targetChunk) {
        if (targetChunk == pistonChunk) {
            return false; // same chunk as the piston body — same owner, always allowed
        }
        return !Verdict.allowed(Verdicts.decide(snap, actor, worldIdx, targetChunk, Action.PISTON));
    }

    // ── entities / hangings / growth ──────────────────────────────────────────────────────────

    /** Protects claimed blocks from non-player entity changes (endermen, sheep, withers) (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Player) {
            return; // player changes are covered by the build/interact listeners
        }
        Block block = event.getBlock();
        wildernessGuard(block.getWorld(), chunkOf(block), Action.ENTITY_GRIEF, event);
    }

    /**
     * Physics/explosion (non-entity) hanging break in a claim is entity grief (D-4).
     *
     * <p>{@code HangingBreakByEntityEvent} is a subclass that inherits this event's {@link
     * org.bukkit.event.HandlerList}, so an entity-caused break ALSO dispatches to this generic
     * handler. Its wilderness-actor rule would then wrongly cancel every break inside a claim —
     * including the owning faction's own members and bypass admins. Those entity-caused breaks are
     * attributed correctly by {@link #onHangingBreakByEntity}, so bail out here and let that handler
     * decide them.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (event instanceof HangingBreakByEntityEvent) {
            return; // decided by onHangingBreakByEntity with proper actor attribution
        }
        Entity hanging = event.getEntity();
        wildernessGuard(hanging.getWorld(), chunkOf(hanging), Action.ENTITY_GRIEF, event);
    }

    /** A player breaking a hanging is build-like; a mob/arrow is entity grief (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        Entity hanging = event.getEntity();
        Entity remover = DamageAttribution.resolveAttacker(event.getRemover());
        if (remover instanceof Player player) {
            playerGuard(player, hanging.getWorld(), chunkOf(hanging), Action.ENTITY_GRIEF, event,
                    ProtectionText.NO_BUILD);
            return;
        }
        wildernessGuard(hanging.getWorld(), chunkOf(hanging), Action.ENTITY_GRIEF, event);
    }

    /** Trims a growing tree/mushroom's blocks that would cross into a foreign claim (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        List<BlockState> blocks = event.getBlocks();
        if (blocks.isEmpty()) {
            return;
        }
        World world = event.getLocation().getWorld();
        int worldIdx = ctx.worlds().indexOf(world);
        KernelSnapshot snap = ctx.snapshots().current();
        long originChunk = ChunkKeys.fromBlock(event.getLocation().getBlockX(),
                event.getLocation().getBlockZ());
        long actor = ProtectionSupport.environmentActorBits(snap.claimOwnerAt(worldIdx, originChunk));
        ChunkVerdictMemo memo = new ChunkVerdictMemo();
        blocks.removeIf(state -> growthBlocked(snap, actor, worldIdx, state, memo));
    }

    private static boolean growthBlocked(KernelSnapshot snap, long actor, int worldIdx,
                                         BlockState state, ChunkVerdictMemo memo) {
        long chunkKey = ChunkKeys.fromBlock(state.getX(), state.getZ());
        int verdict = memo.lookup(chunkKey);
        if (verdict == Verdict.DENY_INTERNAL) {
            verdict = Verdicts.decide(snap, actor, worldIdx, chunkKey, Action.BUILD);
            memo.store(chunkKey, verdict);
        }
        return !Verdict.allowed(verdict);
    }

    // ── trample ──────────────────────────────────────────────────────────────────────────────

    /** Cancels farmland/turtle-egg trample by a non-member (PHYSICAL interaction) (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.PHYSICAL) {
            return; // only stepping-on interactions
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        // Silent (no message) — trample fires continuously as the player walks.
        playerGuard(event.getPlayer(), block.getWorld(), chunkOf(block), Action.TRAMPLE, event, null);
    }

    // ── verdict helpers ───────────────────────────────────────────────────────────────────────

    private void playerGuard(Player player, World world, long chunkKey, int action, Cancellable event,
                             MessageKey denyKey) {
        KernelSnapshot snap = ctx.snapshots().current();
        long actor = ProtectionSupport.playerActorBits(snap, player);
        int worldIdx = ctx.worlds().indexOf(world);
        if (!Verdict.allowed(Verdicts.decide(snap, actor, worldIdx, chunkKey, action))) {
            event.setCancelled(true);
            if (denyKey != null) {
                ctx.messages().to(player, denyKey);
            }
        }
    }

    private void flowGuard(World world, long srcChunk, long dstChunk, int action, Cancellable event) {
        if (srcChunk == dstChunk) {
            return; // same chunk: same owner, always allowed
        }
        KernelSnapshot snap = ctx.snapshots().current();
        int worldIdx = ctx.worlds().indexOf(world);
        long actor = ProtectionSupport.environmentActorBits(snap.claimOwnerAt(worldIdx, srcChunk));
        if (!Verdict.allowed(Verdicts.decide(snap, actor, worldIdx, dstChunk, action))) {
            event.setCancelled(true);
        }
    }

    private void wildernessGuard(World world, long chunkKey, int action, Cancellable event) {
        KernelSnapshot snap = ctx.snapshots().current();
        int worldIdx = ctx.worlds().indexOf(world);
        long actor = ProtectionSupport.environmentActorBits(FactionHandle.WILDERNESS);
        if (!Verdict.allowed(Verdicts.decide(snap, actor, worldIdx, chunkKey, action))) {
            event.setCancelled(true);
        }
    }

    private static long chunkOf(Block block) {
        return ChunkKeys.fromBlock(block.getX(), block.getZ());
    }

    private static long chunkOf(Entity entity) {
        return ChunkKeys.fromBlock(entity.getLocation().getBlockX(), entity.getLocation().getBlockZ());
    }
}
