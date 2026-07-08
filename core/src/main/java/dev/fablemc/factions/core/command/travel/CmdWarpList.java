package dev.fablemc.factions.core.command.travel;

import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.Warp;

/**
 * {@code /f warp list [page]} — the paginated faction warp list (ref-commands-misc.md §5.5). Pure
 * snapshot read; no intent submitted.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdWarpList extends CommandNode {

    private static final MessageKey NONE = MessageKey.of("custom.warp.none");
    private static final MessageKey HEADER_PAGE = MessageKey.of("custom.warp.header-page");
    private static final MessageKey ENTRY = MessageKey.of("custom.warp.entry");

    CmdWarpList() {
        super("list");
        setPermission("factions.cmd.warp");
        setRequiresPlayer(true);
        setDescription("List faction warps");
        setOptionalArgs("page");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireFaction(snap, actor))) {
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        Warp[] warps = snap.state().warps().forFaction(faction.idx());
        if (warps.length == 0) {
            ctx.send(NONE);
            return;
        }
        int pageSize = Math.max(1, snap.config().display().warpListPageSize());
        int page = ArgParsers.parsePage(ctx.arg(0));
        int start = Math.max(0, (page - 1) * pageSize);
        int end = Math.min(warps.length, start + pageSize);
        ctx.send(HEADER_PAGE, Integer.toString(page));
        for (int i = start; i < end; i++) {
            ctx.send(ENTRY, warps[i].name());
        }
    }
}
