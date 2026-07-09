package dev.fablemc.factions.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.storage.load.BaselineLoader;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.NameIndex;

/**
 * Verifies {@link BaselineLoader} streams FableFactions' own schema back into a valid
 * {@link KernelState}: factions/members/claims land in the kernel structures, faction handles are
 * generation-0 and match the projector-seed map, and the loader never throws on a realistic row set.
 */
final class BaselineLoaderTest {

    private static final Logger QUIET = quiet();

    private static Logger quiet() {
        Logger l = Logger.getAnonymousLogger();
        l.setLevel(Level.OFF);
        return l;
    }

    private static DataSource embedded(String mem) {
        JDBCDataSource ds = new JDBCDataSource();
        ds.setUrl(HsqldbDialect.memUrl(mem));
        ds.setUser("SA");
        ds.setPassword("");
        return ds;
    }

    private static final ToIntFunction<String> WORLD = name -> "world".equals(name) ? 0 : -1;

    @Test
    void loadsFactionsMembersAndClaims() throws SQLException {
        DataSource ds = embedded("base_" + UUID.randomUUID().toString().replace('-', '_'));
        UUID factionId = new UUID(11, 22);
        UUID owner = new UUID(33, 44);
        UUID member = new UUID(55, 66);
        try (Connection c = ds.getConnection()) {
            SchemaMigrator.migrate(c);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO `factions` (`id`,`name`,`name_folded`,`owner_id`,`money`) "
                        + "VALUES ('" + factionId + "','Alpha','alpha','" + owner + "',250.0)");
                st.execute("INSERT INTO `players` (`id`,`faction_id`,`power`) VALUES ('" + owner
                        + "','" + factionId + "',12.0)");
                st.execute("INSERT INTO `players` (`id`,`faction_id`,`power`) VALUES ('" + member
                        + "','" + factionId + "',8.0)");
                st.execute("INSERT INTO `board` (`world`,`cx`,`cz`,`faction_id`) VALUES ('world',3,4,'"
                        + factionId + "')");
            }
        }

        BaselineLoader loader = new BaselineLoader(ds, WORLD, QUIET);
        BaselineLoader.Result result = loader.load(ConfigImage.defaults());

        assertEquals(1, result.factionCount());
        assertEquals(2, result.memberCount());
        assertEquals(1, result.claimCount());

        KernelState state = result.state();
        int ordinal = FactionHandle.FIRST_NORMAL_ORDINAL;
        int handle = FactionHandle.handle(0, ordinal);
        assertEquals(ordinal, state.factionNames().ordinalOf(NameIndex.fold("Alpha")));

        Faction f = state.factions().resolve(handle);
        assertNotNull(f, "faction resolvable by its generation-0 handle");
        assertEquals("Alpha", f.name());
        assertEquals(owner, f.ownerId());
        assertEquals(250.0, f.bank(), 1e-9);
        assertEquals(1, f.landCount());

        // Claim owner slot stores the faction handle (AM-6).
        assertEquals(handle, state.claims().ownerAt(0, ChunkKeys.key(3, 4)));

        // Members are mapped and attached to the faction.
        int ownerOrd = state.members().get(owner);
        assertTrue(ownerOrd >= 0);
        assertEquals(handle, state.ledger().factionHandle(ownerOrd));

        // The projector seed map round-trips the handle back to the DB id.
        assertEquals(factionId, result.factionHandleToId().get(handle));
    }
}
