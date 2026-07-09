package dev.fablemc.factions.kernel.config;

/**
 * Read-only view of {@code database.yml} carried in the config image (ref-resources.md §2).
 *
 * <p><b>Owning thread(s):</b> parsed in {@code :core}, read on any thread. <b>Mutability:</b>
 * immutable value. <b>Reducer rule:</b> present for completeness / display; database settings
 * are boot-only (a {@code SwapConfig} does not re-open the pool), matching the reference.
 *
 * <p>The kernel does no storage — this is purely a typed mirror so the full config inventory is
 * representable and readable (e.g. by bStats database-type charts).
 */
public record StorageConfigView(
        String type,
        String embeddedFile,
        String mysqlHost,
        int mysqlPort,
        String mysqlDatabase,
        String mysqlUsername,
        int mysqlPoolSize,
        boolean jaloquentLogging) {

    /** The reference-default storage view (embedded HSQLDB). */
    public static StorageConfigView defaults() {
        return new StorageConfigView(
                "hsqldb",
                "data/factions",
                "localhost",
                3306,
                "factions",
                "root",
                10,
                false);
    }
}
