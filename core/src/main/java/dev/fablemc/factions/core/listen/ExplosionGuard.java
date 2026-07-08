package dev.fablemc.factions.core.listen;

import java.util.List;

import org.bukkit.World;
import org.bukkit.block.Block;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.rules.Action;
import dev.fablemc.factions.kernel.rules.Verdict;
import dev.fablemc.factions.kernel.rules.Verdicts;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * Shared explosion block-list filtering for {@link ExplosionListener} (entity) and
 * {@link BlockExplodeListener} (block) — removes protected blocks from the blast rather than
 * cancelling the whole event (ref-engines.md §3.1.4). Each block's chunk verdict flows through the
 * {@link ChunkVerdictMemo} so a blast spanning a few chunks decides each chunk once, then reuses it
 * for every block in that chunk.
 *
 * <p><b>Owning thread(s):</b> the explosion's region/main thread. <b>Mutability:</b> static-only;
 * one memo is allocated per explosion (justified region-rate allocation — see {@link ChunkVerdictMemo}).
 */
final class ExplosionGuard {

    private ExplosionGuard() {
    }

    /**
     * Drops every protected block from {@code blocks} (the event's mutable block-list). Uses
     * {@link List#removeIf} — the reference filter shape — over an explicit index loop because an
     * index-based removal from a list would be O(n²); the single captured predicate is a justified
     * region-rate allocation.
     */
    static void protect(ListenerContext ctx, World world, List<Block> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        KernelSnapshot snap = ctx.snapshots().current();
        int worldIdx = ctx.worlds().indexOf(world);
        long actor = ProtectionSupport.environmentActorBits(FactionHandle.WILDERNESS);
        ChunkVerdictMemo memo = new ChunkVerdictMemo();
        blocks.removeIf(block -> isProtected(snap, actor, worldIdx, block, memo));
    }

    private static boolean isProtected(KernelSnapshot snap, long actor, int worldIdx, Block block,
                                       ChunkVerdictMemo memo) {
        long chunkKey = ChunkKeys.fromBlock(block.getX(), block.getZ());
        int verdict = memo.lookup(chunkKey);
        if (verdict == Verdict.DENY_INTERNAL) {
            verdict = Verdicts.decide(snap, actor, worldIdx, chunkKey, Action.EXPLOSION);
            memo.store(chunkKey, verdict);
        }
        return !Verdict.allowed(verdict);
    }
}
