package dev.fablemc.factions.core.storage.project;

/**
 * A single field-scoped/whole-row SQL op the projector buffers within a flush: a prepared-statement
 * {@code sql} plus its positional {@code params}. Consecutive ops with identical {@code sql} are
 * batch-executed by the orchestrator in one transaction (proposal-C §6.2).
 *
 * <p><b>Owning thread(s):</b> built and executed on {@code fable-storage}. <b>Mutability:</b>
 * immutable value (the {@code params} array is not shared after construction).
 */
public record ProjectionOp(String sql, Object[] params) {
}
