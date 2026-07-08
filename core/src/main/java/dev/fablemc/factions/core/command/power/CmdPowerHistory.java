package dev.fablemc.factions.core.command.power;

import java.util.List;
import java.util.Locale;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;

/**
 * {@code /f powerhistory [player] [page]} (alias {@code phist}) — the power-change log
 * (ref-commands-admin.md §7.3). The history rows live in the LEDGER-tier projection with no
 * synchronous command-side read seam (CONTRACTS §4); this command performs the reference's self /
 * other dispatch and permission gate, then renders the paged header and an empty result until a
 * history-query seam is wired (integrator note).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdPowerHistory extends CommandNode {

    private static final String OTHER_PERMISSION = "factions.cmd.power.history.other";
    private static final MessageKey CONSOLE_USAGE = MessageKey.of("power.history-console-usage");
    private static final MessageKey HEADER = MessageKey.of("power.history-header");
    private static final MessageKey EMPTY = MessageKey.of("power.history-empty");

    CmdPowerHistory() {
        super("powerhistory", "phist");
        setPermission("factions.cmd.power.history");
        setDescription("View power history");
        setOptionalArgs("player", "page");
    }

    @Override
    protected void perform(CommandContext ctx) {
        String first = ctx.argOrEmpty(0);
        boolean own = first.isBlank() || ArgParsers.parseInt(first).isPresent();
        String name;
        int page;
        if (own) {
            if (!ctx.isPlayer()) {
                ctx.send(CONSOLE_USAGE);
                return;
            }
            name = ctx.player().getName();
            page = ArgParsers.parsePage(first);
        } else {
            if (!ctx.sender().hasPermission(OTHER_PERMISSION)) {
                ctx.sendReason(ReasonCode.NO_PERMISSION);
                return;
            }
            name = first;
            page = ArgParsers.parsePage(ctx.arg(1));
        }
        ctx.send(HEADER, name, Integer.toString(page));
        ctx.send(EMPTY, name);
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0
                ? Completions.onlinePlayers(ctx.argOrEmpty(0).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
