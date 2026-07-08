package dev.fablemc.factions.core.command.travel;

import java.util.List;

import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * Builds the travel command nodes for the {@code /f} tree (ref-commands-core.md §7.12–7.15,
 * ref-commands-misc.md §5): home, sethome, unsethome, the warp group (+set/delete/list/password/cost)
 * and fly. Location-building nodes are constructor-injected with the {@link Worlds} registry.
 *
 * <p><b>Owning thread(s):</b> {@link #create(Worlds)} runs once at boot (Wave 4 wiring).
 * <b>Mutability:</b> stateless factory.
 */
public final class TravelCommands {

    private TravelCommands() {
    }

    /** The home / warp / fly top-level nodes, bound to the world registry. */
    public static List<CommandNode> create(Worlds worlds) {
        return List.of(
                new CmdHome(),
                new CmdSetHome(worlds),
                new CmdUnsetHome(),
                new CmdWarp(worlds),
                new CmdFly(worlds));
    }
}
