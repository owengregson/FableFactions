package dev.fablemc.factions.core.command.bank;

import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.VaultBridge;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f bank [deposit|withdraw|transfer|history]} — shows the faction bank balance and hosts the
 * money-movement children (ref-commands-misc.md §1). The group's own {@code perform} is a snapshot
 * read (balance); no intent submitted.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdBank extends CommandNode {

    private static final MessageKey BALANCE = MessageKey.of("bank.balance");

    CmdBank(VaultBridge vault) {
        super("bank");
        setPermission("factions.cmd.bank");
        setRequiresPlayer(true);
        setDescription("View or manage the faction bank");
        setOptionalArgs("deposit|withdraw|transfer|history");
        addChild(new CmdBankDeposit(vault));
        addChild(new CmdBankWithdraw());
        addChild(new CmdBankTransfer());
        addChild(new CmdBankHistory());
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireFaction(snap, actor))) {
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        ctx.send(BALANCE, CommandFlow.money(faction.bank()));
    }
}
