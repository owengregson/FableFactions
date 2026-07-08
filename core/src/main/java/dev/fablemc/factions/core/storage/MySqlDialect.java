package dev.fablemc.factions.core.storage;

/**
 * The MySQL / MariaDB backend dialect (AM-10). Upserts use {@code INSERT … ON DUPLICATE KEY
 * UPDATE} (the {@code VALUES(col)} back-reference MySQL supports natively); the connection pool
 * size comes from config (default 10). Drivers are shaded/relocated with an explicit driver
 * class name.
 *
 * <p><b>Owning thread(s):</b> stateless. <b>Mutability:</b> immutable singleton.
 */
public final class MySqlDialect implements SqlDialect {

    /** The shared stateless instance. */
    public static final MySqlDialect INSTANCE = new MySqlDialect();

    private MySqlDialect() {
    }

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public String driverClassName() {
        if (classPresent("dev.fablemc.factions.lib.mysql.cj.jdbc.Driver")) {
            return "dev.fablemc.factions.lib.mysql.cj.jdbc.Driver";
        }
        return "com.mysql.cj.jdbc.Driver";
    }

    /** Builds the MySQL URL with the reference-parity connection flags (pvp-data §2). */
    public static String url(String host, int port, String database) {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=UTC";
    }

    @Override
    public String upsert(String table, String[] columns, String[] keyColumns) {
        StringBuilder sb = new StringBuilder("INSERT INTO `").append(table).append("` (")
                .append(SqlDialect.columnList(columns)).append(") VALUES (")
                .append(SqlDialect.placeholders(columns.length)).append(") ON DUPLICATE KEY UPDATE ");
        boolean first = true;
        for (String col : columns) {
            if (isKey(col, keyColumns)) {
                continue;   // never re-assign the PK to itself
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('`').append(col).append("`=VALUES(`").append(col).append("`)");
        }
        if (first) {
            // all columns are key columns: a no-op update keeps the statement valid
            sb.append('`').append(keyColumns[0]).append("`=VALUES(`").append(keyColumns[0]).append("`)");
        }
        return sb.toString();
    }

    private static boolean isKey(String col, String[] keyColumns) {
        for (String k : keyColumns) {
            if (k.equals(col)) {
                return true;
            }
        }
        return false;
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, MySqlDialect.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
