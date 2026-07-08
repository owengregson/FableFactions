package dev.fablemc.factions.core.command.admin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import org.jetbrains.annotations.Nullable;

/**
 * The read-side query seam for the paging admin commands {@code /fa audit} and {@code /fa power
 * history} (ref-commands-admin.md §2.10, §3.7). These commands render rows that live in the storage
 * projection, which a command body must never touch on the server thread (CONTRACTS §4); instead it
 * asks this seam for a page and renders when the returned stage completes. The implementation (Wave
 * 4 wiring) runs the query on {@code fable-storage} and completes the stage there.
 *
 * <p><b>Owning thread(s):</b> {@link #auditPage}/{@link #powerHistoryPage} are called from a command
 * {@code perform} on the server thread and return immediately; the returned stage completes on the
 * storage thread. <b>Mutability:</b> the implementation is stateless per call; this interface is a
 * pure seam. The row records are immutable values.
 */
public interface AdminQueries {

    /**
     * A page of audit rows for {@code factionId}, most-recent first, filtered to {@code actionId}
     * when non-null (a {@code FactionAuditAction.id()} the caller already validated).
     */
    CompletionStage<List<AuditRow>> auditPage(UUID factionId, @Nullable String actionId,
                                              int limit, int offset);

    /** A page of power-history rows for {@code playerId}, most-recent first. */
    CompletionStage<List<PowerRow>> powerHistoryPage(UUID playerId, int limit, int offset);

    /** One audit-log row: when, who (nullable = system), the action id, and the free-text detail. */
    record AuditRow(long createdAtMillis, @Nullable UUID actor, String action, String detail) {
    }

    /** One power-history row: when, the reason code, the signed delta, and the resulting power. */
    record PowerRow(long createdAtMillis, String reason, double delta, double powerAfter) {
    }
}
