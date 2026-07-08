package dev.fablemc.factions.core.command.bank;

import java.util.List;

import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.VaultBridge;

/**
 * Builds the bank command node for the {@code /f} tree (ref-commands-misc.md §1): the {@code bank}
 * group with its deposit / withdraw / transfer / history children. The deposit child is
 * constructor-injected with the {@link VaultBridge} (the wallet boundary for the AM-7 deposit flow).
 *
 * <p><b>Owning thread(s):</b> {@link #create(VaultBridge)} runs once at boot (Wave 4 wiring).
 * <b>Mutability:</b> stateless factory.
 */
public final class BankCommands {

    private BankCommands() {
    }

    /** The {@code /f bank} group (with its children), bound to the Vault wallet bridge. */
    public static List<CommandNode> create(VaultBridge vault) {
        return List.of(new CmdBank(vault));
    }
}
