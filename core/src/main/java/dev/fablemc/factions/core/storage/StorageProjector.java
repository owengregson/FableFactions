package dev.fablemc.factions.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

import dev.fablemc.factions.core.pipeline.EffectSink;
import dev.fablemc.factions.kernel.audit.FactionAuditAction;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.state.NameIndex;

/**
 * Projects the ordered effect stream into the relational store (proposal-C §6.1 Layer 2, §6.2).
 * Effects arrive on an SPSC queue from {@link dev.fablemc.factions.core.pipeline.EffectFanout};
 * the {@code fable-storage} thread drains them, translates each into field-scoped/whole-row SQL
 * ops keyed by absolute values (so replays are idempotent), and executes them in <b>one
 * transaction per flush</b> (250&nbsp;ms cadence or 4k effects), advancing the
 * {@code ff_meta.journal_seq} checkpoint in the same transaction. A per-flush ack fires after
 * commit so the pipeline can release CRITICAL-tier confirmations (AM-17).
 *
 * <p><b>Owning thread(s):</b> {@link #accept} on the writer thread (enqueue only, non-blocking);
 * everything else on {@code fable-storage}. <b>Mutability:</b> the handle→id resolution map is
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

    private final javax.sql.DataSource dataSource;
    private final SqlDialect dialect;
    private final IntFunction<String> worldResolver;
    private final LongSupplier wallClock;
    private final FlushListener ack;

    private final Queue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final Map<Integer, String> factionIdByHandle = new HashMap<>();
    private long ledgerRowSeq;

    private volatile Thread thread;
    private volatile boolean running;

    public StorageProjector(javax.sql.DataSource dataSource, SqlDialect dialect,
                            IntFunction<String> worldResolver, LongSupplier wallClock,
                            FlushListener ack) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.worldResolver = Objects.requireNonNull(worldResolver, "worldResolver");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.ack = ack;
    }

    /** Primes a handle→id mapping at boot (from the {@code factions} table). */
    public void seedFaction(int handle, UUID factionId) {
        factionIdByHandle.put(handle, factionId.toString());
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
        List<Op> ops = new ArrayList<>();
        long checkpoint = -1;
        boolean any = false;
        int effectCount = 0;
        Pending p;
        while ((p = queue.poll()) != null) {
            for (int i = 0; i < p.batch.size(); i++) {
                translate(p.batch.get(i), ops);
                effectCount++;
            }
            checkpoint = p.lastSeq;
            any = true;
        }
        if (!any) {
            return 0;
        }
        flush(ops, checkpoint);
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

    private void flush(List<Op> ops, long checkpoint) {
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

    private void executeOps(Connection conn, List<Op> ops) throws SQLException {
        String currentSql = null;
        PreparedStatement ps = null;
        int batched = 0;
        try {
            for (int i = 0; i < ops.size(); i++) {
                Op op = ops.get(i);
                if (!op.sql.equals(currentSql)) {
                    if (ps != null) {
                        ps.executeBatch();
                        ps.close();
                    }
                    ps = conn.prepareStatement(op.sql);
                    currentSql = op.sql;
                    batched = 0;
                }
                bind(ps, op.params);
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

    // ── translation ───────────────────────────────────────────────────────────────────────

    private void translate(Effect e, List<Op> ops) {
        if (e instanceof Effect.ClaimSet x) {
            String world = worldResolver.apply(x.worldIdx());
            String fid = factionIdByHandle.get(x.faction());
            if (world != null && fid != null) {
                ops.add(new Op(dialect.upsert("board",
                        new String[] {"world", "cx", "cz", "faction_id"},
                        new String[] {"world", "cx", "cz"}),
                        new Object[] {world, ChunkKeys.x(x.key()), ChunkKeys.z(x.key()), fid}));
            }
        } else if (e instanceof Effect.ClaimRemoved x) {
            String world = worldResolver.apply(x.worldIdx());
            if (world != null) {
                ops.add(new Op(dialect.deleteByKey("board", new String[] {"world", "cx", "cz"}),
                        new Object[] {world, ChunkKeys.x(x.key()), ChunkKeys.z(x.key())}));
            }
        } else if (e instanceof Effect.FactionCreated x) {
            factionIdByHandle.put(x.faction(), x.id().toString());
            ops.add(new Op(dialect.upsert("factions",
                    new String[] {"id", "name", "name_folded"}, new String[] {"id"}),
                    new Object[] {x.id().toString(), x.name(), NameIndex.fold(x.name())}));
        } else if (e instanceof Effect.FactionRenamed x) {
            String fid = factionIdByHandle.get(x.faction());
            if (fid != null) {
                ops.add(new Op("UPDATE `factions` SET `name`=?, `name_folded`=? WHERE `id`=?",
                        new Object[] {x.newName(), NameIndex.fold(x.newName()), fid}));
            }
        } else if (e instanceof Effect.DescriptionChanged x) {
            factionUpdate(ops, x.faction(), "`description`=?", x.description());
        } else if (e instanceof Effect.MotdChanged x) {
            factionUpdate(ops, x.faction(), "`motd`=?", x.motd());
        } else if (e instanceof Effect.OwnershipTransferred x) {
            factionUpdate(ops, x.faction(), "`owner_id`=?",
                    x.newOwner() == null ? null : x.newOwner().toString());
        } else if (e instanceof Effect.RaidableChanged x) {
            factionUpdate(ops, x.faction(), "`is_raidable`=?", x.nowRaidable() ? 1 : 0);
        } else if (e instanceof Effect.ShieldChanged x) {
            String fid = factionIdByHandle.get(x.faction());
            if (fid != null) {
                ops.add(new Op("UPDATE `factions` SET `shield_start_hour`=?, "
                        + "`shield_duration_hours`=? WHERE `id`=?",
                        new Object[] {x.startHour(), x.durationHours(), fid}));
            }
        } else if (e instanceof Effect.BankChanged x) {
            String fid = factionIdByHandle.get(x.faction());
            if (fid != null) {
                factionUpdate(ops, x.faction(), "`money`=?", x.balance());
                ops.add(bankTxInsert(e.seq(), fid, txLabel(x.txType()), x.delta(),
                        x.actor() == null ? null : x.actor().toString(),
                        factionIdByHandle.get(x.counterparty()), x.note()));
            }
        } else if (e instanceof Effect.TaxCharged x) {
            String fid = factionIdByHandle.get(x.faction());
            if (fid != null) {
                factionUpdate(ops, x.faction(), "`money`=?", x.balance());
                ops.add(bankTxInsert(e.seq(), fid, "TAX", -x.amount(), null, null, null));
            }
        } else if (e instanceof Effect.FactionDisbanded x) {
            String fid = factionIdByHandle.remove(x.faction());
            if (fid != null) {
                disbandCascade(ops, fid);
            }
        } else if (e instanceof Effect.MemberJoined x) {
            String fid = factionIdByHandle.get(x.faction());
            if (fid != null) {
                ops.add(new Op(dialect.upsert("players",
                        new String[] {"id", "faction_id"}, new String[] {"id"}),
                        new Object[] {x.player().toString(), fid}));
            }
        } else if (e instanceof Effect.MemberLeft x) {
            ops.add(new Op("UPDATE `players` SET `faction_id`=NULL, `rank_id`=NULL WHERE `id`=?",
                    new Object[] {x.player().toString()}));
        } else if (e instanceof Effect.PowerChanged x) {
            ops.add(new Op(dialect.upsert("players",
                    new String[] {"id", "power"}, new String[] {"id"}),
                    new Object[] {x.player().toString(), x.after()}));
            ops.add(powerHistoryInsert(e.seq(), x.player().toString(), x.after() - x.before(),
                    x.reasonCode(), x.after()));
        } else if (e instanceof Effect.PowerFrozenChanged x) {
            playerUpsert(ops, x.player(), "power_frozen", x.frozen() ? 1 : 0);
        } else if (e instanceof Effect.DeathStreakAdvanced x) {
            ops.add(new Op(dialect.upsert("players",
                    new String[] {"id", "death_streak", "last_death_at"}, new String[] {"id"}),
                    new Object[] {x.player().toString(), x.streak(), wallClock.getAsLong()}));
        } else if (e instanceof Effect.LocaleChanged x) {
            ops.add(new Op(dialect.upsert("players",
                    new String[] {"id", "locale"}, new String[] {"id"}),
                    new Object[] {x.player().toString(), localeName(x.localeIdx())}));
        } else if (e instanceof Effect.AutoModeChanged x) {
            playerUpsert(ops, x.player(), "auto_territory_mode", x.mode());
        } else if (e instanceof Effect.OverrideChanged x) {
            playerUpsert(ops, x.player(), "overriding", x.on() ? 1 : 0);
        } else if (e instanceof Effect.SessionStarted x) {
            playerUpsert(ops, x.player(), "last_activity", x.lastActivity());
        } else if (e instanceof Effect.SessionEnded x) {
            playerUpsert(ops, x.player(), "last_activity", x.lastActivity());
        } else if (e instanceof Effect.AuditRecorded x) {
            String fid = factionIdByHandle.get(x.faction());
            if (fid != null) {
                ops.add(auditInsert(e.seq(), fid, x.actor() == null ? null : x.actor().toString(),
                        x.action() == null ? "unknown" : x.action().id(), x.detail()));
            }
        } else if (e instanceof Effect.InboxQueued x) {
            String msg = x.key() == null ? "" : x.key().key();
            ops.add(inboxInsert(e.seq(), x.player().toString(), msg));
        } else if (e instanceof Effect.InboxDelivered x) {
            ops.add(new Op(dialect.deleteByColumn("faction_inbox", "player_id"),
                    new Object[] {x.player().toString()}));
        } else if (e instanceof Effect.MergeRequested x) {
            String s = factionIdByHandle.get(x.sender());
            String t = factionIdByHandle.get(x.target());
            if (s != null && t != null) {
                ops.add(mergeRequestInsert(e.seq(), s, t));
            }
        } else if (e instanceof Effect.MergeCompleted x) {
            String s = factionIdByHandle.get(x.sender());
            if (s != null) {
                ops.add(new Op(dialect.deleteByColumn("merge_requests", "sender_faction_id"),
                        new Object[] {s}));
            }
        } else if (e instanceof Effect.ChestContentsChanged x) {
            String fid = factionIdByHandle.get(x.faction());
            if (fid != null) {
                ops.add(new Op("UPDATE `team_chests` SET `blob_ref`=? WHERE `faction_id`=? "
                        + "AND `name`=?", new Object[] {x.blobRef(), fid, x.name()}));
            }
        }
        // All other effects (feedback, external requests, zones, invites, roles, homes, warps,
        // config) are not projected here in W2b — see class javadoc.
    }

    private void factionUpdate(List<Op> ops, int handle, String setClause, Object value) {
        String fid = factionIdByHandle.get(handle);
        if (fid != null) {
            ops.add(new Op("UPDATE `factions` SET " + setClause + " WHERE `id`=?",
                    new Object[] {value, fid}));
        }
    }

    private void playerUpsert(List<Op> ops, UUID player, String column, Object value) {
        ops.add(new Op(dialect.upsert("players",
                new String[] {"id", column}, new String[] {"id"}),
                new Object[] {player.toString(), value}));
    }

    private void disbandCascade(List<Op> ops, String fid) {
        ops.add(new Op(dialect.deleteByColumn("board", "faction_id"), new Object[] {fid}));
        ops.add(new Op(dialect.deleteByColumn("warps", "faction_id"), new Object[] {fid}));
        ops.add(new Op(dialect.deleteByColumn("ranks", "faction_id"), new Object[] {fid}));
        ops.add(new Op(dialect.deleteByColumn("team_chests", "faction_id"), new Object[] {fid}));
        ops.add(new Op(dialect.deleteByColumn("invitations", "faction_id"), new Object[] {fid}));
        ops.add(new Op(dialect.deleteByColumn("merge_requests", "sender_faction_id"),
                new Object[] {fid}));
        ops.add(new Op("UPDATE `players` SET `faction_id`=NULL, `rank_id`=NULL WHERE `faction_id`=?",
                new Object[] {fid}));
        ops.add(new Op(dialect.deleteByColumn("factions", "id"), new Object[] {fid}));
    }

    private Op bankTxInsert(long seq, String factionId, String type, double amount, String actor,
                            String counterparty, String note) {
        return new Op(dialect.upsert("bank_transactions", new String[] {"id", "faction_id",
                "actor_uuid", "type", "amount", "counterparty_faction_id", "created_at", "note"},
                new String[] {"id"}),
                new Object[] {ledgerId(seq), factionId, actor, type, amount, counterparty,
                        wallClock.getAsLong(), note});
    }

    private Op powerHistoryInsert(long seq, String player, double delta, String reason,
                                  double after) {
        return new Op(dialect.upsert("power_history", new String[] {"id", "player_uuid", "delta",
                "reason", "power_after", "created_at"}, new String[] {"id"}),
                new Object[] {ledgerId(seq), player, delta, reason == null ? "" : reason, after,
                        wallClock.getAsLong()});
    }

    private Op auditInsert(long seq, String factionId, String actor, String action, String detail) {
        return new Op(dialect.upsert("audit_logs", new String[] {"id", "faction_id", "actor_uuid",
                "action", "detail", "created_at"}, new String[] {"id"}),
                new Object[] {ledgerId(seq), factionId, actor, action, detail, wallClock.getAsLong()});
    }

    private Op inboxInsert(long seq, String player, String message) {
        return new Op(dialect.upsert("faction_inbox", new String[] {"id", "player_id", "message",
                "created_at"}, new String[] {"id"}),
                new Object[] {ledgerId(seq), player, message, wallClock.getAsLong()});
    }

    private Op mergeRequestInsert(long seq, String sender, String target) {
        return new Op(dialect.upsert("merge_requests", new String[] {"id", "sender_faction_id",
                "target_faction_id", "actor_id", "created_at"}, new String[] {"id"}),
                new Object[] {ledgerId(seq), sender, target, sender, wallClock.getAsLong()});
    }

    private String ledgerId(long seq) {
        return new UUID(seq, ledgerRowSeq++).toString();
    }

    private static String txLabel(int txType) {
        return switch (txType) {
            case 1 -> "WITHDRAW";
            case 2 -> "TRANSFER";
            case 3 -> "TAX";
            default -> "DEPOSIT";
        };
    }

    private static String localeName(int localeIdx) {
        return localeIdx < 0 ? null : Integer.toString(localeIdx);
    }

    private record Op(String sql, Object[] params) {
    }

    private record Pending(List<Effect> batch, long lastSeq) {
    }
}
