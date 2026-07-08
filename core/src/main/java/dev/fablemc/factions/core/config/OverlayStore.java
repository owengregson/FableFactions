package dev.fablemc.factions.core.config;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The overlay layer of the two-layer config write model (proposal-C §9.1, Mental §7): human-edited
 * YAML files are never re-serialized, so GUI/API/admin writes go to {@code state/overrides.yml}
 * and take precedence over the on-disk file. Effective value = overlay ?? file ?? default; the
 * {@link ConfigParser} sees a merged view because {@link #applyOnto} stamps every overlay leaf onto
 * the freshly-loaded base config before the parse.
 *
 * <p><b>Owning thread(s):</b> loaded and merged on the boot / reload parse thread; runtime
 * {@link #set}/{@link #save} writes come from the (single) admin-command handler. It is NOT
 * internally synchronized — callers confine it to the config-management path. <b>Mutability:</b>
 * mutable holder around one {@link YamlConfiguration}; each {@link #save} rewrites the file.
 */
public final class OverlayStore {

    private final File file;
    private YamlConfiguration overlay;

    /** Binds the store to {@code overridesFile} (typically {@code <dataFolder>/state/overrides.yml}). */
    public OverlayStore(File overridesFile) {
        this.file = Objects.requireNonNull(overridesFile, "overridesFile");
        this.overlay = new YamlConfiguration();
    }

    /** (Re)loads the overlay from disk; an absent file yields an empty overlay (no error). */
    public void load() {
        if (file.isFile()) {
            this.overlay = YamlConfiguration.loadConfiguration(file);
        } else {
            this.overlay = new YamlConfiguration();
        }
    }

    /** {@code true} when the overlay defines {@code path}. */
    public boolean contains(String path) {
        return overlay.contains(path);
    }

    /** The overlaid value at {@code path}, or {@code null} when the overlay does not define it. */
    public Object get(String path) {
        return overlay.get(path);
    }

    /** Sets an overlay value in memory (call {@link #save} to persist). */
    public void set(String path, Object value) {
        overlay.set(path, value);
    }

    /** Removes an overlay value in memory (call {@link #save} to persist). */
    public void clear(String path) {
        overlay.set(path, null);
    }

    /**
     * Stamps every overlay <em>leaf</em> onto {@code base} so a subsequent parse reads the effective
     * (overlay-wins) value. Intermediate sections are skipped — only concrete values override.
     */
    public void applyOnto(ConfigurationSection base) {
        Objects.requireNonNull(base, "base");
        for (String path : overlay.getKeys(true)) {
            if (!overlay.isConfigurationSection(path)) {
                base.set(path, overlay.get(path));
            }
        }
    }

    /** Persists the in-memory overlay to disk, creating the parent {@code state/} directory. */
    public void save() throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        overlay.save(file);
    }
}
