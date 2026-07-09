package dev.fablemc.factions.core.command.member;

import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f desc <text…>} — sets the caller's faction description (ref-commands-core.md §7.9).
 * Owner-only; the joined text must be ≤250 characters (the reducer re-checks). Submits
 * {@link LifecycleIntent.SetDescription}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdDesc extends CommandNode {

    private static final int MAX_DESCRIPTION = 250;
    private static final MessageKey UPDATED = MessageKey.of("custom.faction.desc-updated");

    CmdDesc() {
        super("desc");
        setPermission("factions.cmd.desc");
        setRequiresPlayer(true);
        setDescription("Set your faction description");
        setRequiredArgs("text");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOwner(snap, actor))) {
            return;
        }
        // Strip the legacy section sign so a player can't smuggle raw colour/format codes into
        // their description and have them render in other players' /f info (finding #44). '&' is
        // left intact — it is a legitimate character and the render path never translates it.
        String description = CommandFlow.stripLegacyCodes(String.join(" ", ctx.args()).trim());
        if (description.length() > MAX_DESCRIPTION) {
            ctx.sendReason(ReasonCode.DESCRIPTION_TOO_LONG);
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        CommandFlow.submit(ctx, actor,
                new LifecycleIntent.SetDescription(CommandFlow.handleOf(snap, faction), description, actor),
                UPDATED);
    }
}
