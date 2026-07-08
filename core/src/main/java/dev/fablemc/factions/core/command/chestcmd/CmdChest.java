package dev.fablemc.factions.core.command.chestcmd;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.admin.CommandKit;
import dev.fablemc.factions.kernel.intent.ChestIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f chest [create|delete|list|open]} — the team-chest group (ref-commands-misc.md §2).
 * Opening (bare command and {@code open}) delegates to the {@link
 * dev.fablemc.factions.core.chest.Chests} seam, which loads the blob, shows the 54-slot view, and
 * commits on close through the pipeline; {@code create}/{@code delete} pre-validate the officer guard
 * and (for create) the name shape, then submit a {@link ChestIntent}; {@code list} is a pure
 * snapshot read.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread. <b>Mutability:</b>
 * a group node configured once at construction.
 */
public final class CmdChest extends CommandNode {

    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9_-]+");
    private static final int MAX_NAME_LENGTH = 32;

    /** Builds the chest group with all four children. */
    public CmdChest() {
        super("chest");
        setPermission("factions.cmd.chest");
        setRequiresPlayer(true);
        setDescription("Open or manage your faction's team chests.");
        setOptionalArgs("create|delete|list|open");
        addChild(new CmdChestCreate());
        addChild(new CmdChestDelete());
        addChild(new CmdChestList());
        addChild(new CmdChestOpen());
    }

    @Override
    protected void perform(CommandContext ctx) {
        // Bare `/f chest` opens the default team chest (ref-commands-misc.md §2.2).
        KernelSnapshot snap = ctx.snap();
        ReasonCode notInFaction = CommandGuards.requireFaction(snap, ctx.player().getUniqueId());
        if (notInFaction != null) {
            ctx.sendReason(notInFaction);
            return;
        }
        ctx.services().chests().open(ctx.player(), snap.config().limits().defaultTeamChestName());
    }

    private static List<String> chestCompletions(CommandContext ctx) {
        Faction mine = CommandGuards.factionOf(ctx.snap(), ctx.player().getUniqueId());
        return mine == null ? List.of()
                : Completions.chestNames(ctx.snap(), CommandKit.handleOf(ctx.snap(), mine),
                        ctx.argOrEmpty(0));
    }

    /**
     * {@code /f chest create <name>} — officer-gated create of a named chest (§2.3).
     */
    static final class CmdChestCreate extends CommandNode {

        private static final MessageKey INVALID_NAME = MessageKey.of("chest.invalid-name");

        CmdChestCreate() {
            super("create");
            setPermission("factions.cmd.chest.create");
            setRequiresPlayer(true);
            setDescription("Create a team chest.");
            setRequiredArgs("name");
        }

        @Override
        protected void perform(CommandContext ctx) {
            KernelSnapshot snap = ctx.snap();
            UUID actor = ctx.player().getUniqueId();
            ReasonCode guard = CommandGuards.requireOfficerOrAbove(snap, actor);
            if (guard != null) {
                ctx.sendReason(guard);
                return;
            }
            String name = ctx.arg(0).toLowerCase(Locale.ROOT);
            if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || !VALID_NAME.matcher(name).matches()) {
                ctx.send(INVALID_NAME);
                return;
            }
            Faction mine = CommandGuards.factionOf(snap, actor);
            CommandKit.submit(ctx, new ChestIntent.CreateChest(CommandKit.handleOf(snap, mine), name, actor),
                    CommandKit.playerOrigin(ctx.player()));
        }
    }

    /**
     * {@code /f chest delete <name>} — officer-gated delete (§2.4).
     */
    static final class CmdChestDelete extends CommandNode {

        CmdChestDelete() {
            super("delete");
            setPermission("factions.cmd.chest.delete");
            setRequiresPlayer(true);
            setDescription("Delete a team chest.");
            setRequiredArgs("name");
        }

        @Override
        protected void perform(CommandContext ctx) {
            KernelSnapshot snap = ctx.snap();
            UUID actor = ctx.player().getUniqueId();
            ReasonCode guard = CommandGuards.requireOfficerOrAbove(snap, actor);
            if (guard != null) {
                ctx.sendReason(guard);
                return;
            }
            Faction mine = CommandGuards.factionOf(snap, actor);
            CommandKit.submit(ctx, new ChestIntent.DeleteChest(CommandKit.handleOf(snap, mine),
                    ctx.arg(0).toLowerCase(Locale.ROOT), actor), CommandKit.playerOrigin(ctx.player()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? chestCompletions(ctx) : List.of();
        }
    }

    /**
     * {@code /f chest list} — the faction's chest names, case-insensitively sorted (§2.5).
     */
    static final class CmdChestList extends CommandNode {

        private static final MessageKey NONE = MessageKey.of("chest.none");
        private static final MessageKey HEADER = MessageKey.of("chest.list-header");
        private static final MessageKey ENTRY = MessageKey.of("chest.list-entry");

        CmdChestList() {
            super("list");
            setPermission("factions.cmd.chest");
            setRequiresPlayer(true);
            setDescription("List your faction's team chests.");
        }

        @Override
        protected void perform(CommandContext ctx) {
            KernelSnapshot snap = ctx.snap();
            Faction mine = CommandGuards.factionOf(snap, ctx.player().getUniqueId());
            if (mine == null) {
                ctx.sendReason(ReasonCode.NOT_IN_FACTION);
                return;
            }
            ChestRef[] chests = snap.state().chests().forFaction(mine.idx());
            if (chests.length == 0) {
                ctx.send(NONE);
                return;
            }
            String[] names = new String[chests.length];
            for (int i = 0; i < chests.length; i++) {
                names[i] = chests[i].name();
            }
            Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
            ctx.send(HEADER);
            for (String name : names) {
                ctx.send(ENTRY, name);
            }
        }
    }

    /**
     * {@code /f chest open <name>} — opens (auto-creating under the cap) a named chest (§2.6).
     */
    static final class CmdChestOpen extends CommandNode {

        CmdChestOpen() {
            super("open");
            setPermission("factions.cmd.chest");
            setRequiresPlayer(true);
            setDescription("Open a team chest by name.");
            setRequiredArgs("name");
        }

        @Override
        protected void perform(CommandContext ctx) {
            ReasonCode notInFaction = CommandGuards.requireFaction(ctx.snap(), ctx.player().getUniqueId());
            if (notInFaction != null) {
                ctx.sendReason(notInFaction);
                return;
            }
            ctx.services().chests().open(ctx.player(), ctx.arg(0).toLowerCase(Locale.ROOT));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? chestCompletions(ctx) : List.of();
        }
    }
}
