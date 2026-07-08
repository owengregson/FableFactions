package dev.fablemc.factions.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * MySQL projection parity, gated behind {@code FABLE_MYSQL_TEST=1} and default-skipped (work order
 * W2b §3). Supply a reachable database via {@code FABLE_MYSQL_URL} (JDBC URL),
 * {@code FABLE_MYSQL_USER}, {@code FABLE_MYSQL_PASS}. Mirrors the H2 smoke against the
 * {@code ON DUPLICATE KEY UPDATE} dialect so replay idempotence holds on MySQL too.
 */
@EnabledIfEnvironmentVariable(named = "FABLE_MYSQL_TEST", matches = "1")
final class MySqlProjectionTest {

    /** Minimal {@link DataSource} over {@link DriverManager} (no pool needed for the smoke). */
    private static final class DriverManagerDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String pass;

        DriverManagerDataSource(String url, String user, String pass) {
            this.url = url;
            this.user = user;
            this.pass = pass;
        }

        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, pass);
        }

        @Override public Connection getConnection(String u, String p) throws SQLException {
            return DriverManager.getConnection(url, u, p);
        }

        @Override public PrintWriter getLogWriter() {
            return null;
        }

        @Override public void setLogWriter(PrintWriter out) {
        }

        @Override public void setLoginTimeout(int seconds) {
        }

        @Override public int getLoginTimeout() {
            return 0;
        }

        @Override public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    private static DataSource fromEnv() {
        return new DriverManagerDataSource(System.getenv("FABLE_MYSQL_URL"),
                System.getenv("FABLE_MYSQL_USER"), System.getenv("FABLE_MYSQL_PASS"));
    }

    @Test
    void projectsAndAdvancesCheckpointOnMysql() throws SQLException {
        DataSource ds = fromEnv();
        try (Connection c = ds.getConnection()) {
            SchemaMigrator.migrate(c);
            // Clean slate for a repeatable smoke.
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM `bank_transactions`");
                st.execute("DELETE FROM `board`");
                st.execute("DELETE FROM `factions`");
                st.execute("UPDATE `ff_meta` SET `journal_seq`=-1 WHERE `id`=0");
            }
        }

        AtomicLong acked = new AtomicLong(Long.MIN_VALUE);
        StorageProjector projector = new StorageProjector(ds, MySqlDialect.INSTANCE,
                worldIdx -> worldIdx == 0 ? "world" : null, () -> 1_000L, acked::set);

        Origin origin = Origin.player(new UUID(5, 5));
        UUID factionId = new UUID(9, 9);
        int handle = FactionHandle.handle(0, FactionHandle.FIRST_NORMAL_ORDINAL);

        List<Effect> batch = new ArrayList<>();
        batch.add(new Effect.FactionCreated(1L, origin, handle, factionId, "Alpha"));
        batch.add(new Effect.BankChanged(2L, origin, handle, 50.0, 50.0, Effect.TX_DEPOSIT,
                origin.actor(), FactionHandle.WILDERNESS, "deposit"));
        projector.accept(batch, 2L);
        assertEquals(2, projector.drainAndFlush());

        assertEquals(2L, projector.readCheckpoint());
        assertEquals(2L, acked.get());
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `factions`")) {
            assertTrue(rs.next() && rs.getInt(1) == 1);
        }
    }
}
