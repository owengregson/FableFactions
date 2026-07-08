package dev.fablemc.factions.core.integration.lwc;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * The LWC/LWCX chest-protection façade (ref-integrations §6). Registration and stale-protection
 * behaviors are wired reflectively (no {@code com.griefcraft} imports); the ownership-change purge
 * is driven by the kernel's {@code ExternalEffect.LwcPurgeRequested} effect.
 *
 * <p><b>Owning thread(s):</b> {@link #register}/{@link #unregister} on the boot/shutdown thread;
 * {@link #purge} on the chunk's owning region thread (routed by the effect sink, AM-12).
 * <b>Mutability:</b> implementations hold registration state guarded for idempotency.
 */
public interface LwcInterop {

    /** Registers the LWC event listeners (idempotent). */
    void register(Plugin plugin);

    /** Unregisters the LWC event listeners. */
    void unregister();

    /**
     * Purges LWC protections in {@code (chunkX, chunkZ)} whose owner is not a member of the chunk's
     * new owning faction ({@code newOwnerOrdinal}); a no-op when the integration is a Noop.
     */
    void purge(World world, int chunkX, int chunkZ, int newOwnerOrdinal);
}
