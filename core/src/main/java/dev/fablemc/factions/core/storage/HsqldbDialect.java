package dev.fablemc.factions.core.storage;

/**
 * The embedded HSQLDB backend dialect. HSQLDB's MySQL syntax mode ({@code sql.syntax_mys=true})
 * accepts the same backtick-quoted DDL and the same {@code INSERT … ON DUPLICATE KEY UPDATE}
 * upsert shape as MySQL (including the {@code VALUES(col)} back-reference), so {@link #upsert}
 * delegates to {@link MySqlDialect}. Backed by the {@code jdk8} artifact — pure Java-8 bytecode
 * in the CURRENT upstream release, no native libraries — so the shaded jar needs no downgrade
 * pass for it and runs identically from Java 8 up. Pool size is 1 (single-writer file DB).
 *
 * <p><b>Owning thread(s):</b> stateless. <b>Mutability:</b> immutable singleton.
 */
public final class HsqldbDialect implements SqlDialect {

    /** The pinned {@link #name()} value ({@code ff_meta} / logging vocabulary). */
    public static final String NAME = "hsqldb";

    /** The shared stateless instance. */
    public static final HsqldbDialect INSTANCE = new HsqldbDialect();

    private HsqldbDialect() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String driverClassName() {
        // shaded/relocated first, canonical fallback (ref-data §6)
        if (SqlDialect.classPresent("dev.fablemc.factions.lib.hsqldb.jdbc.JDBCDriver")) {
            return "dev.fablemc.factions.lib.hsqldb.jdbc.JDBCDriver";
        }
        return "org.hsqldb.jdbc.JDBCDriver";
    }

    /** Builds the file-DB URL: {@code jdbc:hsqldb:file:<path>;sql.syntax_mys=true}. */
    public static String fileUrl(String absolutePath) {
        return "jdbc:hsqldb:file:" + absolutePath + ";sql.syntax_mys=true";
    }

    /** Builds a named in-memory URL (tests): shared by name within the process until SHUTDOWN. */
    public static String memUrl(String name) {
        return "jdbc:hsqldb:mem:" + name + ";sql.syntax_mys=true";
    }

    @Override
    public String upsert(String table, String[] columns, String[] keyColumns) {
        // MYS mode implements the MySQL upsert natively — one shape for both backends.
        return MySqlDialect.INSTANCE.upsert(table, columns, keyColumns);
    }
}
