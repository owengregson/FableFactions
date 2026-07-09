package dev.fablemc.factions.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.EconomyEffect;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.vocab.BankTxType;

/**
 * The embedded HSQLDB (in-memory) storage contract (work order W2b §3): schema creation + migrator idempotence,
 * a projector smoke run (synthetic effect batch → rows + advanced checkpoint + CRITICAL ack), and
 * the AM-11 advisory lock refusing a second live instance. Uses a named in-memory DB, which
 * HSQLDB shares by name within the process, so multiple DataSources see one database (AM-10).
 */
final class HsqldbStorageTest {

    private static DataSource embedded(String memName) {
        JDBCDataSource ds = new JDBCDataSource();
        ds.setUrl(HsqldbDialect.memUrl(memName));
        ds.setUser("SA");
        ds.setPassword("");
        return ds;
    }

    private static long count(DataSource ds, String table) throws SQLException {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    @Test
    void schemaCreatesAndMigratorIsIdempotent() throws SQLException {
        DataSource ds = embedded("mig_" + UUID.randomUUID().toString().replace('-', '_'));
        try (Connection c = ds.getConnection()) {
            assertEquals(SchemaMigrator.CURRENT_VERSION, SchemaMigrator.migrate(c));
            assertEquals(SchemaMigrator.CURRENT_VERSION, SchemaMigrator.schemaVersion(c));
        }
        // Prove the migration preserved data across a second run: seed a row, migrate again.
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO `factions` (`id`,`name`,`name_folded`) VALUES ('f','A','a')");
        }
        try (Connection c = ds.getConnection()) {
            // Running migrate twice is a no-op the second time (idempotent DDL + converged version).
            assertEquals(SchemaMigrator.CURRENT_VERSION, SchemaMigrator.migrate(c));
            assertEquals(SchemaMigrator.CURRENT_VERSION, SchemaMigrator.schemaVersion(c));
        }
        assertEquals(1L, count(ds, "factions"), "the second migrate must not drop existing rows");
        // Every control + parity table exists and is queryable.
        for (String t : List.of("factions", "players", "board", "warps", "invitations", "ranks",
                "bank_transactions", "power_history", "faction_inbox", "audit_logs", "team_chests",
                "merge_requests", "ff_meta", "ff_escrows", "ff_blobs")) {
            assertTrue(count(ds, t) >= 0, t + " must exist");
        }
    }

    @Test
    void projectorSmokeWritesRowsAdvancesCheckpointAndAcks() throws SQLException {
        DataSource ds = embedded("proj_" + UUID.randomUUID().toString().replace('-', '_'));
        try (Connection c = ds.getConnection()) {
            SchemaMigrator.migrate(c);
        }

        AtomicLong ackedSeq = new AtomicLong(Long.MIN_VALUE);
        StorageProjector projector = new StorageProjector(ds, HsqldbDialect.INSTANCE,
                worldIdx -> worldIdx == 0 ? "world" : null, () -> 1_000L,
                committedSeq -> ackedSeq.set(committedSeq), java.util.logging.Logger.getAnonymousLogger());

        Origin origin = Origin.player(new UUID(7, 7));
        UUID factionId = new UUID(100, 200);
        int handle = FactionHandle.handle(0, FactionHandle.FIRST_NORMAL_ORDINAL);
        long chunk = ChunkKeys.key(3, 4);

        List<Effect> batch = new ArrayList<>();
        batch.add(new LifecycleEffect.FactionCreated(1L, origin, handle, factionId, "Alpha"));
        batch.add(new ClaimEffect.ClaimSet(2L, origin, 0, chunk, handle, FactionHandle.WILDERNESS));
        // A CRITICAL-tier bank movement: its projection commit is what releases the ack.
        batch.add(new EconomyEffect.BankChanged(3L, origin, handle, 100.0, 100.0, BankTxType.DEPOSIT,
                origin.actor(), FactionHandle.WILDERNESS, "deposit"));

        projector.accept(batch, 3L);
        assertEquals(3, projector.drainAndFlush(), "all three effects project in one flush");

        assertEquals(1L, count(ds, "factions"));
        assertEquals(1L, count(ds, "board"));
        assertEquals(1L, count(ds, "bank_transactions"));
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT `name`,`money` FROM `factions` WHERE `id`='"
                     + factionId + "'")) {
            assertTrue(rs.next());
            assertEquals("Alpha", rs.getString(1));
            assertEquals(100.0, rs.getDouble(2), 1e-9);
        }
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT `world`,`cx`,`cz`,`faction_id` FROM `board`")) {
            assertTrue(rs.next());
            assertEquals("world", rs.getString(1));
            assertEquals(3, rs.getInt(2));
            assertEquals(4, rs.getInt(3));
            assertEquals(factionId.toString(), rs.getString(4));
        }

        assertEquals(3L, projector.readCheckpoint(), "ff_meta.journal_seq advanced to the batch end");
        assertEquals(3L, ackedSeq.get(), "the CRITICAL-tier flush ack fired with the committed seq");
    }

    @Test
    void advisoryLockRefusesSecondLiveInstance() throws SQLException {
        String mem = "adv_" + UUID.randomUUID().toString().replace('-', '_');
        DataSource ds1 = embedded(mem);
        DataSource ds2 = embedded(mem);   // a second DataSource pointed at the SAME database
        try (Connection c = ds1.getConnection()) {
            SchemaMigrator.migrate(c);
        }
        long now = 1_000_000L;
        UUID owner1 = new UUID(1, 1);
        UUID owner2 = new UUID(2, 2);

        AdvisoryLock lock1 = AdvisoryLock.tryAcquire(ds1, HsqldbDialect.INSTANCE, mem, owner1, 60_000L,
                () -> now);
        assertNotNull(lock1, "first instance acquires the lock");
        assertTrue(lock1.ownsLock());

        // While the first lock is live (heartbeat keeps the fence fresh), a second instance refuses.
        lock1.heartbeat();
        AdvisoryLock lock2 = AdvisoryLock.tryAcquire(ds2, HsqldbDialect.INSTANCE, mem, owner2, 60_000L,
                () -> now);
        assertNull(lock2, "a second live instance must be refused read-write");

        // After the owner releases, the lock is grantable again.
        lock1.close();
        AdvisoryLock lock3 = AdvisoryLock.tryAcquire(ds2, HsqldbDialect.INSTANCE, mem, owner2, 60_000L,
                () -> now);
        assertNotNull(lock3, "the released lock can be re-acquired");
        lock3.close();
    }

    @Test
    void advisoryLockHeartbeatDetectsTakeoverAndFiresFenceLost() throws SQLException {
        String mem = "fence_" + UUID.randomUUID().toString().replace('-', '_');
        DataSource ds1 = embedded(mem);
        DataSource ds2 = embedded(mem);
        try (Connection c = ds1.getConnection()) {
            SchemaMigrator.migrate(c);
        }
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        java.util.concurrent.atomic.AtomicBoolean fenceLost = new java.util.concurrent.atomic.AtomicBoolean();

        // owner1 acquires a 5s fence; owner1 is told to flag if it loses the fence (finding #4).
        AdvisoryLock lock1 = AdvisoryLock.tryAcquire(ds1, HsqldbDialect.INSTANCE, mem, new UUID(1, 1),
                5_000L, now::get, () -> fenceLost.set(true));
        assertNotNull(lock1, "owner1 acquires the fence");

        // Time passes beyond owner1's expiry; owner2 legitimately takes over the stale lock.
        now.addAndGet(10_000L);
        AdvisoryLock lock2 = AdvisoryLock.tryAcquire(ds2, HsqldbDialect.INSTANCE, mem, new UUID(2, 2),
                60_000L, now::get, AdvisoryLock.IGNORE_FENCE_LOSS);
        assertNotNull(lock2, "owner2 takes over after owner1's fence expired");

        // owner1's next heartbeat discovers it no longer owns the fence → loud fence-loss signal.
        org.junit.jupiter.api.Assertions.assertThrows(StorageException.class, lock1::heartbeat,
                "a taken-over heartbeat must fail loudly, not silently keep writing");
        assertTrue(fenceLost.get(), "the fence-lost callback fired so the plugin can disable");
        lock2.close();
    }

    @Test
    void pagedQueriesBindLimitOffsetOnHsqldb() throws SQLException {
        DataSource ds = embedded("page_" + UUID.randomUUID().toString().replace('-', '_'));
        try (Connection c = ds.getConnection()) {
            SchemaMigrator.migrate(c);
        }
        String fid = UUID.randomUUID().toString();
        try (Connection c = ds.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO `audit_logs` (`id`,`faction_id`,`actor_uuid`,`action`,`detail`,`created_at`) "
                             + "VALUES (?,?,?,?,?,?)")) {
            for (int i = 0; i < 5; i++) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, fid);
                ps.setString(3, null);
                ps.setString(4, "act" + i);
                ps.setString(5, "d" + i);
                ps.setLong(6, i);
                ps.executeUpdate();
            }
        }
        // The exact paged shape StorageBoot.queryAudit/queryPowerHistory use: HSQLDB must
        // accept BOUND `LIMIT ? OFFSET ?` parameters in MySQL syntax mode.
        try (Connection c = ds.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT `created_at`,`actor_uuid`,`action`,`detail` FROM `audit_logs` "
                             + "WHERE `faction_id`=? ORDER BY `created_at` DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, fid);
            ps.setInt(2, 2);
            ps.setInt(3, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(3L, rs.getLong(1), "DESC order, offset 1 starts at created_at=3");
                assertTrue(rs.next());
                assertEquals(2L, rs.getLong(1));
                org.junit.jupiter.api.Assertions.assertFalse(rs.next(), "limit 2 caps the page");
            }
        }
    }

    @Test
    void blobStoreRoundTripsFramedBytes() throws SQLException {
        DataSource ds = embedded("blob_" + UUID.randomUUID().toString().replace('-', '_'));
        try (Connection c = ds.getConnection()) {
            SchemaMigrator.migrate(c);
        }
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] framed = Blobs.wrap(Blobs.FORMAT_MODERN, 3120, payload);
        Blobs.store(ds, HsqldbDialect.INSTANCE, 77L, framed, 1_000L);

        Blobs.Blob read = Blobs.read(ds, 77L);
        assertNotNull(read);
        assertEquals(Blobs.FORMAT_MODERN, read.itemFormat());
        assertEquals(3120, read.dataVersion());
        org.junit.jupiter.api.Assertions.assertArrayEquals(payload, read.payload());
        assertNull(Blobs.read(ds, 999L), "an absent ref reads back null");
    }
}
