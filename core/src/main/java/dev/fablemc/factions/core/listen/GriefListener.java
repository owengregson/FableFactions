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
 * The event-complete grief matrix (D-4, proposal-C §8.1): fire spread / ignite, liquid & dragon-egg
 * flow, piston push/pull (both-ends), non-player block changes, hanging break, structure growth and
 * farmland trample — all at {@code HIGH}/{@code ignoreCancelled=true}, all through
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

    // ── liquids ──────────────────────────────────────────────────────────────────────────────

    /** Blocks liquid / dragon-egg flow across a claim border (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block from = event.getBlock();
        Block to = event.getToBlock();
        flowGuard(from.getWorld(), chunkOf(from), chunkOf(to), Action.LIQUID, event);
    }

    /** A player emptying a bucket is build-like at the clicked block (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block block = event.getBlockClicked();
        playerGuard(event.getPlayer(), block.getWorld(), chunkOf(block), Action.LIQUID, event,
                ProtectionText.NO_BUILD);
    }

    /** A player filling a bucket is build-like at the clicked block (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlockClicked();
        playerGuard(event.getPlayer(), block.getWorld(), chunkOf(block), Action.LIQUID, event,
                ProtectionText.NO_BUILD);
    }

    // ── pistons (both-ends, floor-safe: no getBlocks()) ───────────────────────────────────────

    /** Blocks a piston in one claim pushing blocks into a foreign claim (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        pistonGuard(event.getBlock(), event.getDirection(), event);
    }

    /** Blocks a piston in one claim pulling blocks out of a foreign claim (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        pistonGuard(event.getBlock(), event.getDirection(), event);
    }

    private void pistonGuard(Block piston, BlockFace direction, Cancellable event) {
        // Floor-safe both-ends check: compare the piston's chunk to the frontier chunk one block
        // along the movement axis (getBlocks() is 1.8+ and NoSuchMethodErrors on 1.7.10).
        Block frontier = piston.getRelative(direction);
        flowGuard(piston.getWorld(), chunkOf(piston), chunkOf(frontier), Action.PISTON, event);
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

    /** Physics/explosion hanging break in a claim is entity grief (D-4). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
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
