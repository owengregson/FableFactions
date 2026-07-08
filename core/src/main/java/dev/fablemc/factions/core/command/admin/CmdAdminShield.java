package dev.fablemc.factions.core.command.admin;

import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /fa shield <faction> <clear | <start-hour 0-23> <duration-hours>>} — sets or clears a
 * faction's war-shield window (ref-commands-admin.md §2.8). Console capable. Gates on the war-shield
 * feature, resolves the faction case-insensitively, and pre-validates the hour / duration before
 * submitting a {@link PrefIntent.SetShield} or {@link PrefIntent.ClearShield}; the reducer re-checks
 * and emits the change.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread. <b>Mutability:</b>
 * a leaf node configured once at construction.
 */
public final class CmdAdminShield extends CommandNode {

    private static final int MAX_HOUR = 23;

    /** Builds the shield command. */
    public CmdAdminShield() {
        super("shield");
        setCommandPath("/fa");
        setPermission("factions.cmd.shield");
        setDescription("Set or clear a faction's war shield.");
        setRequiredArgs("faction", "clear|start-hour");
        setOptionalArgs("duration-hours");
    }

    @Override
    protected void perform(CommandContext ctx) {
        KernelSnapshot snap = ctx.snap();
        if (!snap.config().land().warShieldEnabled()) {
            ctx.sendReason(ReasonCode.SHIELD_FEATURE_DISABLED);
            return;
        }
        Faction target = ArgParsers.factionByName(snap, ctx.arg(0));
        if (target == null) {
            ctx.sendReason(ReasonCode.FACTION_NOT_FOUND, ctx.argOrEmpty(0));
            return;
        }
        int handle = CommandKit.handleOf(snap, target);
        UUID actor = CommandKit.adminOrigin(ctx.sender()).actor();
        String action = ctx.argOrEmpty(1).trim().toLowerCase(Locale.ROOT);
        if (action.equals("clear")) {
            CommandKit.submit(ctx, new PrefIntent.ClearShield(handle, actor), CommandKit.adminOrigin(ctx.sender()));
            return;
        }
        OptionalInt startHour = ArgParsers.parseInt(ctx.arg(1));
        if (startHour.isEmpty() || startHour.getAsInt() < 0 || startHour.getAsInt() > MAX_HOUR) {
            ctx.sendReason(ReasonCode.SHIELD_INVALID_HOUR);
            return;
        }
        int maxDuration = snap.config().land().warShieldMaxDurationHours();
        OptionalInt duration = ArgParsers.parseInt(ctx.arg(2));
        if (duration.isEmpty() || duration.getAsInt() < 1 || duration.getAsInt() > maxDuration) {
            ctx.sendReason(ReasonCode.SHIELD_INVALID_DURATION, Integer.toString(maxDuration));
            return;
        }
        CommandKit.submit(ctx, new PrefIntent.SetShield(handle, startHour.getAsInt(), duration.getAsInt(),
                actor), CommandKit.adminOrigin(ctx.sender()));
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        if (argIndex == 0) {
            return Completions.factionNames(ctx.snap(), ctx.argOrEmpty(0));
        }
        return argIndex == 1 ? Completions.matching(new String[] {"clear"}, ctx.argOrEmpty(1)) : List.of();
    }
}
