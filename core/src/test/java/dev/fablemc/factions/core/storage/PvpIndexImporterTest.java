package dev.fablemc.factions.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.RelationKind;

/**
 * Verifies {@link PvpIndexImporter} against the reference schema (string {@code "world:cx:cz"}
 * board PK, relations JSON) and both mandated sanitizations: asymmetric ALLY wishes stay wishes
 * (effective only when mutual), and fold-case name collisions get a numeric suffix.
 */
final class PvpIndexImporterTest {

    private static final Logger QUIET = quiet();

    private static Logger quiet() {
        Logger l = Logger.getAnonymousLogger();
        l.setLevel(Level.OFF);
        return l;
    }

    private static final ToIntFunction<String> WORLD = name -> "world".equals(name) ? 0 : -1;

    /** Migrate FF schema, then swap {@code board} for the reference string-PK shape. */
    private static DataSource legacyDb(String mem) throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + mem + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            SchemaMigrator.migrate(c);
            st.execute("DROP TABLE `board`");
            st.execute("CREATE TABLE `board` (`id` VARCHAR(128) NOT NULL, "
                    + "`faction_id` VARCHAR(36) NOT NULL, `access_json` TEXT, PRIMARY KEY (`id`))");
            // The reference schema has no case-folded uniqueness (that is an FF addition), which is
            // exactly why fold-case collisions exist in legacy data and must be sanitized on import.
            st.execute("DROP INDEX IF EXISTS `ux_factions_name_folded`");
        }
        return ds;
    }

    private static void insertFaction(Connection c, UUID id, String name, String relationsJson)
            throws SQLException {
        try (Statement st = c.createStatement()) {
            String rel = relationsJson == null ? "NULL" : "'" + relationsJson + "'";
            st.execute("INSERT INTO `factions` (`id`,`name`,`name_folded`,`relations_json`) VALUES ('"
                    + id + "','" + name + "','" + NameIndex.fold(name) + "'," + rel + ")");
        }
    }

    @Test
    void importsLegacyBoardAndBasicState() throws SQLException {
        DataSource ds = legacyDb("pvp_" + UUID.randomUUID().toString().replace('-', '_'));
        UUID f1 = new UUID(1, 1);
        try (Connection c = ds.getConnection()) {
            insertFaction(c, f1, "Alpha", null);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO `board` (`id`,`faction_id`) VALUES ('world:3:4','" + f1 + "')");
                st.execute("INSERT INTO `players` (`id`,`faction_id`,`power`) VALUES ('"
                        + new UUID(2, 2) + "','" + f1 + "',10.0)");
            }
        }

        PvpIndexImporter importer = new PvpIndexImporter(ds, WORLD, QUIET);
        PvpIndexImporter.Result r = importer.importAll(ConfigImage.defaults());

        assertEquals(1, r.factionCount());
        assertEquals(1, r.memberCount());
        assertEquals(1, r.claimCount());
        assertEquals(0, r.renamedCollisions());

        KernelState state = r.state();
        int handle = FactionHandle.handle(0, FactionHandle.FIRST_NORMAL_ORDINAL);
        assertEquals(handle, state.claims().ownerAt(0, ChunkKeys.key(3, 4)),
                "the reference 'world:3:4' board id parses to chunk (3,4)");
        assertNotNull(state.factions().resolve(handle));
    }

    @Test
    void asymmetricAllyStaysAWish() throws SQLException {
        DataSource ds = legacyDb("pvpaa_" + UUID.randomUUID().toString().replace('-', '_'));
        UUID a = new UUID(10, 1);
        UUID b = new UUID(10, 2);
        try (Connection c = ds.getConnection()) {
            insertFaction(c, a, "Alpha", "{\"" + b + "\":\"ALLY\"}");   // A wishes ALLY with B
            insertFaction(c, b, "Beta", null);                          // B does not reciprocate
        }

        PvpIndexImporter.Result r = new PvpIndexImporter(ds, WORLD, QUIET)
                .importAll(ConfigImage.defaults());
        KernelState state = r.state();

        int aHandle = handleOf(r, a);
        int bHandle = handleOf(r, b);
        int aOrd = FactionHandle.ordinal(aHandle);
        int bOrd = FactionHandle.ordinal(bHandle);

        Faction fa = state.factions().resolve(aHandle);
        assertEquals(RelationKind.ALLY, fa.relationDeclared(bOrd), "A's ALLY is kept as a wish");
        assertEquals(RelationKind.NEUTRAL, fa.relationEffective(bOrd),
                "an unreciprocated ALLY is NOT effective");

        Faction fb = state.factions().resolve(bHandle);
        assertEquals(RelationKind.NEUTRAL, fb.relationDeclared(aOrd), "B declared nothing");
        assertEquals(RelationKind.NEUTRAL, fb.relationEffective(aOrd));
    }

    @Test
    void foldCaseNameCollisionGetsNumericSuffix() throws SQLException {
        DataSource ds = legacyDb("pvpc_" + UUID.randomUUID().toString().replace('-', '_'));
        try (Connection c = ds.getConnection()) {
            insertFaction(c, new UUID(20, 1), "Wolves", null);
            insertFaction(c, new UUID(20, 2), "wolves", null);   // collides case-insensitively
        }

        PvpIndexImporter.Result r = new PvpIndexImporter(ds, WORLD, QUIET)
                .importAll(ConfigImage.defaults());
        assertEquals(2, r.factionCount());
        assertEquals(1, r.renamedCollisions(), "exactly one colliding faction is renamed");

        KernelState state = r.state();
        Set<String> foldedNames = new HashSet<>();
        for (int ord = FactionHandle.FIRST_NORMAL_ORDINAL;
             ord < FactionHandle.FIRST_NORMAL_ORDINAL + 2; ord++) {
            Faction f = state.factions().at(ord);
            assertNotNull(f);
            foldedNames.add(f.nameFolded());
        }
        assertEquals(Set.of("wolves", "wolves2"), foldedNames,
                "the collision resolves to unique folded names");
        // Both are registered in the case-folded NameIndex.
        assertTrue(state.factionNames().ordinalOf("wolves") >= FactionHandle.FIRST_NORMAL_ORDINAL);
        assertTrue(state.factionNames().ordinalOf("wolves2") >= FactionHandle.FIRST_NORMAL_ORDINAL);
    }

    private static int handleOf(PvpIndexImporter.Result r, UUID factionId) {
        for (var e : r.factionHandleToId().entrySet()) {
            if (e.getValue().equals(factionId)) {
                return e.getKey();
            }
        }
        throw new AssertionError("no handle for " + factionId);
    }
}
