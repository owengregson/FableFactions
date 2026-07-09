package dev.fablemc.factions.core.storage;

/**
 * The narrow slice of SQL that differs between the embedded HSQLDB backend and MySQL (AM-10,
 * proposal-C §6.2). The DDL is identical across both backends (HSQLDB runs in MySQL syntax mode),
 * as is the upsert shape; the dialects carry the explicit driver class names and URL builders.
 *
 * <p><b>Owning thread(s):</b> stateless; used on the {@code fable-storage} thread and at boot.
 * <b>Mutability:</b> immutable. Identifiers are backtick-quoted, which both HSQLDB
 * ({@code sql.syntax_mys=true}) and MySQL accept.
 */
public interface SqlDialect {

    /** A short backend name ({@code "hsqldb"} / {@code "mysql"}) for logging and {@code ff_meta}. */
    String name();

    /**
     * The explicit JDBC driver class name — shaded/relocated first, canonical fallback (per
     * ref-data §6): the SPI service file is stripped from the shaded jar so the pool must be told.
     */
    String driverClassName();

    /**
     * An upsert statement for {@code table} writing all {@code columns} (bound in order), keyed on
     * {@code keyColumns}. Replays are idempotent because effects carry absolute values, so a
     * whole-row upsert is safe (proposal-C §6.2).
     */
    String upsert(String table, String[] columns, String[] keyColumns);

    /** A delete-by-key statement for {@code table} matching all {@code keyColumns} (bound in order). */
    default String deleteByKey(String table, String[] keyColumns) {
        StringBuilder sb = new StringBuilder("DELETE FROM `").append(table).append("` WHERE ");
        for (int i = 0; i < keyColumns.length; i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append('`').append(keyColumns[i]).append("`=?");
        }
        return sb.toString();
    }

    /** A delete-all-rows statement for {@code table} matching a single column value. */
    default String deleteByColumn(String table, String column) {
        return "DELETE FROM `" + table + "` WHERE `" + column + "`=?";
    }

    /** Renders a comma-separated, backtick-quoted column list. */
    static String columnList(String[] columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('`').append(columns[i]).append('`');
        }
        return sb.toString();
    }

    /** Renders {@code n} comma-separated {@code ?} placeholders. */
    static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.toString();
    }

    /**
     * {@code true} if {@code fqn} is loadable (without initializing it) — the dialects' probe for
     * the shaded/relocated driver class before falling back to the canonical name (ref-data §6).
     */
    static boolean classPresent(String fqn) {
        try {
            Class.forName(fqn, false, SqlDialect.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
