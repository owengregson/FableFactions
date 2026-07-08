package dev.fablemc.factions.core.command.admin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.flagcmd.FactionFlags;
import dev.fablemc.factions.kernel.intent.PowerIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;

/**
 * {@code /fa power …} — the admin power group (ref-commands-admin.md §3): view / set / add / remove
 * / reset / freeze / history. Each mutating child pre-validates the target and reason and submits the
 * matching {@link PowerIntent}; the {@code PowerReducer} clamps, applies the per-event cap, and emits
 * the {@code PowerChanged} effect the fan-out renders, so a mutation never sends a success message
 * (CONTRACTS §6.4). {@code view} reads the snapshot; {@code history} pages the {@link AdminQueries}
 * read seam.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread. <b>Mutability:</b>
 * a group node holding the injected query seam (for the history child).
 */
public final class CmdAdminPower extends CommandNode {

    private static final MessageKey INVALID_AMOUNT = MessageKey.of("power.admin-invalid-amount");
    private static final MessageKey REASON_REQUIRED = MessageKey.of("power.admin-reason-required");
    private static final MessageKey FREEZE_USAGE = MessageKey.of("power.admin-freeze-usage");

    /** Builds the admin-power group with all seven children. */
    public CmdAdminPower(AdminQueries queries) {
        super("power");
        setCommandPath("/fa");
        setPermission("factions.cmd.admin.power");
        setDescription("Admin power management.");
        addChild(new View());
        addChild(new Set());
        addChild(new Add());
        addChild(new Remove());
        addChild(new Reset());
        addChild(new Freeze());
        addChild(new History(queries));
    }

    // ── shared helpers ───────────────────────────────────────────────────────────────────────

    /** Resolves {@code arg0} to a known member's uuid, or sends {@code player-not-found} and null. */
    private static UUID resolveTarget(CommandContext ctx) {
        UUID uuid = ArgParsers.offlineId(ctx.arg(0));
        if (uuid == null || ctx.snap().memberOrdinal(uuid) < 0) {
            ctx.sendReason(ReasonCode.PLAYER_NOT_FOUND, ctx.argOrEmpty(0));
            return null;
        }
        return uuid;
    }

    /** Joins {@code args[startIndex..]} into a trimmed space-separated reason ({@code ""} if none). */
    private static String joinReason(CommandContext ctx, int startIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < ctx.argCount(); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(ctx.arg(i));
        }
        return sb.toString().trim();
    }

    private static UUID adminActor(CommandContext ctx) {
        return CommandKit.adminOrigin(ctx.sender()).actor();
    }

    private static List<String> onlineNameCompletions(CommandContext ctx) {
        return Completions.onlinePlayers(ctx.argOrEmpty(0));
    }

    /** {@code /fa power view <player>} (§3.1). */
    static final class View extends CommandNode {

        private static final MessageKey VIEW = MessageKey.of("power.admin-view");

        View() {
            super("view");
            setCommandPath("/fa power");
            setPermission("factions.cmd.admin.power.view");
            setDescription("View a player's power.");
            setRequiredArgs("player");
        }

        @Override
        protected void perform(CommandContext ctx) {
            UUID target = resolveTarget(ctx);
            if (target == null) {
                return;
            }
            KernelSnapshot snap = ctx.snap();
            int ordinal = snap.memberOrdinal(target);
            MemberView member = snap.member(ordinal);
            double power = snap.powerAt(ordinal, snap.tick());
            boolean frozen = member != null && member.powerFrozen();
            ctx.send(VIEW, ctx.arg(0), String.format(Locale.ROOT, "%.2f", power), Boolean.toString(frozen));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? onlineNameCompletions(ctx) : List.of();
        }
    }

    /** {@code /fa power set <player> <amount> <reason...>} (§3.2). */
    static final class Set extends CommandNode {

        Set() {
            super("set");
            setCommandPath("/fa power");
            setPermission("factions.cmd.admin.power.set");
            setDescription("Set a player's power.");
            setRequiredArgs("player", "amount", "reason");
        }

        @Override
        protected void perform(CommandContext ctx) {
            OptionalDouble amount = ArgParsers.parseDouble(ctx.arg(1));
            if (amount.isEmpty()) {
                ctx.send(INVALID_AMOUNT, ctx.argOrEmpty(1));
                return;
            }
            String reason = joinReason(ctx, 2);
            if (reason.isEmpty()) {
                ctx.send(REASON_REQUIRED);
                return;
            }
            UUID target = resolveTarget(ctx);
            if (target == null) {
                return;
            }
            CommandKit.submit(ctx, new PowerIntent.AdminPowerSet(target, amount.getAsDouble(),
                    adminActor(ctx), reason), CommandKit.adminOrigin(ctx.sender()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            if (argIndex == 0) {
                return onlineNameCompletions(ctx);
            }
            return argIndex == 1 ? Completions.matching(new String[] {"0", "5", "10"}, ctx.argOrEmpty(1)) : List.of();
        }
    }

    /** {@code /fa power add <player> <amount> <reason...>} (§3.3). */
    static final class Add extends CommandNode {

        Add() {
            super("add");
            setCommandPath("/fa power");
            setPermission("factions.cmd.admin.power.add");
            setDescription("Add power to a player.");
            setRequiredArgs("player", "amount", "reason");
        }

        @Override
        protected void perform(CommandContext ctx) {
            OptionalDouble amount = ArgParsers.parseDouble(ctx.arg(1));
            if (amount.isEmpty()) {
                ctx.send(INVALID_AMOUNT, ctx.argOrEmpty(1));
                return;
            }
            String reason = joinReason(ctx, 2);
            if (reason.isEmpty()) {
                ctx.send(REASON_REQUIRED);
                return;
            }
            UUID target = resolveTarget(ctx);
            if (target == null) {
                return;
            }
            CommandKit.submit(ctx, new PowerIntent.AdminPowerAdd(target, Math.abs(amount.getAsDouble()),
                    adminActor(ctx), reason), CommandKit.adminOrigin(ctx.sender()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            if (argIndex == 0) {
                return onlineNameCompletions(ctx);
            }
            return argIndex == 1 ? Completions.matching(new String[] {"1", "2", "5"}, ctx.argOrEmpty(1)) : List.of();
        }
    }

    /** {@code /fa power remove <player> <amount> <reason...>} (§3.4). */
    static final class Remove extends CommandNode {

        Remove() {
            super("remove");
            setCommandPath("/fa power");
            setPermission("factions.cmd.admin.power.remove");
            setDescription("Remove power from a player.");
            setRequiredArgs("player", "amount", "reason");
        }

        @Override
        protected void perform(CommandContext ctx) {
            OptionalDouble amount = ArgParsers.parseDouble(ctx.arg(1));
            if (amount.isEmpty()) {
                ctx.send(INVALID_AMOUNT, ctx.argOrEmpty(1));
                return;
            }
            String reason = joinReason(ctx, 2);
            if (reason.isEmpty()) {
                ctx.send(REASON_REQUIRED);
                return;
            }
            UUID target = resolveTarget(ctx);
            if (target == null) {
                return;
            }
            CommandKit.submit(ctx, new PowerIntent.AdminPowerRemove(target, Math.abs(amount.getAsDouble()),
                    adminActor(ctx), reason), CommandKit.adminOrigin(ctx.sender()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            if (argIndex == 0) {
                return onlineNameCompletions(ctx);
            }
            return argIndex == 1 ? Completions.matching(new String[] {"1", "2", "5"}, ctx.argOrEmpty(1)) : List.of();
        }
    }

    /** {@code /fa power reset <player> <reason...>} (§3.5). */
    static final class Reset extends CommandNode {

        Reset() {
            super("reset");
            setCommandPath("/fa power");
            setPermission("factions.cmd.admin.power.reset");
            setDescription("Reset a player's power to the maximum.");
            setRequiredArgs("player", "reason");
        }

        @Override
        protected void perform(CommandContext ctx) {
            String reason = joinReason(ctx, 1);
            if (reason.isEmpty()) {
                ctx.send(REASON_REQUIRED);
                return;
            }
            UUID target = resolveTarget(ctx);
            if (target == null) {
                return;
            }
            CommandKit.submit(ctx, new PowerIntent.AdminPowerReset(target, adminActor(ctx), reason),
                    CommandKit.adminOrigin(ctx.sender()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? onlineNameCompletions(ctx) : List.of();
        }
    }

    /** {@code /fa power freeze <player> <on|off> [reason...]} (§3.6). */
    static final class Freeze extends CommandNode {

        Freeze() {
            super("freeze");
            setCommandPath("/fa power");
            setPermission("factions.cmd.admin.power.freeze");
            setDescription("Freeze or unfreeze a player's power.");
            setRequiredArgs("player", "on|off");
        }

        @Override
        protected void perform(CommandContext ctx) {
            Boolean enabled = FactionFlags.parseValue(ctx.arg(1));
            if (enabled == null) {
                ctx.send(FREEZE_USAGE);
                return;
            }
            UUID target = resolveTarget(ctx);
            if (target == null) {
                return;
            }
            CommandKit.submit(ctx, new PowerIntent.SetPowerFrozen(target, enabled, adminActor(ctx),
                    joinReason(ctx, 2)), CommandKit.adminOrigin(ctx.sender()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            if (argIndex == 0) {
                return onlineNameCompletions(ctx);
            }
            return argIndex == 1 ? Completions.matching(new String[] {"on", "off"}, ctx.argOrEmpty(1)) : List.of();
        }
    }

    /** {@code /fa power history <player> [page]} — 10/page, UTC (§3.7). */
    static final class History extends CommandNode {

        private static final int PAGE_SIZE = 10;
        private static final MessageKey HEADER = MessageKey.of("power.history-header");
        private static final MessageKey EMPTY = MessageKey.of("power.history-empty");
        private static final MessageKey ENTRY_GAIN = MessageKey.of("power.history-entry-gain");
        private static final MessageKey ENTRY_LOSS = MessageKey.of("power.history-entry-loss");
        private static final MessageKey STORAGE_ERROR = MessageKey.of("power.history-storage-error");

        private final AdminQueries queries;

        History(AdminQueries queries) {
            super("history");
            this.queries = Objects.requireNonNull(queries, "queries");
            setCommandPath("/fa power");
            setPermission("factions.cmd.admin.power.history");
            setDescription("View a player's power history.");
            setRequiredArgs("player");
            setOptionalArgs("page");
        }

        @Override
        protected void perform(CommandContext ctx) {
            UUID target = ArgParsers.offlineId(ctx.arg(0));
            if (target == null) {
                ctx.sendReason(ReasonCode.PLAYER_NOT_FOUND, ctx.argOrEmpty(0));
                return;
            }
            int page = ArgParsers.parsePage(ctx.arg(1));
            int offset = (page - 1) * PAGE_SIZE;
            String displayName = ctx.arg(0);
            queries.powerHistoryPage(target, PAGE_SIZE, offset).whenComplete((rows, error) ->
                    CommandKit.deliver(ctx, () -> render(ctx, displayName, page, rows, error)));
        }

        private void render(CommandContext ctx, String displayName, int page,
                            List<AdminQueries.PowerRow> rows, Throwable error) {
            if (error != null) {
                ctx.send(STORAGE_ERROR);
                return;
            }
            if (rows == null || rows.isEmpty()) {
                ctx.send(EMPTY, displayName);
                return;
            }
            ctx.send(HEADER, displayName, Integer.toString(page));
            for (AdminQueries.PowerRow row : rows) {
                String time = AdminFormat.formatUtc(row.createdAtMillis());
                String after = String.format(Locale.ROOT, "%.1f", row.powerAfter());
                if (row.delta() >= 0) {
                    ctx.send(ENTRY_GAIN, time, row.reason(),
                            "+" + String.format(Locale.ROOT, "%.1f", row.delta()), after);
                } else {
                    ctx.send(ENTRY_LOSS, time, row.reason(),
                            String.format(Locale.ROOT, "%.1f", row.delta()), after);
                }
            }
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? onlineNameCompletions(ctx) : List.of();
        }
    }
}
