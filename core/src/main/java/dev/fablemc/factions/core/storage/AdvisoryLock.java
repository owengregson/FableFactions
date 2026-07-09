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

    /** A fence-loss notifier that does nothing (the historic behaviour / test default). */
    public static final Runnable IGNORE_FENCE_LOSS = () -> { };

    private final DataSource dataSource;
    private final String lockName;
    private final UUID owner;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final Connection heldMysqlConnection;   // non-null only for MySQL
    private final Runnable onFenceLost;
    private boolean released;
    private boolean fenceLostFired;

    private AdvisoryLock(DataSource dataSource, String lockName, UUID owner,
                         long ttlMillis, LongSupplier clock, Connection heldMysqlConnection,
                         Runnable onFenceLost) {
        this.dataSource = dataSource;
        this.lockName = lockName;
        this.owner = owner;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        this.heldMysqlConnection = heldMysqlConnection;
        this.onFenceLost = onFenceLost == null ? IGNORE_FENCE_LOSS : onFenceLost;
    }

    /** @see #tryAcquire(DataSource, SqlDialect, String, UUID, long, LongSupplier, Runnable) */
    public static AdvisoryLock tryAcquire(DataSource dataSource, SqlDialect dialect, String dbKey,
                                          UUID owner, long ttlMillis, LongSupplier clock)
            throws SQLException {
        return tryAcquire(dataSource, dialect, dbKey, owner, ttlMillis, clock, IGNORE_FENCE_LOSS);
    }

    /**
     * Attempts to acquire the lock for {@code dbKey}. Returns the held lock, or {@code null} if a
     * live instance already holds it (a second boot refuses read-write). {@code onFenceLost} is
     * invoked (once) by {@link #heartbeat()} if this instance later discovers it no longer owns the
     * fence — an H2 takeover after expiry, or a dropped MySQL {@code GET_LOCK} connection — so the
     * caller can disable the plugin loudly rather than keep writing behind a stolen lock (AM-11).
     */
    public static AdvisoryLock tryAcquire(DataSource dataSource, SqlDialect dialect, String dbKey,
                                          UUID owner, long ttlMillis, LongSupplier clock,
                                          Runnable onFenceLost) throws SQLException {
        String lockName = "fablefactions:" + dbKey;
        if (MySqlDialect.NAME.equals(dialect.name())) {
            return tryAcquireMysql(dataSource, lockName, owner, ttlMillis, clock, onFenceLost);
        }
        return tryAcquireH2(dataSource, lockName, owner, ttlMillis, clock, onFenceLost);
    }

    /**
     * Refreshes the fence AND verifies we still own it (AM-11 fencing, finding #4). H2: the expiry
     * bump is conditioned on us still being {@code lock_owner}; if it updates zero rows another
     * instance took over after our expiry — we fire {@code onFenceLost} and go read-only. MySQL: the
     * {@code GET_LOCK} is connection-scoped, so a dropped/invalid lock connection means the lock was
     * silently released server-side — we detect it and fire {@code onFenceLost}.
     */
    public void heartbeat() throws SQLException {
        if (released) {
            return;
        }
        if (heldMysqlConnection != null) {
            heartbeatMysql();
            return;
        }
        long now = clock.getAsLong();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE `ff_meta` SET `lock_expiry`=? WHERE `id`=0 AND `lock_owner`=?")) {
            ps.setLong(1, now + ttlMillis);
            ps.setString(2, owner.toString());
            int updated = ps.executeUpdate();
            if (updated != 1) {
                fenceLost("H2 advisory lock was taken over by another instance (our fence expired)");
            }
        }
    }

    private void heartbeatMysql() {
        boolean stillOurs = false;
        try {
            if (!heldMysqlConnection.isClosed() && heldMysqlConnection.isValid(2)) {
                try (PreparedStatement ps = heldMysqlConnection.prepareStatement(
                        "SELECT IS_USED_LOCK(?) = CONNECTION_ID()");
                     ResultSet rs = ps.executeQuery()) {
                    stillOurs = rs.next() && rs.getBoolean(1);
                }
            }
        } catch (SQLException dropped) {
            stillOurs = false;   // connection blip → the server released our lock
        }
        if (!stillOurs) {
            fenceLost("MySQL GET_LOCK connection was dropped/lost — the advisory lock is no longer held");
        }
    }

    private void fenceLost(String reason) {
        if (fenceLostFired) {
            return;
        }
        fenceLostFired = true;
        released = true;   // stop refreshing; we no longer own the fence
        try {
            onFenceLost.run();
        } catch (RuntimeException ignored) {
            // the handler is responsible for its own failures; never mask the fence-loss signal
        }
        throw new StorageException("advisory lock fence lost — " + reason + " (AM-11)");
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

    private static AdvisoryLock tryAcquireH2(DataSource dataSource, String lockName, UUID owner,
                                             long ttlMillis, LongSupplier clock, Runnable onFenceLost)
            throws SQLException {
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
                return new AdvisoryLock(dataSource, lockName, owner, ttlMillis, clock, null, onFenceLost);
            }
            return null;   // held by another live instance and not expired
        }
    }

    private static AdvisoryLock tryAcquireMysql(DataSource dataSource, String lockName, UUID owner,
                                                long ttlMillis, LongSupplier clock, Runnable onFenceLost)
            throws SQLException {
        Connection conn = dataSource.getConnection();
        boolean ok = false;
        try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, 5)")) {
            ps.setString(1, lockName);
            try (ResultSet rs = ps.executeQuery()) {
                ok = rs.next() && rs.getInt(1) == 1;
            }
            if (ok) {
                return new AdvisoryLock(dataSource, lockName, owner, ttlMillis, clock, conn, onFenceLost);
            }
            return null;
        } finally {
            if (!ok) {
                conn.close();
            }
        }
    }
}
