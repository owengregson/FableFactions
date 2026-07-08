package dev.fablemc.factions.core.command.admin;

import java.util.List;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.flagcmd.FactionFlags;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /fa flag <faction> <flag> [on|off]} — the admin flag override (ref-commands-admin.md §2.9).
 * Console capable. Unlike {@code /f flag set}, this ignores the {@code player-editable} lock and any
 * rank requirement: it resolves the target and flag, toggles or sets the value, and submits a
 * {@link PrefIntent.SetFactionFlag} with {@code byAdmin=true}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread. <b>Mutability:</b>
 * a leaf node configured once at construction.
 */
public final class CmdAdminFlag extends CommandNode {

    private static final MessageKey VALUE_INVALID = MessageKey.of("flag.value-invalid");
    private static final String[] ON_OFF = {"on", "off"};

    /** Builds the admin-flag command. */
    public CmdAdminFlag() {
        super("flag");
        setCommandPath("/fa");
        setPermission("factions.admin");
        setDescription("Override a faction flag (ignores locks).");
        setRequiredArgs("faction", "flag");
        setOptionalArgs("on|off");
    }

    @Override
    protected void perform(CommandContext ctx) {
        KernelSnapshot snap = ctx.snap();
        Faction target = ArgParsers.factionByName(snap, ctx.arg(0));
        if (target == null) {
            ctx.sendReason(ReasonCode.FACTION_NOT_FOUND, ctx.argOrEmpty(0));
            return;
        }
        int ordinal = FactionFlags.ordinalOf(ctx.arg(1));
        if (ordinal < 0) {
            ctx.sendReason(ReasonCode.FLAG_INVALID, ctx.argOrEmpty(1));
            return;
        }
        boolean newValue;
        if (ctx.argCount() < 3) {
            newValue = !target.flag(ordinal, snap.config().flagDefaults().defaultOf(ordinal));
        } else {
            Boolean parsed = FactionFlags.parseValue(ctx.arg(2));
            if (parsed == null) {
                ctx.send(VALUE_INVALID);
                return;
            }
            newValue = parsed;
        }
        CommandKit.submit(ctx, new PrefIntent.SetFactionFlag(CommandKit.handleOf(snap, target), ordinal,
                newValue, true, CommandKit.adminOrigin(ctx.sender()).actor()), CommandKit.adminOrigin(ctx.sender()));
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        if (argIndex == 0) {
            return Completions.factionNames(ctx.snap(), ctx.argOrEmpty(0));
        }
        if (argIndex == 1) {
            return Completions.matching(FactionFlags.ids(), ctx.argOrEmpty(1));
        }
        return argIndex == 2 ? Completions.matching(ON_OFF, ctx.argOrEmpty(2)) : List.of();
    }
}
