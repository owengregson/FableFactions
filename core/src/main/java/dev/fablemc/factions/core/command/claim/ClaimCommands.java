package dev.fablemc.factions.core.command.claim;

import java.util.List;

import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * Builds the land command nodes for the {@code /f} tree (ref-commands-core.md §7.21/§7.22/§7.28):
 * claim, unclaim and the snapshot map. Each is constructor-injected with the {@link Worlds}
 * registry so it can resolve the player's world to a dense {@code worldIdx} (AM-15) when building
 * claim intents / reading the claim atlas.
 *
 * <p><b>Owning thread(s):</b> {@link #create(Worlds)} runs once at boot (Wave 4 wiring).
 * <b>Mutability:</b> stateless factory.
 */
public final class ClaimCommands {

    private ClaimCommands() {
    }

    /** The claim / unclaim / map top-level nodes, bound to the world registry. */
    public static List<CommandNode> create(Worlds worlds) {
        return List.of(new CmdClaim(worlds), new CmdUnclaim(worlds), new CmdMap(worlds));
    }
}
