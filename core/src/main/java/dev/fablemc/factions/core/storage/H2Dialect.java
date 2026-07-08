package dev.fablemc.factions.core.storage;

/**
 * The H2 backend dialect (AM-10). Upserts rewrite to H2's {@code MERGE INTO … KEY(…)} form,
 * which — unlike {@code ON DUPLICATE KEY UPDATE} — H2 1.4.200 implements natively. The JDBC URL
 * uses {@code MODE=MySQL;DB_CLOSE_DELAY=-1} and pool size 1 (single-writer file DB); it does
 * <b>NOT</b> use {@code NON_KEYWORDS} (that flag is H2 2.x-only — AM-10).
 *
 * <p><b>Owning thread(s):</b> stateless. <b>Mutability:</b> immutable singleton.
 */
public final class H2Dialect implements SqlDialect {

    /** The shared stateless instance. */
    public static final H2Dialect INSTANCE = new H2Dialect();

    private H2Dialect() {
    }

    @Override
    public String name() {
        return "h2";
    }

    @Override
    public String driverClassName() {
        // shaded/relocated first, canonical fallback (pvp-data §6)
        if (classPresent("dev.fablemc.factions.lib.h2.Driver")) {
            return "dev.fablemc.factions.lib.h2.Driver";
        }
        return "org.h2.Driver";
    }

    /**
     * Builds the H2 file-DB URL: {@code jdbc:h2:file:<path>;MODE=MySQL;DB_CLOSE_DELAY=-1}. No
     * {@code NON_KEYWORDS} flag (AM-10 — that is H2 2.x-only and would fail on the pinned 1.4.200).
     */
    public static String fileUrl(String absolutePath) {
        return "jdbc:h2:file:" + absolutePath + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    }

    @Override
    public String upsert(String table, String[] columns, String[] keyColumns) {
        return "MERGE INTO `" + table + "` (" + SqlDialect.columnList(columns) + ") KEY("
                + SqlDialect.columnList(keyColumns) + ") VALUES ("
                + SqlDialect.placeholders(columns.length) + ")";
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, H2Dialect.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
