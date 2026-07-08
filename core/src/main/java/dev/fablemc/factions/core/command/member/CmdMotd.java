package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f motd [clear | <text…>]} — views or sets the faction MOTD (ref-commands-core.md §7.10).
 * Any member may view; setting / clearing requires officer-or-above. Submits
 * {@link LifecycleIntent.SetMotd} ({@code ""} clears).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdMotd extends CommandNode {

    private static final int MAX_MOTD = 250;
    private static final String CLEAR_TOKEN = "clear";
    private static final MessageKey NONE = MessageKey.of("motd.none");
    private static final MessageKey HEADER = MessageKey.of("motd.header");
    private static final MessageKey DISPLAY = MessageKey.of("motd.display");
    private static final MessageKey TOO_LONG = MessageKey.of("motd.too-long");
    private static final MessageKey SET = MessageKey.of("motd.set");
    private static final MessageKey CLEARED = MessageKey.of("motd.cleared");

    CmdMotd() {
        super("motd");
        setPermission("factions.cmd.motd");
        setRequiresPlayer(true);
        setDescription("View or set the faction MOTD");
        setOptionalArgs("clear|text");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        Faction faction = CommandGuards.factionOf(snap, actor);
        if (faction == null) {
            ctx.sendReason(ReasonCode.NOT_IN_FACTION);
            return;
        }
        if (ctx.argCount() == 0) {
            String motd = faction.motd();
            if (motd == null || motd.isEmpty()) {
                ctx.send(NONE);
            } else {
                ctx.send(HEADER);
                ctx.send(DISPLAY, motd);
            }
            return;
        }
        if (CommandFlow.blocked(ctx, CommandGuards.requireOfficerOrAbove(snap, actor))) {
            return;
        }
        int handle = CommandFlow.handleOf(snap, faction);
        if (ctx.argOrEmpty(0).equalsIgnoreCase(CLEAR_TOKEN)) {
            CommandFlow.submit(ctx, actor, new LifecycleIntent.SetMotd(handle, "", actor), CLEARED);
            return;
        }
        String motd = String.join(" ", ctx.args()).trim();
        if (motd.length() > MAX_MOTD) {
            ctx.send(TOO_LONG);
            return;
        }
        CommandFlow.submit(ctx, actor, new LifecycleIntent.SetMotd(handle, motd, actor), SET);
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0
                ? Completions.matching(new String[] {CLEAR_TOKEN}, ctx.argOrEmpty(0).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
