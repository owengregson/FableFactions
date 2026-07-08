package dev.fablemc.factions.core.command.power;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.VaultBridge;
import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * {@code /f power [buy]} — the player power group (ref-commands-admin.md §7.1). A pure usage/group
 * stub whose own {@code perform} prints the buy usage; the {@code buy} child does the work.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdPower extends CommandNode {

    private static final MessageKey USAGE_BUY = MessageKey.of("custom.power.usage-buy");

    CmdPower(VaultBridge vault) {
        super("power");
        setPermission("factions.cmd.power");
        setRequiresPlayer(true);
        setDescription("Buy faction power");
        setOptionalArgs("buy");
        addChild(new CmdPowerBuy(vault));
    }

    @Override
    protected void perform(CommandContext ctx) {
        ctx.send(USAGE_BUY);
    }
}
