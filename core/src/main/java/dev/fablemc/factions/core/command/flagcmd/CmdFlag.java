package dev.fablemc.factions.core.command.flagcmd;

import java.util.List;
import java.util.UUID;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.admin.CommandKit;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f flag [list|set]} — the player-facing faction-flag group (ref-commands-admin.md §4.3).
 * {@code list} renders every flag's effective value and lock state; {@code set} pre-validates the
 * officer guard, the flag id, and the {@code player-editable} lock, then submits a
 * {@link PrefIntent.SetFactionFlag} with {@code byAdmin=false}. The bare command delegates to
 * {@code list}. Admin flag overrides (which ignore the lock) live in {@code /fa flag}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread. <b>Mutability:</b>
 * a group node configured once at construction.
 */
public final class CmdFlag extends CommandNode {

    private final CmdFlagList list = new CmdFlagList();

    /** Builds the flag group with its {@code list} and {@code set} children. */
    public CmdFlag() {
        super("flag");
        setPermission("factions.cmd.flag");
        setRequiresPlayer(true);
        setDescription("View or set your faction's flags.");
        setOptionalArgs("list|set");
        addChild(list);
        addChild(new CmdFlagSet());
    }

    @Override
    protected void perform(CommandContext ctx) {
        // Bare `/f flag` shows the list (ref-commands-admin.md §4.3).
        list.perform(ctx);
    }

    /**
     * {@code /f flag list} — every flag with its ON/OFF value and lock note (§4.4).
     */
    static final class CmdFlagList extends CommandNode {

        private static final MessageKey HEADER = MessageKey.of("flag.list-header");
        private static final MessageKey ENTRY_ON = MessageKey.of("flag.entry-on");
        private static final MessageKey ENTRY_OFF = MessageKey.of("flag.entry-off");

        CmdFlagList() {
            super("list");
            setPermission("factions.cmd.flag");
            setRequiresPlayer(true);
            setDescription("List your faction's flags.");
        }

        @Override
        protected void perform(CommandContext ctx) {
            KernelSnapshot snap = ctx.snap();
            Faction mine = CommandGuards.factionOf(snap, ctx.player().getUniqueId());
            if (mine == null) {
                ctx.sendReason(ReasonCode.NOT_IN_FACTION);
                return;
            }
            ConfigImage.FlagDefaults defaults = snap.config().flagDefaults();
            ctx.send(HEADER, mine.name());
            for (int ordinal = 0; ordinal < Faction.FLAG_COUNT; ordinal++) {
                boolean value = mine.flag(ordinal, defaults.defaultOf(ordinal));
                String editNote = defaults.editableOf(ordinal) ? "" : " (locked)";
                ctx.send(value ? ENTRY_ON : ENTRY_OFF, FactionFlags.id(ordinal), editNote);
            }
        }
    }

    /**
     * {@code /f flag set <flag> [on|off]} — toggles or sets a flag if editable (§4.5).
     */
    static final class CmdFlagSet extends CommandNode {

        private static final MessageKey VALUE_INVALID = MessageKey.of("flag.value-invalid");

        CmdFlagSet() {
            super("set");
            setPermission("factions.cmd.flag.set");
            setRequiresPlayer(true);
            setDescription("Set a faction flag.");
            setRequiredArgs("flag");
            setOptionalArgs("on|off");
        }

        @Override
        protected void perform(CommandContext ctx) {
            KernelSnapshot snap = ctx.snap();
            UUID actor = ctx.player().getUniqueId();
            ReasonCode notInFaction = CommandGuards.requireFaction(snap, actor);
            if (notInFaction != null) {
                ctx.sendReason(notInFaction);
                return;
            }
            ReasonCode notOfficer = CommandGuards.requireOfficerOrAbove(snap, actor);
            if (notOfficer != null) {
                ctx.sendReason(notOfficer);
                return;
            }
            int ordinal = FactionFlags.ordinalOf(ctx.arg(0));
            if (ordinal < 0) {
                ctx.sendReason(ReasonCode.FLAG_INVALID, ctx.argOrEmpty(0));
                return;
            }
            if (!snap.config().flagDefaults().editableOf(ordinal)) {
                ctx.sendReason(ReasonCode.FLAG_NOT_EDITABLE, FactionFlags.id(ordinal));
                return;
            }
            Faction mine = CommandGuards.factionOf(snap, actor);
            boolean newValue;
            if (ctx.argCount() < 2) {
                newValue = !mine.flag(ordinal, snap.config().flagDefaults().defaultOf(ordinal));
            } else {
                Boolean parsed = FactionFlags.parseValue(ctx.arg(1));
                if (parsed == null) {
                    ctx.send(VALUE_INVALID);
                    return;
                }
                newValue = parsed;
            }
            CommandKit.submit(ctx, new PrefIntent.SetFactionFlag(
                    CommandKit.handleOf(snap, mine), ordinal, newValue, false, actor),
                    CommandKit.playerOrigin(ctx.player()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            if (argIndex == 0) {
                return Completions.matching(FactionFlags.ids(), ctx.argOrEmpty(0));
            }
            return argIndex == 1 ? Completions.matching(new String[] {"on", "off"}, ctx.argOrEmpty(1)) : List.of();
        }
    }
}
