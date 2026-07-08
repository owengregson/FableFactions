package dev.fablemc.factions.core.boot;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.core.command.admin.AdminQueries;
import dev.fablemc.factions.core.storage.StorageBoot;
import dev.fablemc.factions.platform.sched.Scheduling;

/**
 * Bridges the paging admin read commands ({@code /fa audit}, {@code /fa power history}) to the
 * JDBC-free {@link StorageBoot} query surface (ref-commands-admin.md §2.10/§3.7, CONTRACTS §4). The
 * command body must never touch the database on the server thread, so each call runs the projection
 * query off-thread via {@link Scheduling#runAsync} and completes the returned stage there; the JDBC
 * itself stays confined to {@code core.storage} (the {@code StorageBoot} facade).
 *
 * <p><b>Owning thread(s):</b> {@link #auditPage}/{@link #powerHistoryPage} are called from a command
 * {@code perform} on the server thread and return immediately; the stage completes on the async pool.
 * <b>Mutability:</b> immutable (holds only its two collaborators).
 */
public final class StorageAdminQueries implements AdminQueries {

    private final StorageBoot storage;
    private final Scheduling scheduling;

    public StorageAdminQueries(StorageBoot storage, Scheduling scheduling) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
    }

    @Override
    public CompletionStage<List<AuditRow>> auditPage(UUID factionId, @Nullable String actionId,
                                                     int limit, int offset) {
        CompletableFuture<List<AuditRow>> result = new CompletableFuture<>();
        scheduling.runAsync(() -> {
            try {
                List<AuditRow> rows = storage.queryAudit(factionId, actionId, limit, offset).stream()
                        .map(r -> new AuditRow(r.createdAt(), r.actor(), r.action(), r.detail()))
                        .toList();
                result.complete(rows);
            } catch (RuntimeException ex) {
                result.completeExceptionally(ex);
            }
        });
        return result;
    }

    @Override
    public CompletionStage<List<PowerRow>> powerHistoryPage(UUID playerId, int limit, int offset) {
        CompletableFuture<List<PowerRow>> result = new CompletableFuture<>();
        scheduling.runAsync(() -> {
            try {
                List<PowerRow> rows = storage.queryPowerHistory(playerId, limit, offset).stream()
                        .map(r -> new PowerRow(r.createdAt(), r.reason(), r.delta(), r.powerAfter()))
                        .toList();
                result.complete(rows);
            } catch (RuntimeException ex) {
                result.completeExceptionally(ex);
            }
        });
        return result;
    }
}
