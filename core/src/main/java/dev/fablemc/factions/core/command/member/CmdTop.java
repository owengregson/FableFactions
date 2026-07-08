package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.Locale;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f top [page] [sort]} — the leaderboard, sorted by {@code power|bank|land} (default
 * {@code power}), reusing the {@link CmdList} directory gather (ref-commands-core.md §7.26).
 * Console-safe; a pure snapshot read.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdTop extends CommandNode {

    private static final String[] SORTS = {"power", "bank", "land"};
    private static final MessageKey SEPARATOR = MessageKey.of("custom.list.separator");
    private static final MessageKey HEADER = MessageKey.of("custom.top.header");
    private static final MessageKey ROW = MessageKey.of("custom.top.row");
    private static final MessageKey NONE = MessageKey.of("custom.list.none");

    CmdTop() {
        super("top");
        setPermission("factions.cmd.top");
        setDescription("Faction leaderboard");
        setOptionalArgs("page", "sort");
    }

    @Override
    protected void perform(CommandContext ctx) {
        KernelSnapshot snap = ctx.snap();
        String sort;
        int page;
        if (isSort(ctx.argOrEmpty(0))) {
            sort = ctx.arg(0).toLowerCase(Locale.ROOT);
            page = 1;
        } else {
            page = ArgParsers.parsePage(ctx.arg(0));
            sort = normalizeSort(ctx.argOrEmpty(1));
        }
        List<CmdList.FactionRow> rows = CmdList.gather(snap);
        if (rows.isEmpty()) {
            ctx.send(NONE);
            return;
        }
        CmdList.sortRows(rows, sort);
        int pageSize = Math.max(1, snap.config().display().topPageSize());
        int start = Math.max(0, (page - 1) * pageSize);
        int end = Math.min(rows.size(), start + pageSize);
        ctx.send(SEPARATOR);
        ctx.send(HEADER, Integer.toString(page), sort);
        for (int i = start; i < end; i++) {
            CmdList.FactionRow row = rows.get(i);
            ctx.send(ROW, Integer.toString(i + 1), row.name,
                    CommandFlow.fmt1(row.power), Integer.toString(row.land), CommandFlow.money(row.bank));
        }
        ctx.send(SEPARATOR);
    }

    private static boolean isSort(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String sort : SORTS) {
            if (sort.equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeSort(String value) {
        return isSort(value) ? value.toLowerCase(Locale.ROOT) : "power";
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return (argIndex == 0 || argIndex == 1)
                ? Completions.matching(SORTS, ctx.argOrEmpty(argIndex).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
