package dev.fablemc.factions.core.command.role;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.admin.CommandKit;
import dev.fablemc.factions.kernel.config.RoleConfig;
import dev.fablemc.factions.kernel.intent.RoleIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.Rank;

/**
 * {@code /f role …} — the custom-role group (ref-commands-admin.md §5): list / create / rename /
 * setpriority / setprefix / delete / assign. Every mutating child pre-validates the shared guards
 * (officer-or-above) and its roles.yml feature gates from the snapshot {@link RoleConfig}, then
 * submits the matching {@link RoleIntent}; the {@code RoleReducer} runs the authoritative
 * priority/authority/name-taken/limit/in-use rules and emits the outcome, so a mutation command
 * never sends a success message (CONTRACTS §6.4). {@code list} is a pure snapshot read.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread — snapshot reads +
 * one intent submit only. <b>Mutability:</b> a group node configured once at construction.
 */
public final class CmdRole extends CommandNode {

    /** Builds the role group with all seven children. */
    public CmdRole() {
        super("role");
        setPermission("factions.cmd.role");
        setRequiresPlayer(true);
        setDescription("Manage your faction's custom roles.");
        addChild(new CmdRoleList());
        addChild(new CmdRoleCreate());
        addChild(new CmdRoleRename());
        addChild(new CmdRoleSetPriority());
        addChild(new CmdRoleSetPrefix());
        addChild(new CmdRoleDelete());
        addChild(new CmdRoleAssign());
    }

    // ── shared helpers for the children ──────────────────────────────────────────────────────

    /** The actor's own faction handle after the officer guard passed, or a rejection on failure. */
    private static int officerFactionHandle(CommandContext ctx) {
        KernelSnapshot snap = ctx.snap();
        UUID actor = ctx.player().getUniqueId();
        ReasonCode guard = CommandGuards.requireOfficerOrAbove(snap, actor);
        if (guard != null) {
            ctx.sendReason(guard);
            return -1;
        }
        return CommandKit.handleOf(snap, CommandGuards.factionOf(snap, actor));
    }

    private static List<String> roleNameCompletions(CommandContext ctx) {
        Faction mine = CommandGuards.factionOf(ctx.snap(), ctx.player().getUniqueId());
        return mine == null ? List.of()
                : Completions.roleNames(ctx.snap(), CommandKit.handleOf(ctx.snap(), mine),
                        ctx.argOrEmpty(0));
    }

    /**
     * {@code /f role list} — every rank with its priority and prefix (ref-commands-admin.md §5.5).
     */
    static final class CmdRoleList extends CommandNode {

        private static final MessageKey HEADER = MessageKey.of("custom.role.list-header");
        private static final MessageKey ENTRY = MessageKey.of("custom.role.list-entry");

        CmdRoleList() {
            super("list");
            setPermission("factions.cmd.role.list");
            setRequiresPlayer(true);
            setDescription("List your faction's roles.");
        }

        @Override
        protected void perform(CommandContext ctx) {
            Faction mine = CommandGuards.factionOf(ctx.snap(), ctx.player().getUniqueId());
            if (mine == null) {
                ctx.sendReason(ReasonCode.NOT_IN_FACTION);
                return;
            }
            ctx.send(HEADER);
            Rank[] ranks = mine.ranks();
            for (int i = 0; i < ranks.length; i++) {
                Rank rank = ranks[i];
                String prefix = rank.prefix() == null ? "-" : rank.prefix();
                ctx.send(ENTRY, rank.name(), Integer.toString(rank.priority()), prefix);
            }
        }
    }

    /**
     * {@code /f role create <name> <priority> [prefix]} (ref-commands-admin.md §5.6).
     */
    static final class CmdRoleCreate extends CommandNode {

        CmdRoleCreate() {
            super("create");
            setPermission("factions.cmd.role.create");
            setRequiresPlayer(true);
            setDescription("Create a custom role.");
            setRequiredArgs("name", "priority");
            setOptionalArgs("prefix");
        }

        @Override
        protected void perform(CommandContext ctx) {
            int handle = officerFactionHandle(ctx);
            if (handle < 0) {
                return;
            }
            RoleConfig role = ctx.snap().config().role();
            if (!role.customEnabled() || !role.overridesEnabled()) {
                ctx.sendReason(ReasonCode.ROLE_FEATURE_DISABLED);
                return;
            }
            OptionalInt priority = ArgParsers.parseInt(ctx.arg(1));
            if (priority.isEmpty()) {
                ctx.sendReason(ReasonCode.ROLE_INVALID_PRIORITY);
                return;
            }
            String prefix = ctx.argCount() >= 3 ? ctx.arg(2) : null;
            CommandKit.submit(ctx, new RoleIntent.CreateRole(handle, ctx.player().getUniqueId(),
                    ctx.arg(0), priority.getAsInt(), prefix), CommandKit.playerOrigin(ctx.player()));
        }
    }

    /**
     * {@code /f role rename <old> <newName>} (ref-commands-admin.md §5.7).
     */
    static final class CmdRoleRename extends CommandNode {

        CmdRoleRename() {
            super("rename");
            setPermission("factions.cmd.role.edit");
            setRequiresPlayer(true);
            setDescription("Rename a custom role.");
            setRequiredArgs("old", "newName");
        }

        @Override
        protected void perform(CommandContext ctx) {
            int handle = officerFactionHandle(ctx);
            if (handle < 0) {
                return;
            }
            RoleConfig role = ctx.snap().config().role();
            if (!role.customEnabled() || !role.overridesEnabled()) {
                ctx.sendReason(ReasonCode.ROLE_FAILED);
                return;
            }
            CommandKit.submit(ctx, new RoleIntent.RenameRole(handle, ctx.player().getUniqueId(),
                    ctx.arg(0), ctx.arg(1)), CommandKit.playerOrigin(ctx.player()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? roleNameCompletions(ctx) : List.of();
        }
    }

    /**
     * {@code /f role setpriority <role> <priority>} (ref-commands-admin.md §5.8).
     */
    static final class CmdRoleSetPriority extends CommandNode {

        CmdRoleSetPriority() {
            super("setpriority");
            setPermission("factions.cmd.role.edit");
            setRequiresPlayer(true);
            setDescription("Set a role's priority.");
            setRequiredArgs("role", "priority");
        }

        @Override
        protected void perform(CommandContext ctx) {
            int handle = officerFactionHandle(ctx);
            if (handle < 0) {
                return;
            }
            RoleConfig role = ctx.snap().config().role();
            if (!role.customEnabled() || !role.overridesEnabled()) {
                ctx.sendReason(ReasonCode.ROLE_FAILED);
                return;
            }
            OptionalInt priority = ArgParsers.parseInt(ctx.arg(1));
            if (priority.isEmpty()) {
                ctx.sendReason(ReasonCode.ROLE_INVALID_PRIORITY);
                return;
            }
            CommandKit.submit(ctx, new RoleIntent.SetRolePriority(handle, ctx.player().getUniqueId(),
                    ctx.arg(0), priority.getAsInt()), CommandKit.playerOrigin(ctx.player()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? roleNameCompletions(ctx) : List.of();
        }
    }

    /**
     * {@code /f role setprefix <role> <prefix|none>} (ref-commands-admin.md §5.9).
     */
    static final class CmdRoleSetPrefix extends CommandNode {

        CmdRoleSetPrefix() {
            super("setprefix");
            setPermission("factions.cmd.role.edit");
            setRequiresPlayer(true);
            setDescription("Set or clear a role's prefix.");
            setRequiredArgs("role", "prefix");
        }

        @Override
        protected void perform(CommandContext ctx) {
            int handle = officerFactionHandle(ctx);
            if (handle < 0) {
                return;
            }
            RoleConfig role = ctx.snap().config().role();
            if (!role.prefixesEnabled() || !role.overridesEnabled()) {
                ctx.sendReason(ReasonCode.ROLE_PREFIX_DISABLED);
                return;
            }
            String prefix = "none".equalsIgnoreCase(ctx.arg(1)) ? null : ctx.arg(1);
            CommandKit.submit(ctx, new RoleIntent.SetRolePrefix(handle, ctx.player().getUniqueId(),
                    ctx.arg(0), prefix), CommandKit.playerOrigin(ctx.player()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            if (argIndex == 0) {
                return roleNameCompletions(ctx);
            }
            return argIndex == 1 ? Completions.matching(new String[] {"none"}, ctx.argOrEmpty(1)) : List.of();
        }
    }

    /**
     * {@code /f role delete <role>} (ref-commands-admin.md §5.10).
     */
    static final class CmdRoleDelete extends CommandNode {

        CmdRoleDelete() {
            super("delete");
            setPermission("factions.cmd.role.delete");
            setRequiresPlayer(true);
            setDescription("Delete a custom role.");
            setRequiredArgs("role");
        }

        @Override
        protected void perform(CommandContext ctx) {
            int handle = officerFactionHandle(ctx);
            if (handle < 0) {
                return;
            }
            RoleConfig role = ctx.snap().config().role();
            if (!role.customEnabled() || !role.overridesEnabled()) {
                ctx.sendReason(ReasonCode.ROLE_FAILED);
                return;
            }
            CommandKit.submit(ctx, new RoleIntent.DeleteRole(handle, ctx.player().getUniqueId(),
                    ctx.arg(0)), CommandKit.playerOrigin(ctx.player()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? roleNameCompletions(ctx) : List.of();
        }
    }

    /**
     * {@code /f role assign <player> <role>} (ref-commands-admin.md §5.11).
     */
    static final class CmdRoleAssign extends CommandNode {

        CmdRoleAssign() {
            super("assign");
            setPermission("factions.cmd.role.assign");
            setRequiresPlayer(true);
            setDescription("Assign a role to a member.");
            setRequiredArgs("player", "role");
        }

        @Override
        protected void perform(CommandContext ctx) {
            int handle = officerFactionHandle(ctx);
            if (handle < 0) {
                return;
            }
            if (!ctx.snap().config().role().overridesEnabled()) {
                ctx.sendReason(ReasonCode.ROLE_FAILED);
                return;
            }
            UUID target = ArgParsers.offlineId(ctx.arg(0));
            if (target == null) {
                ctx.sendReason(ReasonCode.PLAYER_NOT_FOUND, ctx.argOrEmpty(0));
                return;
            }
            CommandKit.submit(ctx, new RoleIntent.AssignRole(handle, ctx.player().getUniqueId(),
                    target, ctx.arg(1)), CommandKit.playerOrigin(ctx.player()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            if (argIndex == 0) {
                return Completions.onlinePlayers(ctx.argOrEmpty(0));
            }
            return argIndex == 1 ? roleNameCompletions(ctx) : List.of();
        }
    }
}
