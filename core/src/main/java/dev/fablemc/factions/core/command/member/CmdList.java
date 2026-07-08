package dev.fablemc.factions.core.command.member;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.rules.FactionAggregates;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f list [page] [sort]} — a paginated directory of every normal faction sorted by
 * {@code members|power|land|bank|name} (ref-commands-core.md §7.25). Console-safe; a pure snapshot
 * read with no intent submitted.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdList extends CommandNode {

    private static final String[] SORTS = {"members", "power", "land", "bank", "name"};
    private static final MessageKey SEPARATOR = MessageKey.of("custom.list.separator");
    private static final MessageKey HEADER = MessageKey.of("custom.list.header");
    private static final MessageKey ROW = MessageKey.of("custom.list.row");
    private static final MessageKey NONE = MessageKey.of("custom.list.none");

    CmdList() {
        super("list");
        setPermission("factions.cmd.list");
        setDescription("List all factions");
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
            sort = normalizeSort(ctx.argOrEmpty(1), "name");
        }
        List<FactionRow> rows = gather(snap);
        if (rows.isEmpty()) {
            ctx.send(NONE);
            return;
        }
        sortRows(rows, sort);
        int pageSize = Math.max(1, snap.config().display().listPageSize());
        render(ctx, rows, page, pageSize, sort);
    }

    private void render(CommandContext ctx, List<FactionRow> rows, int page, int pageSize, String sort) {
        int start = Math.max(0, (page - 1) * pageSize);
        int end = Math.min(rows.size(), start + pageSize);
        ctx.send(SEPARATOR);
        ctx.send(HEADER, Integer.toString(page), sort);
        for (int i = start; i < end; i++) {
            FactionRow row = rows.get(i);
            ctx.send(ROW, Integer.toString(i + 1), row.name,
                    Integer.toString(row.members), Integer.toString(row.land), CommandFlow.money(row.bank));
        }
        ctx.send(SEPARATOR);
    }

    static List<FactionRow> gather(KernelSnapshot snap) {
        long now = System.currentTimeMillis();
        int tick = snap.tick();
        FactionArena arena = snap.state().factions();
        int highWater = arena.highWater();
        List<FactionRow> rows = new ArrayList<>();
        for (int ord = FactionHandle.FIRST_NORMAL_ORDINAL; ord < highWater; ord++) {
            Faction faction = arena.at(ord);
            if (faction == null || !faction.isNormal()) {
                continue;
            }
            int handle = arena.handleOf(ord);
            rows.add(new FactionRow(faction.name(),
                    FactionAggregates.memberCount(snap.state(), handle),
                    faction.landCount(), faction.bank(),
                    FactionAggregates.totalPower(snap.state(), faction, tick, now)));
        }
        return rows;
    }

    static void sortRows(List<FactionRow> rows, String sort) {
        switch (sort) {
            case "members" -> rows.sort((a, b) -> Integer.compare(b.members, a.members));
            case "land" -> rows.sort((a, b) -> Integer.compare(b.land, a.land));
            case "bank" -> rows.sort((a, b) -> Double.compare(b.bank, a.bank));
            case "power" -> rows.sort((a, b) -> Double.compare(b.power, a.power));
            default -> rows.sort((a, b) -> a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT)));
        }
    }

    private static boolean isSort(String value) {
        return normalizeSort(value, null) != null;
    }

    private static String normalizeSort(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String sort : SORTS) {
            if (sort.equals(lower)) {
                return sort;
            }
        }
        return fallback;
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return (argIndex == 0 || argIndex == 1)
                ? Completions.matching(SORTS, ctx.argOrEmpty(argIndex).toLowerCase(Locale.ROOT))
                : List.of();
    }

    /** A cold-path faction directory row (list / top). */
    static final class FactionRow {
        final String name;
        final int members;
        final int land;
        final double bank;
        final double power;

        FactionRow(String name, int members, int land, double bank, double power) {
            this.name = name;
            this.members = members;
            this.land = land;
            this.bank = bank;
            this.power = power;
        }
    }
}
