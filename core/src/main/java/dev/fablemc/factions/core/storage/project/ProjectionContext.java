package dev.fablemc.factions.core.storage.project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

import dev.fablemc.factions.core.storage.SqlDialect;

/**
 * The confined translation state the projector threads through its per-domain appliers within a
 * flush: the {@link SqlDialect}, the world-index → name resolver (AM-15), the wall clock, the
 * long-lived handle→id resolution map, the ledger-row counter, and the per-flush op buffer.
 *
 * <p>Faction references in effects are generation-tagged handles; the DB keys factions by their
 * {@link UUID}. The map is seeded at boot ({@code seed}) and by {@code FactionCreated} effects, and
 * scrubbed by {@code FactionDisbanded}. It survives across flushes; the op buffer is reset each
 * flush by {@link #begin()}.
 *
 * <p><b>Owning thread(s):</b> confined to {@code fable-storage} (created on the constructing
 * thread, then only touched by the flush loop). <b>Mutability:</b> mutable, single-thread-confined.
 */
public final class ProjectionContext {

    private static final String[] KEY_ID = {"id"};

    private final SqlDialect dialect;
    private final IntFunction<String> worldResolver;
    private final LongSupplier wallClock;
    private final Map<Integer, String> factionIdByHandle = new HashMap<>();
    private List<ProjectionOp> ops = new ArrayList<>();
    private long ledgerRowSeq;

    public ProjectionContext(SqlDialect dialect, IntFunction<String> worldResolver,
                             LongSupplier wallClock) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.worldResolver = Objects.requireNonNull(worldResolver, "worldResolver");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    }

    /** Starts a fresh flush's op buffer. */
    public void begin() {
        this.ops = new ArrayList<>();
    }

    /** The ops accumulated in the current flush. */
    public List<ProjectionOp> ops() {
        return ops;
    }

    /** Appends one op to the current flush. */
    public void add(ProjectionOp op) {
        ops.add(op);
    }

    // ── row-emission verbs (the appliers' vocabulary) ─────────────────────────────────────

    /** Buffers a raw prepared statement with its positional {@code params}. */
    public void op(String sql, Object... params) {
        ops.add(new ProjectionOp(sql, params));
    }

    /**
     * Buffers a dialect upsert into {@code table} writing {@code columns} (bound in order from
     * {@code params}), keyed on {@code keyColumns}.
     */
    public void upsert(String table, String[] columns, String[] keyColumns, Object... params) {
        ops.add(new ProjectionOp(dialect.upsert(table, columns, keyColumns), params));
    }

    /** Buffers a dialect upsert keyed on the ubiquitous single {@code id} column. */
    public void upsertById(String table, String[] columns, Object... params) {
        upsert(table, columns, KEY_ID, params);
    }

    /** Buffers a delete of every {@code table} row whose {@code column} equals {@code value}. */
    public void deleteBy(String table, String column, Object value) {
        ops.add(new ProjectionOp(dialect.deleteByColumn(table, column), new Object[] {value}));
    }

    /** Primes a handle→id mapping at boot (from the {@code factions} table). */
    public void seed(int handle, UUID factionId) {
        factionIdByHandle.put(handle, factionId.toString());
    }

    /** Drops every handle→id seed (crash-recovery reseed after a synchronous tail flush, finding #2). */
    public void clearFactions() {
        factionIdByHandle.clear();
    }

    public SqlDialect dialect() {
        return dialect;
    }

    /** The world name for a world index, or {@code null} if the index is unknown (AM-15). */
    public String world(int worldIdx) {
        return worldResolver.apply(worldIdx);
    }

    /** The current wall-clock millis for {@code created_at} columns. */
    public long now() {
        return wallClock.getAsLong();
    }

    /** The faction id for a handle, or {@code null} if unresolved (stale/never-seen). */
    public String factionId(int handle) {
        return factionIdByHandle.get(handle);
    }

    public void putFaction(int handle, String factionId) {
        factionIdByHandle.put(handle, factionId);
    }

    /** Removes and returns a handle's faction id (disband scrub). */
    public String removeFaction(int handle) {
        return factionIdByHandle.remove(handle);
    }

    /** A deterministic ledger-row id derived from the effect seq and a per-context counter. */
    public String ledgerId(long seq) {
        return new UUID(seq, ledgerRowSeq++).toString();
    }

    // ── shared row helpers ────────────────────────────────────────────────────────────────

    /** Buffers a single-column {@code UPDATE factions ... WHERE id=?} for a resolved handle. */
    public void factionUpdate(int handle, String setClause, Object value) {
        String fid = factionIdByHandle.get(handle);
        if (fid != null) {
            op("UPDATE `factions` SET " + setClause + " WHERE `id`=?", value, fid);
        }
    }

    /** Buffers a single-column upsert into {@code players} keyed by the player UUID. */
    public void playerUpsert(UUID player, String column, Object value) {
        upsertById("players", new String[] {"id", column}, player.toString(), value);
    }
}
