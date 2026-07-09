package dev.fablemc.factions.core.command.travel;

import java.util.UUID;

import org.bukkit.Location;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * {@code /f sethome} — sets the faction home to the caller's current location
 * (ref-commands-core.md §7.13). Officer-or-above. Submits {@link TravelIntent.SetHome}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread.
 * <b>Mutability:</b> holds the injected {@link Worlds} registry; stateless per invocation.
 */
final class CmdSetHome extends CommandNode {

    private static final MessageKey SET = MessageKey.of("custom.home.set");
    private static final MessageKey SET_PROTECTED = MessageKey.of("custom.home.set-protected");

    private final Worlds worlds;

    CmdSetHome(Worlds worlds) {
        super("sethome");
        setPermission("factions.cmd.sethome");
        setRequiresPlayer(true);
        setDescription("Set your faction home");
        this.worlds = worlds;
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOfficerOrAbove(snap, actor))) {
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        Location loc = ctx.player().getLocation();
        int worldIdx = worlds.register(loc.getWorld().getUID());
        // Own-territory guard (finding #13): a home may only be planted inside the faction's own claim,
        // never in enemy/unclaimed land — otherwise an officer sets home in an enemy base and teleports
        // the squad past its defenses. Same claim-owner read the /f fly own-territory gate uses.
        long chunkKey = ChunkKeys.key(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        int owner = snap.claimOwnerAt(worldIdx, chunkKey);
        if (owner == FactionHandle.WILDERNESS || FactionHandle.ordinal(owner) != faction.idx()) {
            ctx.send(SET_PROTECTED);
            return;
        }
        CommandFlow.submit(ctx, actor,
                new TravelIntent.SetHome(CommandFlow.handleOf(snap, faction), worldIdx,
                        loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(), actor),
                SET);
    }
}
