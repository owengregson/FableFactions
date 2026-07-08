package dev.fablemc.factions.platform.probe;

import org.jetbrains.annotations.NotNull;

/**
 * The single registry of every reflectively-loaded compat FQN (CONTRACTS §3, AM-12/Risk #9).
 * These classes are compiled against a newer server API in the {@code :compat-folia} /
 * {@code :compat-modern} modules and must be referenced by <b>string only</b>, so plain / older
 * servers never link them. Centralising the FQNs here means a rename is a one-line change and no
 * raw compat class string is ever scattered across the platform.
 *
 * <p><b>Owning thread(s):</b> read from the boot thread (and wherever a capability gate fires).
 * <b>Mutability:</b> immutable enum. Every future reflectively-loaded compat class MUST be added
 * here rather than inlined as a string literal.
 */
public enum CompatClass {

    /** {@code :compat-folia} scheduling backend (constructor {@code (org.bukkit.plugin.Plugin)}). */
    FOLIA_SCHEDULING("dev.fablemc.factions.compat.folia.FoliaScheduling"),
    /** {@code :compat-modern} Paper {@code serializeAsBytes} item codec. */
    MODERN_ITEM_CODEC("dev.fablemc.factions.compat.modern.ModernItemCodec"),
    /** {@code :compat-modern} Brigadier command installer (behind the {@code brigadier} capability). */
    BRIGADIER_INSTALLER("dev.fablemc.factions.compat.modern.BrigadierInstaller"),
    /** {@code :compat-modern} async chunk loader (behind the {@code asyncChunkGet} capability). */
    ASYNC_CHUNKS("dev.fablemc.factions.compat.modern.AsyncChunks");

    private final String fqn;

    CompatClass(String fqn) {
        this.fqn = fqn;
    }

    /** The fully-qualified class name to load reflectively. */
    public @NotNull String fqn() {
        return fqn;
    }

    /**
     * Loads (and initialises) this compat class with {@code loader}. Throws
     * {@link ClassNotFoundException} on an older server — the caller degrades behind its capability
     * gate. Use {@code CompatClass.X.fqn()} with {@code Class.forName} directly where a specific
     * loader or lazy init is required.
     */
    public @NotNull Class<?> load(@NotNull ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(fqn, true, loader);
    }
}
