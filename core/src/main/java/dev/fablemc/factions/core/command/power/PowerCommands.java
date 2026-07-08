package dev.fablemc.factions.core.command.power;

import java.util.List;

import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.VaultBridge;

/**
 * Builds the player-facing power command nodes for the {@code /f} tree (ref-commands-admin.md §7):
 * the {@code power} group (with its {@code buy} child) and the top-level {@code powerhistory}. The
 * buy path is constructor-injected with the {@link VaultBridge} wallet boundary.
 *
 * <p><b>Owning thread(s):</b> {@link #create(VaultBridge)} runs once at boot (Wave 4 wiring).
 * <b>Mutability:</b> stateless factory.
 */
public final class PowerCommands {

    private PowerCommands() {
    }

    /** The {@code /f power} group and {@code /f powerhistory}, bound to the Vault wallet bridge. */
    public static List<CommandNode> create(VaultBridge vault) {
        return List.of(new CmdPower(vault), new CmdPowerHistory());
    }
}
