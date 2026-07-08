package dev.fablemc.factions.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * The single-instance advisory DB lock (AM-11). Memory is authoritative, so two live instances
 * pointed at one database would silently last-write-win the projection — a second instance must
 * refuse to boot read-write.
 *
 * <ul>
 *   <li><b>MySQL:</b> {@code SELECT GET_LOCK('fablefactions:<db>', 5)} on a held connection
 *       (connection-scoped; released on {@link #close()}).</li>
 *   <li><b>H2:</b> a heartbeat-and-fence lock row in {@code ff_meta} (owner UUID + expiry),
 *       refreshed every ~15s by {@code fable-storage}; a takeover is possible only after expiry.
 *       Short-lived connections are used so H2's pool-of-1 is never starved.</li>
 * </ul>
 *
 * <p><b>Owning thread(s):</b> acquired on the boot thread; {@link #heartbeat()} on
 * {@code fable-storage}. <b>Mutability:</b> holds the MySQL lock connection (if any) and the
 * owner identity; otherwise stateless per call.
 */
public final class AdvisoryLock implements AutoCloseable {

    private final DataSource dataSource;
    private final SqlDialect dialect;
    private final String lockName;
    private final UUID owner;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final Connection heldMysqlConnection;   // non-null only for MySQL
    private boolean released;

    private AdvisoryLock(DataSource dataSource, SqlDialect dialect, String lockName, UUID owner,
                         long ttlMillis, LongSupplier clock, Connection heldMysqlConnection) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.lockName = lockName;
        this.owner = owner;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        this.heldMysqlConnection = heldMysqlConnection;
    }

    /**
     * Attempts to acquire the lock for {@code dbKey}. Returns the held lock, or {@code null} if a
     * live instance already holds it (a second boot refuses read-write).
     */
    public static AdvisoryLock tryAcquire(DataSource dataSource, SqlDialect dialect, String dbKey,
                                          UUID owner, long ttlMillis, LongSupplier clock)
            throws SQLException {
        String lockName = "fablefactions:" + dbKey;
        if ("mysql".equals(dialect.name())) {
            return tryAcquireMysql(dataSource, dialect, lockName, owner, ttlMillis, clock);
        }
        return tryAcquireH2(dataSource, dialect, lockName, owner, ttlMillis, clock);
    }

    /** Refreshes the H2 lock's expiry; a no-op for MySQL (its lock is connection-scoped). */
    public void heartbeat() throws SQLException {
        if (released || heldMysqlConnection != null) {
            return;
        }
        long now = clock.getAsLong();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE `ff_meta` SET `lock_expiry`=? WHERE `id`=0 AND `lock_owner`=?")) {
            ps.setLong(1, now + ttlMillis);
            ps.setString(2, owner.toString());
            ps.executeUpdate();
        }
    }

    /** {@code true} if this process currently owns the lock. */
    public boolean ownsLock() {
        return !released;
    }

    @Override
    public void close() {
        if (released) {
            return;
        }
        released = true;
        try {
            if (heldMysqlConnection != null) {
                try (PreparedStatement ps =
                             heldMysqlConnection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    ps.setString(1, lockName);
                    ps.executeQuery();
                } finally {
                    heldMysqlConnection.close();
                }
            } else {
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "UPDATE `ff_meta` SET `lock_owner`=NULL, `lock_expiry`=0 "
                                     + "WHERE `id`=0 AND `lock_owner`=?")) {
                    ps.setString(1, owner.toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException ignored) {
            // best-effort release; expiry/fencing recovers a leaked lock
        }
    }

    private static AdvisoryLock tryAcquireH2(DataSource dataSource, SqlDialect dialect,
                                             String lockName, UUID owner, long ttlMillis,
                                             LongSupplier clock) throws SQLException {
        long now = clock.getAsLong();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE `ff_meta` SET `lock_owner`=?, `lock_expiry`=? "
                             + "WHERE `id`=0 AND (`lock_owner` IS NULL OR `lock_expiry` <= ? "
                             + "OR `lock_owner`=?)")) {
            ps.setString(1, owner.toString());
            ps.setLong(2, now + ttlMillis);
            ps.setLong(3, now);
            ps.setString(4, owner.toString());
            int updated = ps.executeUpdate();
            if (updated == 1) {
                return new AdvisoryLock(dataSource, dialect, lockName, owner, ttlMillis, clock, null);
            }
            return null;   // held by another live instance and not expired
        }
    }

    private static AdvisoryLock tryAcquireMysql(DataSource dataSource, SqlDialect dialect,
                                                String lockName, UUID owner, long ttlMillis,
                                                LongSupplier clock) throws SQLException {
        Connection conn = dataSource.getConnection();
        boolean ok = false;
        try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, 5)")) {
            ps.setString(1, lockName);
            try (ResultSet rs = ps.executeQuery()) {
                ok = rs.next() && rs.getInt(1) == 1;
            }
            if (ok) {
                return new AdvisoryLock(dataSource, dialect, lockName, owner, ttlMillis, clock, conn);
            }
            return null;
        } finally {
            if (!ok) {
                conn.close();
            }
        }
    }
}
