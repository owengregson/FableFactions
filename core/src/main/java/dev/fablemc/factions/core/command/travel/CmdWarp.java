package dev.fablemc.factions.core.command.travel;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.Warp;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * {@code /f warp [name]} — teleports to a named warp (via the {@code Travel} seam) or lists the
 * faction warps, and hosts the {@code set/delete/list/password/cost} children (ref-commands-misc.md
 * §5). The warmup / password / Vault-cost / teleport all live behind the seam; the group's own
 * {@code perform} is a snapshot read (list) or a seam delegation (teleport).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdWarp extends CommandNode {

    private static final MessageKey NONE = MessageKey.of("custom.warp.none");
    private static final MessageKey HEADER = MessageKey.of("custom.warp.header");
    private static final MessageKey ENTRY = MessageKey.of("custom.warp.entry");

    CmdWarp(Worlds worlds) {
        super("warp");
        setPermission("factions.cmd.warp");
        setRequiresPlayer(true);
        setDescription("Teleport to or list faction warps");
        setOptionalArgs("name");
        addChild(new CmdWarpSet(worlds));
        addChild(new CmdWarpDelete());
        addChild(new CmdWarpList());
        addChild(new CmdWarpPassword());
        addChild(new CmdWarpCost());
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireFaction(snap, actor))) {
            return;
        }
        if (ctx.argCount() == 0) {
            listWarps(ctx, snap, CommandGuards.factionOf(snap, actor));
            return;
        }
        ctx.services().travel().beginWarp(ctx.player(), ctx.arg(0), ctx.arg(1));
    }

    static void listWarps(CommandContext ctx, KernelSnapshot snap, Faction faction) {
        Warp[] warps = snap.state().warps().forFaction(faction.idx());
        if (warps.length == 0) {
            ctx.send(NONE);
            return;
        }
        ctx.send(HEADER);
        for (Warp warp : warps) {
            ctx.send(ENTRY, warp.name());
        }
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        if (argIndex != 0 || !ctx.isPlayer()) {
            return List.of();
        }
        Faction faction = CommandGuards.factionOf(ctx.snap(), ctx.player().getUniqueId());
        if (faction == null) {
            return List.of();
        }
        return Completions.warpNames(ctx.snap(), CommandFlow.handleOf(ctx.snap(), faction),
                ctx.argOrEmpty(0).toLowerCase(Locale.ROOT));
    }
}
