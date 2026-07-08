package dev.fablemc.factions.core.command.bank;

import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f bank history [page]} — the faction bank-transaction log (ref-commands-misc.md §1.5).
 * The log rows live in the LEDGER-tier storage projection, which has no synchronous read seam
 * exposed to the command layer (CONTRACTS §4 forbids JDBC from a command body); this command renders
 * the paged header and reports an empty page until a history-query seam is wired (integrator note).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdBankHistory extends CommandNode {

    private static final MessageKey HEADER = MessageKey.of("bank.history-header");
    private static final MessageKey EMPTY = MessageKey.of("bank.history-empty");

    CmdBankHistory() {
        super("history");
        setPermission("factions.cmd.bank.history");
        setRequiresPlayer(true);
        setDescription("View the faction bank history");
        setOptionalArgs("page");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireFaction(snap, actor))) {
            return;
        }
        int page = ArgParsers.parsePage(ctx.arg(0));
        ctx.send(HEADER, Integer.toString(page));
        ctx.send(EMPTY);
    }
}
