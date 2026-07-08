package dev.fablemc.factions.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

import dev.fablemc.factions.core.pipeline.EffectSink;
import dev.fablemc.factions.core.storage.project.ProjectionContext;
import dev.fablemc.factions.core.storage.project.ProjectionOp;
import dev.fablemc.factions.core.storage.project.Projections;
import dev.fablemc.factions.kernel.effect.Effect;

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

    private final DataSource dataSource;
    private final FlushListener ack;

    private final Queue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final ProjectionContext ctx;

    private volatile Thread thread;
    private volatile boolean running;

    public StorageProjector(DataSource dataSource, SqlDialect dialect,
                            IntFunction<String> worldResolver, LongSupplier wallClock,
                            FlushListener ack) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.ack = ack;
        this.ctx = new ProjectionContext(Objects.requireNonNull(dialect, "dialect"),
                Objects.requireNonNull(worldResolver, "worldResolver"),
                Objects.requireNonNull(wallClock, "wallClock"));
    }

    /** Primes a handle→id mapping at boot (from the {@code factions} table). */
    public void seedFaction(int handle, UUID factionId) {
        ctx.seed(handle, factionId);
    }

    @Override
    public void accept(List<Effect> batch, long lastSeq) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        queue.offer(new Pending(batch, lastSeq));
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
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
            for (int i = 0; i < p.batch().size(); i++) {
                Projections.dispatch(ctx, p.batch().get(i));
                effectCount++;
            }
            checkpoint = p.lastSeq();
            any = true;
        }
        if (!any) {
            return 0;
        }
        flush(ctx.ops(), checkpoint);
        if (ack != null) {
            ack.onFlushed(checkpoint);
        }
        return effectCount;
    }

    @Override
    public void close() {
        shutdown();
    }

    // ── flush ─────────────────────────────────────────────────────────────────────────────

    private void runLoop() {
        long deadline = System.nanoTime() + FLUSH_INTERVAL_NANOS;
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
    }

    private int queueEffectEstimate() {
        return queue.size();   // coarse; a batch is one queue entry
    }

    private void flush(List<ProjectionOp> ops, long checkpoint) {
        try (Connection conn = dataSource.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                executeOps(conn, ops);
                updateCheckpoint(conn, checkpoint);
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (SQLException ex) {
            throw new StorageException("projection flush failed", ex);
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
