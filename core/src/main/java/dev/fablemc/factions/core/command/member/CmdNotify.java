package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;
import dev.fablemc.factions.kernel.state.PlayerLedger;

/**
 * {@code /f notify [status|invites|territory|tax|all] [on|off]} (alias {@code notifications}) —
 * views or toggles the caller's per-player notification preferences (ref-commands-core.md §7.27).
 * Toggles submit {@link PrefIntent.SetNotifyPref} / {@link PrefIntent.SetTerritoryTitles}; the
 * status view is a pure snapshot read of the ledger preference bits.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdNotify extends CommandNode {

    private static final String STATUS = "status";
    private static final String INVITES = "invites";
    private static final String TERRITORY = "territory";
    private static final String TAX = "tax";
    private static final String ALL = "all";
    private static final String[] TYPES = {STATUS, INVITES, TERRITORY, TAX, ALL};
    private static final String[] ON_OFF = {"on", "off"};

    private static final MessageKey STATUS_HEADER = MessageKey.of("custom.notify.status-header");
    private static final MessageKey STATUS_LINE = MessageKey.of("custom.notify.status-line");
    private static final MessageKey USAGE = MessageKey.of("custom.notify.usage");
    private static final MessageKey UNKNOWN_TYPE = MessageKey.of("custom.notify.unknown-type");
    private static final MessageKey UPDATED = MessageKey.of("custom.notify.updated");

    CmdNotify() {
        super("notify", "notifications");
        setPermission("factions.cmd.notify");
        setRequiresPlayer(true);
        setDescription("Manage your notification settings");
        setOptionalArgs("type", "on|off");
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        String type = ctx.argCount() == 0 ? STATUS : ctx.arg(0).toLowerCase(Locale.ROOT);
        if (type.equals(STATUS)) {
            sendStatus(ctx, snap, actor);
            return;
        }
        String value = ctx.argOrEmpty(1).toLowerCase(Locale.ROOT);
        if (!value.equals("on") && !value.equals("off")) {
            ctx.send(USAGE);
            return;
        }
        boolean on = value.equals("on");
        switch (type) {
            case INVITES -> CommandFlow.submit(ctx, actor,
                    new PrefIntent.SetNotifyPref(actor, PlayerLedger.PREF_NOTIFY_INVITES, on), UPDATED, type, value);
            case TAX -> CommandFlow.submit(ctx, actor,
                    new PrefIntent.SetNotifyPref(actor, PlayerLedger.PREF_NOTIFY_TAX, on), UPDATED, type, value);
            case TERRITORY -> CommandFlow.submit(ctx, actor,
                    new PrefIntent.SetTerritoryTitles(actor, on), UPDATED, type, value);
            case ALL -> {
                ctx.services().bus().submit(new PrefIntent.SetNotifyPref(actor, PlayerLedger.PREF_NOTIFY_INVITES, on),
                        Origin.player(actor));
                ctx.services().bus().submit(new PrefIntent.SetNotifyPref(actor, PlayerLedger.PREF_NOTIFY_TAX, on),
                        Origin.player(actor));
                CommandFlow.submit(ctx, actor, new PrefIntent.SetTerritoryTitles(actor, on), UPDATED, type, value);
            }
            default -> ctx.send(UNKNOWN_TYPE);
        }
    }

    private static void sendStatus(CommandContext ctx, KernelSnapshot snap, UUID actor) {
        int prefs = PlayerLedger.DEFAULT_PREFS;
        int ordinal = snap.memberOrdinal(actor);
        if (ordinal >= 0) {
            MemberView member = snap.member(ordinal);
            if (member != null) {
                prefs = member.prefsBits();
            }
        }
        ctx.send(STATUS_HEADER);
        ctx.send(STATUS_LINE, INVITES, onOff(prefs, PlayerLedger.PREF_NOTIFY_INVITES));
        ctx.send(STATUS_LINE, TERRITORY, onOff(prefs, PlayerLedger.PREF_TERRITORY_TITLES));
        ctx.send(STATUS_LINE, TAX, onOff(prefs, PlayerLedger.PREF_NOTIFY_TAX));
    }

    private static String onOff(int prefs, int bit) {
        return PlayerLedger.pref(prefs, bit) ? "on" : "off";
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        String partial = ctx.argOrEmpty(argIndex).toLowerCase(Locale.ROOT);
        if (argIndex == 0) {
            return Completions.matching(TYPES, partial);
        }
        return argIndex == 1 ? Completions.matching(ON_OFF, partial) : List.of();
    }
}
