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

import org.h2.jdbcx.JdbcDataSource;
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
 * The H2 (in-memory) storage contract (work order W2b §3): schema creation + migrator idempotence,
 * a projector smoke run (synthetic effect batch → rows + advanced checkpoint + CRITICAL ack), and
 * the AM-11 advisory lock refusing a second live instance. Uses a named in-memory DB with
 * {@code DB_CLOSE_DELAY=-1} so multiple connections/DataSources share one database (AM-10).
 */
final class H2StorageTest {

    private static DataSource h2(String memName) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + memName + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
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
        DataSource ds = h2("mig_" + UUID.randomUUID().toString().replace('-', '_'));
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
        DataSource ds = h2("proj_" + UUID.randomUUID().toString().replace('-', '_'));
        try (Connection c = ds.getConnection()) {
            SchemaMigrator.migrate(c);
        }

        AtomicLong ackedSeq = new AtomicLong(Long.MIN_VALUE);
        StorageProjector projector = new StorageProjector(ds, H2Dialect.INSTANCE,
                worldIdx -> worldIdx == 0 ? "world" : null, () -> 1_000L,
                committedSeq -> ackedSeq.set(committedSeq));

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
        DataSource ds1 = h2(mem);
        DataSource ds2 = h2(mem);   // a second DataSource pointed at the SAME database
        try (Connection c = ds1.getConnection()) {
            SchemaMigrator.migrate(c);
        }
        long now = 1_000_000L;
        UUID owner1 = new UUID(1, 1);
        UUID owner2 = new UUID(2, 2);

        AdvisoryLock lock1 = AdvisoryLock.tryAcquire(ds1, H2Dialect.INSTANCE, mem, owner1, 60_000L,
                () -> now);
        assertNotNull(lock1, "first instance acquires the lock");
        assertTrue(lock1.ownsLock());

        // While the first lock is live (heartbeat keeps the fence fresh), a second instance refuses.
        lock1.heartbeat();
        AdvisoryLock lock2 = AdvisoryLock.tryAcquire(ds2, H2Dialect.INSTANCE, mem, owner2, 60_000L,
                () -> now);
        assertNull(lock2, "a second live instance must be refused read-write");

        // After the owner releases, the lock is grantable again.
        lock1.close();
        AdvisoryLock lock3 = AdvisoryLock.tryAcquire(ds2, H2Dialect.INSTANCE, mem, owner2, 60_000L,
                () -> now);
        assertNotNull(lock3, "the released lock can be re-acquired");
        lock3.close();
    }

    @Test
    void blobStoreRoundTripsFramedBytes() throws SQLException {
        DataSource ds = h2("blob_" + UUID.randomUUID().toString().replace('-', '_'));
        try (Connection c = ds.getConnection()) {
            SchemaMigrator.migrate(c);
        }
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] framed = Blobs.wrap(Blobs.FORMAT_MODERN, 3120, payload);
        Blobs.store(ds, H2Dialect.INSTANCE, 77L, framed, 1_000L);

        Blobs.Blob read = Blobs.read(ds, 77L);
        assertNotNull(read);
        assertEquals(Blobs.FORMAT_MODERN, read.itemFormat());
        assertEquals(3120, read.dataVersion());
        org.junit.jupiter.api.Assertions.assertArrayEquals(payload, read.payload());
        assertNull(Blobs.read(ds, 999L), "an absent ref reads back null");
    }
}
