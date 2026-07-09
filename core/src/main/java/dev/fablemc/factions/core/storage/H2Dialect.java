package dev.fablemc.factions.core.storage;

/**
 * The H2 backend dialect. Upserts rewrite to H2's {@code MERGE INTO … KEY(…)} form, which — unlike
 * {@code ON DUPLICATE KEY UPDATE} — H2 implements natively. The JDBC URL uses
 * {@code MODE=MySQL;DB_CLOSE_DELAY=-1} and pool size 1 (single-writer file DB). Backed by H2
 * 2.2.224 (the latest Java-8-native line); the schema + MySQL mode clears 2.x's stricter reserved
 * words without needing the {@code NON_KEYWORDS} flag (the full-DDL storage tests confirm it).
 *
 * <p><b>Owning thread(s):</b> stateless. <b>Mutability:</b> immutable singleton.
 */
public final class H2Dialect implements SqlDialect {

    /** The pinned {@link #name()} value ({@code ff_meta} / logging vocabulary). */
    public static final String NAME = "h2";

    /** The shared stateless instance. */
    public static final H2Dialect INSTANCE = new H2Dialect();

    private H2Dialect() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String driverClassName() {
        // shaded/relocated first, canonical fallback (ref-data §6)
        if (SqlDialect.classPresent("dev.fablemc.factions.lib.h2.Driver")) {
            return "dev.fablemc.factions.lib.h2.Driver";
        }
        return "org.h2.Driver";
    }

    /**
     * Builds the H2 file-DB URL: {@code jdbc:h2:file:<path>;MODE=MySQL;DB_CLOSE_DELAY=-1}. No
     * {@code NON_KEYWORDS} flag is needed — the schema clears H2 2.x's reserved words in MySQL mode.
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
}
