package dev.fablemc.factions.core.command.travel;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.rules.TravelRules;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.Warp;

/**
 * {@code /f warp password <name> [password | clear]} — views or sets a warp's plaintext password
 * (ref-commands-misc.md §5.6). Officer-or-above. Submits {@link TravelIntent.SetWarpPassword}
 * ({@code null}/{@code clear} clears).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdWarpPassword extends CommandNode {

    private static final String CLEAR_TOKEN = "clear";
    private static final MessageKey STATUS = MessageKey.of("custom.warp.password-status");
    private static final MessageKey PASSWORD_SET = MessageKey.of("warp.password-set");
    private static final MessageKey PASSWORD_CLEARED = MessageKey.of("warp.password-cleared");

    CmdWarpPassword() {
        super("password");
        setPermission("factions.cmd.warp.password");
        setRequiresPlayer(true);
        setDescription("Set or clear a warp password");
        setRequiredArgs("warpName");
        setOptionalArgs("password|clear");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOfficerOrAbove(snap, actor))) {
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        String name = ctx.arg(0).toLowerCase(Locale.ROOT);
        if (CommandFlow.blocked(ctx, TravelRules.requireWarp(snap.state().warps(), faction.idx(), name))) {
            return;
        }
        int handle = CommandFlow.handleOf(snap, faction);
        if (ctx.argCount() < 2) {
            Warp warp = find(snap, faction.idx(), name);
            boolean has = warp != null && warp.password() != null && !warp.password().isEmpty();
            ctx.send(STATUS, name, has ? "set" : "none");
            return;
        }
        if (ctx.arg(1).equalsIgnoreCase(CLEAR_TOKEN)) {
            CommandFlow.submit(ctx, actor,
                    new TravelIntent.SetWarpPassword(handle, name, null, actor), PASSWORD_CLEARED, name);
            return;
        }
        CommandFlow.submit(ctx, actor,
                new TravelIntent.SetWarpPassword(handle, name, ctx.arg(1), actor), PASSWORD_SET, name);
    }

    private static Warp find(KernelSnapshot snap, int factionOrdinal, String name) {
        Warp[] warps = snap.state().warps().forFaction(factionOrdinal);
        for (Warp warp : warps) {
            if (warp.name().equalsIgnoreCase(name)) {
                return warp;
            }
        }
        return null;
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        if (argIndex == 0) {
            return WarpCompletions.names(ctx);
        }
        return argIndex == 1
                ? Completions.matching(new String[] {CLEAR_TOKEN}, ctx.argOrEmpty(1).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
