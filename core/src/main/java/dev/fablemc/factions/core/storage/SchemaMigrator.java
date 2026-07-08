package dev.fablemc.factions.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Versioned, idempotent schema migration (proposal-C §6.3). Creates the base schema, ensures the
 * single {@code ff_meta} control row, applies the reference's {@code ensureColumn} column-add
 * migrations (pvp-data §5) — all guarded by {@code ADD COLUMN IF NOT EXISTS} with the reference's
 * retry-and-swallow fallback for engines lacking that clause — and records {@code schema_version}.
 *
 * <p>Running {@link #migrate} twice is a no-op the second time (every statement is idempotent and
 * {@code schema_version} converges to {@link #CURRENT_VERSION}). <b>Owning thread(s):</b> boot
 * thread only. <b>Mutability:</b> stateless.
 */
public final class SchemaMigrator {

    /** The schema version this build targets. */
    public static final int CURRENT_VERSION = 1;

    private SchemaMigrator() {
    }

    /**
     * Creates the schema, seeds the {@code ff_meta} row, applies column migrations, and advances
     * {@code schema_version} to {@link #CURRENT_VERSION}. Returns the resulting version.
     */
    public static int migrate(Connection conn) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(true);
        try {
            Schema.create(conn);
            ensureMetaRow(conn);
            applyColumnMigrations(conn);
            setSchemaVersion(conn, CURRENT_VERSION);
            return CURRENT_VERSION;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    /** The schema version recorded in {@code ff_meta}, or 0 if the row/table is absent. */
    public static int schemaVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT `schema_version` FROM `ff_meta` WHERE `id`=0")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void ensureMetaRow(Connection conn) throws SQLException {
        boolean exists;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT `id` FROM `ff_meta` WHERE `id`=0")) {
            exists = rs.next();
        }
        if (!exists) {
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO `ff_meta` (`id`, `schema_version`, `journal_seq`, "
                        + "`lock_owner`, `lock_expiry`) VALUES (0, 0, -1, NULL, 0)");
            }
        }
    }

    private static void setSchemaVersion(Connection conn, int version) throws SQLException {
        try (PreparedStatement ps =
                     conn.prepareStatement("UPDATE `ff_meta` SET `schema_version`=? WHERE `id`=0")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    private static void applyColumnMigrations(Connection conn) throws SQLException {
        // These columns are also in the CREATE TABLE, so on a fresh DB they already exist and each
        // ensureColumn is a no-op; on an upgraded DB they are added here (pvp-data §5).
        ensureColumn(conn, "players", "auto_territory_mode", "TINYINT NOT NULL DEFAULT 0");
        ensureColumn(conn, "players", "notify_invites", "TINYINT NOT NULL DEFAULT 1");
        ensureColumn(conn, "players", "notify_bank_tax", "TINYINT NOT NULL DEFAULT 1");
        ensureColumn(conn, "players", "notify_motd", "TINYINT NOT NULL DEFAULT 1");
        ensureColumn(conn, "players", "locale", "VARCHAR(16)");
        ensureColumn(conn, "players", "power_frozen", "TINYINT NOT NULL DEFAULT 0");
        ensureColumn(conn, "warps", "password", "VARCHAR(64)");
        ensureColumn(conn, "warps", "use_cost", "DOUBLE NOT NULL DEFAULT 0");
        // FableFactions additions
        ensureColumn(conn, "team_chests", "blob_ref", "BIGINT");
    }

    /**
     * Idempotently adds {@code column} to {@code table} (pvp-data §5): tries {@code ADD COLUMN IF
     * NOT EXISTS}, retries without the clause, and swallows "already/exists/duplicate" errors.
     */
    static void ensureColumn(Connection conn, String table, String column, String columnSql)
            throws SQLException {
        try (Statement st = conn.createStatement()) {
            try {
                st.execute("ALTER TABLE `" + table + "` ADD COLUMN IF NOT EXISTS `" + column + "` "
                        + columnSql);
            } catch (SQLException withClause) {
                try {
                    st.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + columnSql);
                } catch (SQLException plain) {
                    String m = plain.getMessage() == null ? "" : plain.getMessage().toLowerCase();
                    if (!(m.contains("exist") || m.contains("duplicate") || m.contains("already"))) {
                        throw plain;
                    }
                }
            }
        }
    }
}
