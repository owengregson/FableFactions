package dev.fablemc.factions.core.listen;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.rules.Action;
import dev.fablemc.factions.kernel.rules.Verdict;
import dev.fablemc.factions.kernel.rules.Verdicts;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * Shared vehicle-mount protection for {@link MountListenerSpigot} (the {@code org.spigotmc} event)
 * and {@link MountListenerBukkit} (the {@code org.bukkit} event, 1.20.3+) — a player mounting a
 * horse/boat/minecart in a claim they may not interact with is cancelled, gated as
 * {@link Action#INTERACT} through {@code Verdicts.decide} (D-4). Only a {@code Player} rider is
 * gated; mob-on-mount is left to vanilla.
 *
 * <p><b>Owning thread(s):</b> the mount event's region/main thread — snapshot read + feedback only
 * (CONTRACTS §4). <b>Mutability:</b> static-only, stateless. This is a plain helper (no
 * post-floor type in any descriptor), so both mount variants reuse it without a descriptor hazard.
 */
final class MountGuard {

    private MountGuard() {
    }

    /** Cancels {@code event} when {@code rider} is a player mounting in a claim they may not use. */
    static void check(ListenerContext ctx, Entity rider, Cancellable event) {
        if (!(rider instanceof Player player)) {
            return;
        }
        KernelSnapshot snap = ctx.snapshots().current();
        long actor = ProtectionSupport.playerActorBits(snap, player);
        Location loc = player.getLocation();
        int worldIdx = ctx.worlds().indexOf(loc.getWorld());
        long chunkKey = ChunkKeys.fromBlock(loc.getBlockX(), loc.getBlockZ());
        if (!Verdict.allowed(Verdicts.decide(snap, actor, worldIdx, chunkKey, Action.INTERACT))) {
            event.setCancelled(true);
            ctx.messages().to(player, ProtectionText.NO_INTERACT);
        }
    }
}
