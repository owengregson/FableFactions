package dev.fablemc.factions.core.command.member;

import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.NameRules;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.NameIndex;

/**
 * {@code /f rename <name>} — renames the caller's faction (ref-commands-core.md §7.8). Owner-only;
 * pre-validates format / uniqueness with {@link NameRules} then submits
 * {@link LifecycleIntent.RenameFaction} (the reducer re-renders the chat tag).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdRename extends CommandNode {

    private static final MessageKey RENAMED = MessageKey.of("custom.faction.renamed");

    CmdRename() {
        super("rename");
        setPermission("factions.cmd.rename");
        setRequiresPlayer(true);
        setDescription("Rename your faction");
        setRequiredArgs("name");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOwner(snap, actor))) {
            return;
        }
        String newName = ctx.arg(0);
        if (CommandFlow.blocked(ctx, NameRules.validateFormat(newName))) {
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        String newFold = NameIndex.fold(newName);
        if (!newFold.equals(faction.nameFolded()) && snap.factionByName(newFold) != null) {
            ctx.sendReason(ReasonCode.NAME_TAKEN);
            return;
        }
        CommandFlow.submit(ctx, actor,
                new LifecycleIntent.RenameFaction(CommandFlow.handleOf(snap, faction), newName, actor),
                RENAMED, newName);
    }
}
