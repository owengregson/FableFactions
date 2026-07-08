package dev.fablemc.factions.platform.sched;

import dev.fablemc.factions.platform.probe.Capabilities;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Chooses the scheduling backend for this server (CONTRACTS §3, AM-12).
 *
 * <p>The selector keys on {@link Capabilities#folia()} <b>alone</b> (the
 * {@code RegionizedServer} class probe). The presence of the {@code EntityScheduler}
 * classes ({@code foliaSchedulers}) is a boot-report fact and an assertion, never a
 * selector — those classes exist on plain Paper 1.20+ (AM-12).
 *
 * <p>The Folia implementation is referenced by <b>FQN string only</b>: its class is
 * compiled against a newer API and must never be loaded on servers lacking the
 * region-scheduler types. On regular Paper the class is never touched, so its
 * newer-API references never link.
 *
 * <p>Owning thread(s): the boot thread. Mutability class: static-only utility.
 */
public final class SchedulingFactory {

    /** The compat-folia backend, loaded reflectively so plain Paper never links it. */
    private static final String FOLIA_IMPL = "dev.fablemc.factions.compat.folia.FoliaScheduling";

    private SchedulingFactory() {}

    public static @NotNull Scheduling create(@NotNull Plugin plugin, @NotNull Capabilities capabilities) {
        // AM-12: caps.folia() is the SOLE selector. foliaSchedulers is never consulted here.
        if (!capabilities.folia()) {
            return new BukkitScheduling(plugin);
        }
        try {
            return (Scheduling) Class.forName(FOLIA_IMPL)
                    .getDeclaredConstructor(Plugin.class)
                    .newInstance(plugin);
        } catch (ReflectiveOperationException | LinkageError failure) {
            // On Folia the Bukkit scheduler would hard-throw anyway; surface the real problem loudly.
            throw new IllegalStateException(
                    "Folia detected but the Folia scheduling backend failed to load", failure);
        }
    }
}
