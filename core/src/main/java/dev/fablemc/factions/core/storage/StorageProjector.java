package dev.fablemc.factions.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

import dev.fablemc.factions.core.pipeline.EffectSink;
import dev.fablemc.factions.core.storage.project.ProjectionContext;
import dev.fablemc.factions.core.storage.project.ProjectionOp;
import dev.fablemc.factions.core.storage.project.Projections;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.state.EscrowTable;

/**
 * Projects the ordered effect stream into the relational store (proposal-C §6.1 Layer 2, §6.2).
 * Effects arrive on an SPSC queue from {@link dev.fablemc.factions.core.pipeline.EffectFanout};
 * the {@code fable-storage} thread drains them, and each effect is translated into
 * field-scoped/whole-row SQL ops (keyed by absolute values so replays are idempotent) by the
 * per-domain appliers in {@code storage.project}. The ops execute in <b>one transaction per
 * flush</b> (250&nbsp;ms cadence or 4k effects), advancing the {@code ff_meta.journal_seq}
 * checkpoint in the same transaction. A per-flush ack fires after commit so the pipeline can
 * release CRITICAL-tier confirmations (AM-17).
 *
 * <p>This class is the orchestrator: queue, flush thread, transaction/checkpoint, and acks. The
 * effect→SQL translation and its confined resolution state live in
 * {@link ProjectionContext}/{@link Projections} (W25-REORG §P2b).
 *
 * <p><b>Owning thread(s):</b> {@link #accept} on the writer thread (enqueue only, non-blocking);
 * everything else on {@code fable-storage}. <b>Mutability:</b> the {@link ProjectionContext} is
 * confined to the storage thread; the queue is the only cross-thread structure.
 *
 * <p>Faction references in effects are generation-tagged handles; the DB keys factions by their
 * {@link UUID}. The projector resolves handle→UUID from a map seeded by {@code FactionCreated}
 * effects and by {@link #seedFaction} at boot (BaselineLoader). World indices resolve to names
 * via the injected resolver (AM-15). Effects whose payload lacks a field the row needs
 * (home/warp coordinates, rank UUIDs) are re-projected whole-row by Wave 4 and are no-ops here.
 */
public final class StorageProjector implements EffectSink, AutoCloseable {

    /** Fired after a flush transaction commits; {@code committedSeq} is the new checkpoint. */
    @FunctionalInterface
    public interface FlushListener {
        void onFlushed(long committedSeq);
    }

    private static final int FLUSH_EFFECT_THRESHOLD = 4_000;
    private static final long FLUSH_INTERVAL_NANOS = 250L * 1_000_000L;

    /** Bounded-backoff retry for a transient flush failure — the batch is re-appliable from the WAL. */
    private static final int MAX_FLUSH_ATTEMPTS = 6;
    private static final long BASE_BACKOFF_MILLIS = 100L;
    private static final long MAX_BACKOFF_MILLIS = 5_000L;

    /** Backpressure high-water (queued batches): the writer is slowed once the queue exceeds this. */
    private static final int BACKPRESSURE_HIGH = 8_192;
    private static final int BACKPRESSURE_LOW = 4_096;

    private final DataSource dataSource;
    private final FlushListener ack;
    private final Logger log;

    private final Queue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedBatches = new AtomicInteger();
    private final ProjectionContext ctx;

    // ff_escrows durable mirror (finding #3): the open-escrow set snapshot + what we last persisted.
    private final Set<Long> persistedEscrows = new HashSet<>();
    private volatile Supplier<EscrowTable> escrowSource;

    private volatile Consumer<Throwable> onFatal;
    private volatile boolean backpressureEngaged;

    private volatile Thread thread;
    private volatile boolean running;

    public StorageProjector(DataSource dataSource, SqlDialect dialect,
                            IntFunction<String> worldResolver, LongSupplier wallClock,
                            FlushListener ack, Logger log) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.ack = ack;
        this.log = Objects.requireNonNull(log, "log");
        this.ctx = new ProjectionContext(Objects.requireNonNull(dialect, "dialect"),
                Objects.requireNonNull(worldResolver, "worldResolver"),
                Objects.requireNonNull(wallClock, "wallClock"));
    }

    /** Primes a handle→id mapping at boot (from the {@code factions} table). */
    public void seedFaction(int handle, UUID factionId) {
        ctx.seed(handle, factionId);
    }

    /** Clears the handle→id seeds (crash-recovery reseed after a synchronous tail flush, finding #10). */
    public void clearFactionSeeds() {
        ctx.clearFactions();
    }

    /**
     * The escrow source for the {@code ff_escrows} durable mirror (finding #3): after each flush the
     * projector diffs this open-escrow set against what it last persisted, upserting new-open rows and
     * deleting settled ones. Set once at boot after the snapshot hub exists; {@code null} disables the
     * mirror (headless projector tests, and the boot tail flush before the hub is built).
     */
    public void setEscrowSource(Supplier<EscrowTable> escrowSource) {
        this.escrowSource = escrowSource;
    }

    /** Seeds the set of escrow ids already durable in {@code ff_escrows} (recovered at boot, finding #3). */
    public void seedOpenEscrows(Collection<Long> ids) {
        persistedEscrows.addAll(ids);
    }

    /** The AM-9-style hook invoked if projection fails permanently (disable the plugin loudly, finding #17). */
    public void setOnFatal(Consumer<Throwable> onFatal) {
        this.onFatal = onFatal;
    }

    /**
     * Drains + flushes any queued batches synchronously on the CALLING thread. Used at boot to apply
     * the recovered journal tail to the DB before {@code BaselineLoader} reads it (finding #2); safe
     * only while the background flush thread is NOT running.
     */
    public int flushNow() {
        return drainAndFlush();
    }

    @Override
    public void accept(List<Effect> batch, long lastSeq) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        applyBackpressure();
        queue.offer(new Pending(batch, lastSeq));
        queuedBatches.incrementAndGet();
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /**
     * When the flush thread falls badly behind (a stalled DB), slow the writer to storage's pace
     * rather than growing the queue unbounded until OOM (finding #17). Only ever engages under a real
     * storage stall; a permanent stall is escalated by the retry/{@code onFatal} path, which flips
     * {@code running} false and releases this wait.
     */
    private void applyBackpressure() {
        if (queuedBatches.get() < BACKPRESSURE_HIGH) {
            return;
        }
        if (!backpressureEngaged) {
            backpressureEngaged = true;
            log.warning("storage projection backpressure engaged: " + queuedBatches.get()
                    + " batches queued (>= " + BACKPRESSURE_HIGH + ") — slowing the writer to the "
                    + "storage flush pace");
        }
        while (running && queuedBatches.get() >= BACKPRESSURE_LOW) {
            LockSupport.parkNanos(this, 1_000_000L);
        }
        if (backpressureEngaged && queuedBatches.get() < BACKPRESSURE_LOW) {
            backpressureEngaged = false;
            log.info("storage projection backpressure released (queue drained below "
                    + BACKPRESSURE_LOW + ")");
        }
    }

    /** Starts the {@code fable-storage} flush thread. Idempotent. */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        Thread t = new Thread(this::runLoop, "fable-storage");
        t.setDaemon(true);
        this.thread = t;
        t.start();
    }

    /** Stops the flush thread after a final drain. */
    public void shutdown() {
        running = false;
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(10_000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        drainAndFlush();   // final flush of anything queued after the loop exited
    }

    /** The persisted checkpoint ({@code ff_meta.journal_seq}), or -1 if unset. */
    public long readCheckpoint() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps =
                     conn.prepareStatement("SELECT `journal_seq` FROM `ff_meta` WHERE `id`=0");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (SQLException ex) {
            throw new StorageException("failed to read checkpoint", ex);
        }
    }

    /**
     * Drains every queued batch, translates them, and flushes in one transaction. Returns the
     * number of effects projected. Package-visible so tests flush deterministically without the
     * background thread.
     */
    int drainAndFlush() {
        ctx.begin();
        long checkpoint = -1;
        boolean any = false;
        int effectCount = 0;
        Pending p;
        while ((p = queue.poll()) != null) {
            queuedBatches.decrementAndGet();
            for (int i = 0; i < p.batch().size(); i++) {
                Projections.dispatch(ctx, p.batch().get(i));
                effectCount++;
            }
            checkpoint = p.lastSeq();
            any = true;
        }
        // Mirror the current open-escrow set into ff_escrows in the SAME transaction as the checkpoint
        // (finding #3): a crash between a journaled bank debit and the Vault payout is recoverable.
        boolean escrowOps = projectEscrows();
        if (!any && !escrowOps) {
            return 0;
        }
        flush(ctx.ops(), checkpoint, any);
        if (any && ack != null) {
            ack.onFlushed(checkpoint);
        }
        return effectCount;
    }

    /**
     * Diffs the current open-escrow set against what is already durable in {@code ff_escrows},
     * buffering upserts for newly-open escrows and deletes for settled ones (finding #3). Returns
     * whether any op was buffered. A no-op when no escrow source is wired and nothing was persisted.
     */
    private boolean projectEscrows() {
        Supplier<EscrowTable> source = escrowSource;
        if (source == null) {
            return false;
        }
        EscrowTable open = source.get();
        if (open == null) {
            open = EscrowTable.empty();
        }
        if (persistedEscrows.isEmpty() && open.size() == 0) {
            return false;   // common case: no open escrows and nothing to reconcile
        }
        HashSet<Long> current = new HashSet<>();
        boolean[] buffered = {false};
        open.forEach(e -> {
            current.add(e.id());
            if (persistedEscrows.add(e.id())) {
                String factionId = ctx.factionId(e.factionHandle());
                ctx.op(ctx.dialect().upsert("ff_escrows",
                        new String[] {"id", "kind", "player_uuid", "faction_id", "amount", "status",
                                "created_at", "settled_at"},
                        new String[] {"id"}),
                        e.id(), e.kind().code(), e.player() == null ? null : e.player().toString(),
                        factionId, e.amount(), "OPEN", e.createdAt(), 0L);
                buffered[0] = true;
            }
        });
        Iterator<Long> it = persistedEscrows.iterator();
        while (it.hasNext()) {
            Long id = it.next();
            if (!current.contains(id)) {
                ctx.op("DELETE FROM `ff_escrows` WHERE `id`=?", id);
                it.remove();
                buffered[0] = true;
            }
        }
        return buffered[0];
    }

    @Override
    public void close() {
        shutdown();
    }

    // ── flush ─────────────────────────────────────────────────────────────────────────────

    private void runLoop() {
        long deadline = System.nanoTime() + FLUSH_INTERVAL_NANOS;
        try {
            while (running) {
                if (queue.isEmpty()) {
                    LockSupport.parkNanos(this, FLUSH_INTERVAL_NANOS);
                    deadline = System.nanoTime() + FLUSH_INTERVAL_NANOS;
                    continue;
                }
                // accumulate briefly, then flush at cadence or effect threshold
                if (System.nanoTime() >= deadline || queueEffectEstimate() >= FLUSH_EFFECT_THRESHOLD) {
                    drainAndFlush();
                    deadline = System.nanoTime() + FLUSH_INTERVAL_NANOS;
                } else {
                    LockSupport.parkNanos(this, 1_000_000L);
                }
            }
        } catch (Throwable fatal) {
            // A permanent projection failure escaped the bounded retry. A dead projector must never
            // look healthy while the unbounded queue OOMs (finding #17): escalate loudly and disable.
            running = false;
            log.log(Level.SEVERE, "fable-storage projector failed permanently — disabling the plugin "
                    + "(the journal replays every un-projected effect on the next boot)", fatal);
            Consumer<Throwable> handler = onFatal;
            if (handler != null) {
                handler.accept(fatal);
            }
        }
    }

    private int queueEffectEstimate() {
        return queue.size();   // coarse; a batch is one queue entry
    }

    /**
     * Flushes the buffered ops (+ optional checkpoint advance) in one transaction, retrying a
     * transient SQLException with bounded exponential backoff (finding #17): the batch is never
     * dropped — it is re-appliable from the WAL — so a connection blip self-heals rather than killing
     * projection forever. When {@code advanceCheckpoint} is false (an escrow-only reconcile flush) the
     * {@code ff_meta.journal_seq} checkpoint is left untouched. Exhausting the retries rethrows, which
     * the {@code fable-storage} loop escalates via {@code onFatal}.
     */
    private void flush(List<ProjectionOp> ops, long checkpoint, boolean advanceCheckpoint) {
        SQLException last = null;
        long backoff = BASE_BACKOFF_MILLIS;
        for (int attempt = 1; attempt <= MAX_FLUSH_ATTEMPTS; attempt++) {
            try {
                flushOnce(ops, checkpoint, advanceCheckpoint);
                if (attempt > 1) {
                    log.info("storage projection flush recovered on attempt " + attempt);
                }
                return;
            } catch (SQLException ex) {
                last = ex;
                if (attempt < MAX_FLUSH_ATTEMPTS) {
                    log.log(Level.WARNING, "storage projection flush attempt " + attempt + "/"
                            + MAX_FLUSH_ATTEMPTS + " failed (retrying in " + backoff + "ms): "
                            + ex.getMessage());
                    sleep(backoff);
                    backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
                }
            }
        }
        throw new StorageException("projection flush failed after " + MAX_FLUSH_ATTEMPTS
                + " attempts (transient or permanent) — escalating", last);
    }

    private void flushOnce(List<ProjectionOp> ops, long checkpoint, boolean advanceCheckpoint)
            throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                executeOps(conn, ops);
                if (advanceCheckpoint) {
                    updateCheckpoint(conn, checkpoint);
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(auto);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void executeOps(Connection conn, List<ProjectionOp> ops) throws SQLException {
        String currentSql = null;
        PreparedStatement ps = null;
        int batched = 0;
        try {
            for (int i = 0; i < ops.size(); i++) {
                ProjectionOp op = ops.get(i);
                if (!op.sql().equals(currentSql)) {
                    if (ps != null) {
                        ps.executeBatch();
                        ps.close();
                    }
                    ps = conn.prepareStatement(op.sql());
                    currentSql = op.sql();
                    batched = 0;
                }
                bind(ps, op.params());
                ps.addBatch();
                batched++;
            }
            if (ps != null && batched > 0) {
                ps.executeBatch();
            }
        } finally {
            if (ps != null) {
                ps.close();
            }
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private void updateCheckpoint(Connection conn, long checkpoint) throws SQLException {
        try (PreparedStatement ps =
                     conn.prepareStatement("UPDATE `ff_meta` SET `journal_seq`=? WHERE `id`=0")) {
            ps.setLong(1, checkpoint);
            ps.executeUpdate();
        }
    }

    private record Pending(List<Effect> batch, long lastSeq) {
    }
}
