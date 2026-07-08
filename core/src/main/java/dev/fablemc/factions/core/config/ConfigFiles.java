package dev.fablemc.factions.core.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Owns the on-disk lifecycle of the six configuration files: it extracts the bundled defaults when a
 * file is missing, loads each into a {@link YamlConfiguration}, merges the {@link OverlayStore} on
 * top of {@code config.yml}, runs the versioned migration hook, and hands the {@link ConfigParser}
 * one {@link ConfigParser.Sources} bundle.
 *
 * <p><b>Owning thread(s):</b> {@link #loadAll} runs off the writer — the boot thread once, then the
 * async {@code /fa reload} thread; it does file I/O but no Bukkit runtime and no kernel-state
 * construction (CONTRACTS §4). <b>Mutability:</b> holds the data folder, a resource opener, and the
 * shared overlay; each {@link #loadAll} re-reads disk.
 */
public final class ConfigFiles {

    /** The current {@code config.yml} schema version; bump when a breaking key rename lands. */
    public static final int CURRENT_CONFIG_VERSION = 1;

    /** The bundled file names extracted into the data folder (and loaded) verbatim. */
    public static final String FILE_CONFIG = "config.yml";
    public static final String FILE_DATABASE = "database.yml";
    public static final String FILE_ROLES = "roles.yml";
    public static final String FILE_NOTIFICATIONS = "notifications.yml";
    public static final String FILE_PREDEFINED = "pre-defined.yml";
    public static final String FILE_GUI = "gui.yml";

    private static final String STATE_DIR = "state";
    private static final String OVERRIDES_FILE = "overrides.yml";

    private final File dataFolder;
    private final Function<String, InputStream> resourceOpener;
    private final OverlayStore overlay;

    /**
     * @param dataFolder     the plugin data folder ({@code plugins/FableFactions/})
     * @param resourceOpener opens a bundled resource by name (e.g. {@code getResourceAsStream("/"+n)});
     *                       returns {@code null} when the resource is absent
     */
    public ConfigFiles(File dataFolder, Function<String, InputStream> resourceOpener) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.resourceOpener = Objects.requireNonNull(resourceOpener, "resourceOpener");
        this.overlay = new OverlayStore(new File(new File(dataFolder, STATE_DIR), OVERRIDES_FILE));
    }

    /** The shared runtime overlay (GUI/API/admin writes land here — see {@link OverlayStore}). */
    public OverlayStore overlay() {
        return overlay;
    }

    /**
     * Extracts any missing default, loads all six files, applies the overlay onto {@code config.yml},
     * migrates it, and returns the parse bundle. Recoverable problems (a missing bundled resource, a
     * failed extraction) are appended to {@code issues} rather than thrown, so a partial config still
     * boots on reference defaults.
     */
    public ConfigParser.Sources loadAll(List<String> issues) {
        Objects.requireNonNull(issues, "issues");
        overlay.load();

        YamlConfiguration config = load(FILE_CONFIG, issues);
        overlay.applyOnto(config);
        migrate(config, issues);

        return new ConfigParser.Sources(
                config,
                load(FILE_DATABASE, issues),
                load(FILE_ROLES, issues),
                load(FILE_NOTIFICATIONS, issues),
                load(FILE_PREDEFINED, issues),
                load(FILE_GUI, issues));
    }

    /** Extracts a single named default if it is missing (used by boot to lay down the tree). */
    public void extractDefaults(List<String> issues) {
        for (String name : new String[] {FILE_CONFIG, FILE_DATABASE, FILE_ROLES, FILE_NOTIFICATIONS,
                FILE_PREDEFINED, FILE_GUI}) {
            extractIfMissing(name, issues);
        }
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────

    private YamlConfiguration load(String name, List<String> issues) {
        File target = extractIfMissing(name, issues);
        if (!target.isFile()) {
            issues.add(name + " is missing and could not be extracted; using defaults");
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(target);
    }

    private File extractIfMissing(String name, List<String> issues) {
        File target = new File(dataFolder, name);
        if (target.isFile()) {
            return target;
        }
        try (InputStream in = resourceOpener.apply(name)) {
            if (in == null) {
                issues.add("bundled default '" + name + "' not found on the classpath");
                return target;
            }
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.copy(in, target.toPath());
        } catch (IOException ex) {
            issues.add("could not extract '" + name + "': " + ex.getMessage());
        }
        return target;
    }

    /**
     * The versioned migration hook: reads {@code config-version} and upgrades an older layout in
     * place before parsing. v1 is the initial schema, so this is a documented no-op today; future
     * key renames add a {@code switch} arm here and bump {@link #CURRENT_CONFIG_VERSION}.
     */
    private void migrate(YamlConfiguration config, List<String> issues) {
        int version = config.getInt(ConfigKeys.CONFIG_VERSION, CURRENT_CONFIG_VERSION);
        if (version > CURRENT_CONFIG_VERSION) {
            issues.add("config-version " + version + " is newer than this build supports ("
                    + CURRENT_CONFIG_VERSION + "); reading it as best-effort");
        }
        // No breaking migrations exist for v1; add per-version upgrade arms here as the schema evolves.
    }
}
