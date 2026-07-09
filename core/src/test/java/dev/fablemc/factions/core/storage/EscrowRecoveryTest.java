package dev.fablemc.factions.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.sql.DataSource;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.storage.load.BaselineLoader;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.EscrowTable;
import dev.fablemc.factions.kernel.vocab.EscrowKind;

/**
 * Finding #3: {@code ff_escrows} is created but was never written or read — a crash between a
 * journaled bank debit and the Vault payout lost the withdrawal. This pins the two halves now wired:
 * the projector mirrors the open-escrow set into {@code ff_escrows} (upsert open, delete settled), and
 * {@code BaselineLoader} reads the still-open rows back into the boot state for AM-7 reconciliation.
 */
final class EscrowRecoveryTest {

    private static final Logger QUIET = Logger.getAnonymousLogger();

    private static DataSource embedded(String name) {
        JDBCDataSource ds = new JDBCDataSource();
        ds.setUrl(HsqldbDialect.memUrl(name));
        ds.setUser("SA");
        ds.setPassword("");
        return ds;
    }

    private static String status(DataSource ds, long id) throws SQLException {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT `status` FROM `ff_escrows` WHERE `id`=" + id)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void projectorMirrorsOpenEscrowsThenDeletesSettled() throws SQLException {
        DataSource ds = embedded("esc_" + UUID.randomUUID().toString().replace('-', '_'));
        try (Connection c = ds.getConnection()) {
            SchemaMigrator.migrate(c);
        }
        StorageProjector projector = new StorageProjector(ds, HsqldbDialect.INSTANCE,
                worldIdx -> worldIdx == 0 ? "world" : null, () -> 1_000L, seq -> { }, QUIET);

        AtomicReference<EscrowTable> live = new AtomicReference<>(EscrowTable.empty());
        projector.setEscrowSource(live::get);

        int handle = FactionHandle.handle(0, FactionHandle.FIRST_NORMAL_ORDINAL);
        live.set(EscrowTable.empty().open(new EscrowTable.Escrow(42L, EscrowKind.WITHDRAW,
                new UUID(1, 2), FactionHandle.FIRST_NORMAL_ORDINAL, handle, 100.0, 1_000L)));
        projector.drainAndFlush();   // escrow-only flush → ff_escrows row appears
        assertEquals("OPEN", status(ds, 42L), "an open escrow is mirrored into ff_escrows");

        live.set(EscrowTable.empty());   // the saga settled
        projector.drainAndFlush();
        assertNull(status(ds, 42L), "a settled escrow's ff_escrows row is deleted");
    }

    @Test
    void baselineLoadsOnlyOpenEscrows() throws SQLException {
        DataSource ds = embedded("escload_" + UUID.randomUUID().toString().replace('-', '_'));
        try (Connection c = ds.getConnection()) {
            SchemaMigrator.migrate(c);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO `ff_escrows` "
                        + "(`id`,`kind`,`player_uuid`,`faction_id`,`amount`,`status`,`created_at`,`settled_at`) "
                        + "VALUES (7, 1, '" + new UUID(3, 4) + "', NULL, 250.0, 'OPEN', 1000, 0)");
                st.execute("INSERT INTO `ff_escrows` "
                        + "(`id`,`kind`,`player_uuid`,`faction_id`,`amount`,`status`,`created_at`,`settled_at`) "
                        + "VALUES (8, 0, '" + new UUID(5, 6) + "', NULL, 10.0, 'SETTLED', 1000, 2000)");
            }
        }

        BaselineLoader loader = new BaselineLoader(ds, name -> -1, QUIET);
        BaselineLoader.Result result = loader.load(ConfigImage.defaults());
        EscrowTable escrows = result.state().escrows();

        assertEquals(1, escrows.size(), "only the OPEN row loads for reconciliation");
        EscrowTable.Escrow recovered = escrows.byId(7L);
        assertNotNull(recovered, "the open WITHDRAW escrow is recovered");
        assertEquals(EscrowKind.WITHDRAW, recovered.kind());
        assertEquals(250.0, recovered.amount(), 1e-9);
        assertNull(escrows.byId(8L), "settled rows are not recovered");
    }

    @Test
    void reconcilerSubmitsFailedSettlePerOpenEscrow() {
        int handle = FactionHandle.handle(0, FactionHandle.FIRST_NORMAL_ORDINAL);
        EscrowTable table = EscrowTable.empty()
                .open(new EscrowTable.Escrow(1L, EscrowKind.WITHDRAW, new UUID(1, 1), 2, handle, 5.0, 1))
                .open(new EscrowTable.Escrow(2L, EscrowKind.DEPOSIT, new UUID(2, 2), 2, handle, 7.0, 1));

        List<Long> settled = new ArrayList<>();
        int n = EscrowReconciler.reconcile(table, settled::add, QUIET);

        assertEquals(2, n, "one FAILED settle per recovered escrow");
        assertEquals(List.of(1L, 2L), settled);
    }
}
