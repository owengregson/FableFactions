package dev.fablemc.factions.core.integration.lwc;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * The LWC-absent façade: registration and purge are no-ops (ref-integrations §6.9), so faction
 * operations never depend on LWC being installed.
 *
 * <p><b>Owning thread(s):</b> any. <b>Mutability:</b> stateless.
 */
public final class NoopLwcInterop implements LwcInterop {

    @Override
    public void register(Plugin plugin) {
    }

    @Override
    public void unregister() {
    }

    @Override
    public void purge(World world, int chunkX, int chunkZ, int newOwnerOrdinal) {
    }
}
