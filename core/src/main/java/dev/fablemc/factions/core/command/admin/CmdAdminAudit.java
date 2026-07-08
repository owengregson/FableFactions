package dev.fablemc.factions.core.command.admin;

import java.util.List;
import java.util.Objects;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.vocab.FactionAuditAction;

/**
 * {@code /fa audit <faction> [page] [--action=<action>]} — paged audit-log view with an optional
 * action filter (ref-commands-admin.md §2.10). Console capable. Resolves the faction and validates
 * the {@code --action} token, then asks the {@link AdminQueries} read seam for the page and renders
 * it when the stage completes; a command body never touches storage on the server thread
 * (CONTRACTS §4).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread; the page renders
 * on the delivery hop. <b>Mutability:</b> a leaf node holding the injected query seam.
 */
public final class CmdAdminAudit extends CommandNode {

    private static final MessageKey HEADER = MessageKey.of("admin.audit-header");
    private static final MessageKey ENTRY = MessageKey.of("admin.audit-entry");
    private static final MessageKey EMPTY = MessageKey.of("admin.audit-empty");
    private static final MessageKey ERROR = MessageKey.of("admin.audit-error");
    private static final MessageKey UNKNOWN_ACTION = MessageKey.of("admin.audit-unknown-action");
    private static final MessageKey BAD_OPTION = MessageKey.of("general.bad-option");

    private final AdminQueries queries;

    /** Builds the audit command with the read-query seam. */
    public CmdAdminAudit(AdminQueries queries) {
        super("audit");
        this.queries = Objects.requireNonNull(queries, "queries");
        setCommandPath("/fa");
        setPermission("factions.admin");
        setDescription("View a faction's audit log.");
        setRequiredArgs("faction");
        setOptionalArgs("page", "--action=<action>");
    }

    @Override
    protected void perform(CommandContext ctx) {
        KernelSnapshot snap = ctx.snap();
        Faction target = ArgParsers.factionByName(snap, ctx.arg(0));
        if (target == null) {
            ctx.sendReason(ReasonCode.FACTION_NOT_FOUND, ctx.argOrEmpty(0));
            return;
        }
        List<String> rest = ctx.args().subList(1, ctx.argCount());
        ArgParsers.ParsedOptions parsed = ArgParsers.parseOptions(rest, "action");
        if (!parsed.ok()) {
            ctx.send(BAD_OPTION, parsed.badOption());
            return;
        }
        int pageSize = Math.max(1, snap.config().display().auditPageSize());
        int page = parsed.positionals().isEmpty() ? 1 : ArgParsers.parsePage(parsed.positionals().get(0));
        int offset = (page - 1) * pageSize;

        String actionId = null;
        String filterLabel = "";
        String actionFilter = parsed.option("action");
        if (actionFilter != null && !actionFilter.trim().isEmpty()) {
            FactionAuditAction action = FactionAuditAction.fromId(actionFilter);
            if (action == null) {
                ctx.send(UNKNOWN_ACTION, actionFilter, FactionAuditAction.validIds());
                return;
            }
            actionId = action.id();
            filterLabel = action.id();
        }

        String displayName = target.name();
        String finalFilter = filterLabel;
        queries.auditPage(target.id(), actionId, pageSize, offset).whenComplete((rows, error) ->
                CommandKit.deliver(ctx, () -> render(ctx, displayName, page, finalFilter, rows, error)));
    }

    private void render(CommandContext ctx, String displayName, int page, String filterLabel,
                        List<AdminQueries.AuditRow> rows, Throwable error) {
        if (error != null) {
            ctx.send(ERROR);
            return;
        }
        ctx.send(HEADER, displayName, Integer.toString(page), filterLabel);
        if (rows == null || rows.isEmpty()) {
            ctx.send(EMPTY);
            return;
        }
        for (AdminQueries.AuditRow row : rows) {
            ctx.send(ENTRY, AdminFormat.formatUtc(row.createdAtMillis()),
                    AdminFormat.resolveActor(row.actor()), row.action(), row.detail());
        }
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0 ? Completions.factionNames(ctx.snap(), ctx.argOrEmpty(0)) : List.of();
    }
}
