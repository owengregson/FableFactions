package dev.fablemc.factions.core.listen;

import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

import dev.fablemc.factions.kernel.config.BakedTables;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.rules.Action;
import dev.fablemc.factions.kernel.rules.Verdict;
import dev.fablemc.factions.kernel.rules.Verdicts;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * Container / interactable protection: right-clicking a chest, furnace, door, lever or button in a
 * claim the actor may not use, at {@code HIGH}/{@code ignoreCancelled=true} (proposal-C §8.1). The
 * config-declared material classes are the baked {@link BakedTables} bitsets (indexed by the interned
 * {@code Material} ordinal — no map lookup, no boxing, AM-14); containers gate {@link Action#CONTAINER}
 * and doors/levers/buttons gate {@link Action#INTERACT}.
 *
 * <p><b>Owning thread(s):</b> the event's region/main thread — snapshot read + feedback only
 * (CONTRACTS §4). <b>Mutability:</b> immutable. Floor-safe baseline listener ({@code PlayerInteractEvent}
 * and {@code Action.RIGHT_CLICK_BLOCK} are universal at the floor); hand disambiguation is
 * deliberately not attempted here to avoid the {@code EquipmentSlot.OFF_HAND} descriptor hazard
 * (AM-16) — a cancel is idempotent, so an off-hand repeat is harmless.
 */
public final class InteractProtectionListener implements Listener {

    private final ListenerContext ctx;

    public InteractProtectionListener(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Cancels use of a protected container/interactable in claimed land (proposal-C §8.1). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Material type = block.getType();
        KernelSnapshot snap = ctx.snapshots().current();
        BakedTables baked = snap.config().baked();
        int ordinal = type.ordinal();
        boolean container = baked.isContainer(ordinal);
        boolean interactable = container || baked.isInteractable(ordinal);
        if (!interactable) {
            return; // not a protected block class — leave to vanilla
        }
        Player player = event.getPlayer();
        long actor = ProtectionSupport.playerActorBits(snap, player);
        int action = container ? Action.CONTAINER : Action.INTERACT;
        int worldIdx = ctx.worlds().indexOf(block.getWorld());
        long chunkKey = ChunkKeys.fromBlock(block.getX(), block.getZ());
        if (!Verdict.allowed(Verdicts.decide(snap, actor, worldIdx, chunkKey, action))) {
            event.setCancelled(true);
            ctx.messages().to(player, container ? ProtectionText.NO_CONTAINER : ProtectionText.NO_INTERACT);
        }
    }
}
