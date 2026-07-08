package dev.fablemc.factions.core.command.member;

import java.util.UUID;

import dev.fablemc.factions.api.event.FactionCreateEvent;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.kernel.config.PredefinedConfig;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.NameRules;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f create <name>} — founds a new faction owned by the caller (ref-commands-core.md §7.1).
 * Pre-validates on the dispatch snapshot with the same {@link NameRules} the reducer uses
 * (proposal-C §3.7): already-in-faction, the predefined-name allow-list, then name length / charset
 * / uniqueness. Fires the cancellable {@link FactionCreateEvent} before submitting
 * {@link LifecycleIntent.CreateFaction}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread. <b>Mutability:</b>
 * configured once at construction; stateless per invocation.
 */
final class CmdCreate extends CommandNode {

    private static final MessageKey CREATED = MessageKey.of("faction.created");

    CmdCreate() {
        super("create");
        setPermission("factions.cmd.create");
        setRequiresPlayer(true);
        setDescription("Create a new faction");
        setRequiredArgs("name");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        String name = ctx.arg(0);

        if (CommandGuards.factionOf(snap, actor) != null) {
            ctx.sendReason(ReasonCode.ALREADY_IN_FACTION);
            return;
        }
        PredefinedConfig predefined = snap.config().predefined();
        if (predefined.enabled() && !predefined.isPredefinedName(name)) {
            ctx.sendReason(ReasonCode.PREDEFINED_CREATE_NOT_ALLOWED);
            return;
        }
        if (CommandFlow.blocked(ctx, NameRules.validate(name, snap.state().factionNames()))) {
            return;
        }
        if (CommandFlow.fireCancelled(new FactionCreateEvent(actor, name))) {
            return;
        }
        CommandFlow.submit(ctx, actor, new LifecycleIntent.CreateFaction(name, actor), CREATED, name);
    }
}
