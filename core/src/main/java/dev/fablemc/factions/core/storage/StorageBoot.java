package dev.fablemc.factions.core.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import dev.fablemc.factions.core.storage.load.BaselineLoader;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.config.StorageConfigView;
import org.jetbrains.annotations.Nullable;

/**
 * The single JDBC boot facade (CONTRACTS §7 storage-standalone rule, AM-2/AM-10/AM-11). It opens
 * the connection pool, migrates the schema, acquires the AM-11 advisory lock, and builds the
 * {@link StorageProjector} and {@link BaselineLoader} — hiding every {@code java.sql}/{@code javax.sql}
 * type behind a JDBC-free surface so {@code core.boot} (which the ArchUnit gate forbids from touching
 * JDBC) can orchestrate the boot ordering without ever naming a SQL type.
 *
 * <p>This class carries <b>no</b> Bukkit dependency either (the write/journal/storage no-Bukkit gate):
 * it takes a plain {@link File} data folder, primitive/functional config, and a {@link Logger}.
 *
 * <p><b>Owning thread(s):</b> {@link #open} and {@link #loadBaseline} on the boot thread; the
 * per-flush work is confined to the projector's {@code fable-storage} thread; {@link #heartbeat}
 * runs on the caller's async cadence. <b>Mutability:</b> holds the pool, the dialect, the held
 * advisory lock and the two long-lived collaborators; otherwise stateless per call.
 */
public final class StorageBoot implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final SqlDialect dialect;
    private final String backendLabel;
    private final AdvisoryLock lock;
    private final StorageProjector projector;
    private final BaselineLoader loader;

    private StorageBoot(HikariDataSource dataSource, SqlDialect dialect, String backendLabel,
                        AdvisoryLock lock, StorageProjector projector, BaselineLoader loader) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.backendLabel = backendLabel;
        this.lock = lock;
        this.projector = projector;
        this.loader = loader;
    }

    /** One audit-log row (JDBC-free mirror of the {@code audit_logs} projection). */
    public record AuditRowData(long createdAt, @Nullable UUID actor, String action, String detail) {
    }

    /** One power-history row (JDBC-free mirror of the {@code power_history} projection). */
    public record PowerRowData(long createdAt, String reason, double delta, double powerAfter) {
    }

    /**
     * Opens the pool ({@code database.yml} view), migrates the schema, and acquires the AM-11
     * advisory lock. A second live instance is <b>refused</b> read-write: this throws a
     * {@link StorageException} so the plugin disables loudly (proposal-C §6.3 step 3, B10). Builds —
     * but does not start — the {@link StorageProjector}; {@link StorageProjector#start()} is the
     * caller's step after the baseline load and journal replay.
     *
     * @param view          the parsed {@code database.yml} storage view (kernel config)
     * @param mysqlPassword the MySQL wallet password (absent from the kernel view for hygiene; empty for H2)
     * @param dataFolder    the plugin data folder (H2 file DB is resolved under it)
     * @param owner         this instance's advisory-lock owner UUID
     * @param lockTtlMillis the H2 lock fence TTL (heartbeat-refreshed)
     * @param clock         the wall clock (lock fence + projection {@code created_at})
     * @param worldIndex    world name → dense worldIdx (AM-15), used by the baseline loader
     * @param worldResolver worldIdx → world name (AM-15), used by the projector
     * @param log           the boot logger (baseline progress)
     * @param ack           the per-flush checkpoint ack (AM-17 CRITICAL-tier release), may be a no-op
     * @param onFenceLost   fired if the advisory-lock heartbeat later detects a takeover / dropped
     *                      lock connection (AM-11 fencing, finding #4) — disable the plugin loudly
     */
    public static StorageBoot open(StorageConfigView view, String mysqlPassword, File dataFolder,
                                   UUID owner, long lockTtlMillis, LongSupplier clock,
                                   ToIntFunction<String> worldIndex, IntFunction<String> worldResolver,
                                   Logger log, StorageProjector.FlushListener ack, Runnable onFenceLost) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(dataFolder, "dataFolder");
        boolean mysql = MySqlDialect.NAME.equalsIgnoreCase(view.type());
        SqlDialect dialect = mysql ? MySqlDialect.INSTANCE : H2Dialect.INSTANCE;
        String label = mysql ? "MySQL" : "H2";
        String dbKey = mysql ? view.mysqlDatabase() : view.h2File();

        HikariDataSource ds = buildPool(view, dialect, mysqlPassword, dataFolder, mysql);
        AdvisoryLock lock = null;
        try {
            try (Connection conn = ds.getConnection()) {
                SchemaMigrator.migrate(conn);
            }
            lock = AdvisoryLock.tryAcquire(ds, dialect, dbKey, owner, lockTtlMillis, clock, onFenceLost);
            if (lock == null) {
                throw new StorageException("another live instance already holds the FableFactions "
                        + "advisory lock for '" + dbKey + "' — refusing to boot read-write (AM-11)");
            }
            StorageProjector projector = new StorageProjector(ds, dialect, worldResolver, clock, ack, log);
            BaselineLoader loader = new BaselineLoader(ds, worldIndex, log);
            return new StorageBoot(ds, dialect, label, lock, projector, loader);
        } catch (SQLException ex) {
            releaseLock(lock);
            ds.close();
            throw new StorageException("storage boot failed", ex);
        } catch (RuntimeException ex) {
            releaseLock(lock);   // finding #17: a post-lock failure must release the lock, not leak it
            ds.close();
            throw ex;
        }
    }

    /** Best-effort release of a partially-acquired lock during a failed {@link #open}. */
    private static void releaseLock(AdvisoryLock lock) {
        if (lock != null) {
            try {
                lock.close();
            } catch (RuntimeException ignored) {
                // pool close below drops the connection; H2 fence expiry recovers a leaked row
            }
        }
    }

    /** The projector to start after the baseline load + journal replay (proposal-C §6.3). */
    public StorageProjector projector() {
        return projector;
    }

    /** The persisted checkpoint ({@code ff_meta.journal_seq}) or -1 if unset. */
    public long checkpoint() {
        return projector.readCheckpoint();
    }

    /** Streams the whole relational projection into an initial {@link BaselineLoader.Result}. */
    public BaselineLoader.Result loadBaseline(ConfigImage config) {
        try {
            return loader.load(config);
        } catch (SQLException ex) {
            throw new StorageException("baseline load failed", ex);
        }
    }

    /** {@code "H2"} / {@code "MySQL"} for the bStats database-backend chart (no DB scan). */
    public String backendLabel() {
        return backendLabel;
    }

    /** Loads the framed chest blob under {@code ref}, or {@code null} if absent (chest open). */
    public byte @Nullable [] loadBlob(long ref) {
        try {
            return Blobs.load(dataSource, ref);
        } catch (SQLException ex) {
            throw new StorageException("chest blob load failed for ref " + ref, ex);
        }
    }

    /** Persists the framed chest blob under {@code ref} (chest commit). */
    public void storeBlob(long ref, byte[] framed, long createdAt) {
        try {
            Blobs.store(dataSource, dialect, ref, framed, createdAt);
        } catch (SQLException ex) {
            throw new StorageException("chest blob store failed for ref " + ref, ex);
        }
    }

    /** A page of audit rows (most-recent first), optionally filtered to {@code actionId}. */
    public List<AuditRowData> queryAudit(UUID factionId, @Nullable String actionId, int limit, int offset) {
        String sql = "SELECT `created_at`,`actor_uuid`,`action`,`detail` FROM `audit_logs` "
                + "WHERE `faction_id`=?" + (actionId != null ? " AND `action`=?" : "")
                + " ORDER BY `created_at` DESC LIMIT ? OFFSET ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, factionId.toString());
            if (actionId != null) {
                ps.setString(i++, actionId);
            }
            ps.setInt(i++, Math.max(0, limit));
            ps.setInt(i, Math.max(0, offset));
            List<AuditRowData> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String actor = rs.getString(2);
                    rows.add(new AuditRowData(rs.getLong(1), actor == null ? null : UUID.fromString(actor),
                            rs.getString(3), rs.getString(4)));
                }
            }
            return rows;
        } catch (SQLException ex) {
            throw new StorageException("audit query failed", ex);
        }
    }

    /** A page of power-history rows (most-recent first) for {@code playerId}. */
    public List<PowerRowData> queryPowerHistory(UUID playerId, int limit, int offset) {
        String sql = "SELECT `created_at`,`reason`,`delta`,`power_after` FROM `power_history` "
                + "WHERE `player_uuid`=? ORDER BY `created_at` DESC LIMIT ? OFFSET ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setInt(2, Math.max(0, limit));
            ps.setInt(3, Math.max(0, offset));
            List<PowerRowData> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new PowerRowData(rs.getLong(1), rs.getString(2), rs.getDouble(3),
                            rs.getDouble(4)));
                }
            }
            return rows;
        } catch (SQLException ex) {
            throw new StorageException("power-history query failed", ex);
        }
    }

    /** Refreshes the H2 advisory-lock fence (a no-op for MySQL); driven on an async cadence. */
    public void heartbeat() {
        try {
            lock.heartbeat();
        } catch (SQLException ex) {
            throw new StorageException("advisory-lock heartbeat failed", ex);
        }
    }

    @Override
    public void close() {
        try {
            lock.close();
        } finally {
            dataSource.close();
        }
    }

    private static HikariDataSource buildPool(StorageConfigView view, SqlDialect dialect,
                                              String mysqlPassword, File dataFolder, boolean mysql) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("fable-storage-pool");
        cfg.setDriverClassName(dialect.driverClassName());
        if (mysql) {
            cfg.setJdbcUrl(MySqlDialect.url(view.mysqlHost(), view.mysqlPort(), view.mysqlDatabase()));
            cfg.setUsername(view.mysqlUsername());
            cfg.setPassword(mysqlPassword == null ? "" : mysqlPassword);
            cfg.setMaximumPoolSize(Math.max(1, view.mysqlPoolSize()));
        } else {
            cfg.setJdbcUrl(h2Url(view.h2File(), dataFolder));
            cfg.setMaximumPoolSize(1);   // single-writer file/mem DB (AM-10)
        }
        return new HikariDataSource(cfg);
    }

    /** Builds the H2 JDBC URL: a {@code mem:} handle passes through; otherwise a file DB under the data folder. */
    private static String h2Url(String h2File, File dataFolder) {
        if (h2File.startsWith("mem:")) {
            return "jdbc:h2:" + h2File + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        }
        File file = new File(dataFolder, h2File);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        return H2Dialect.fileUrl(file.getAbsolutePath());
    }
}
