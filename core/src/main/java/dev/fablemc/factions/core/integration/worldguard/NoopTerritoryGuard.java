package dev.fablemc.factions.core.integration.worldguard;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The WorldGuard-absent territory façade: {@link #canModifyTerritory} always allows and the region
 * probes report {@code false}, so protection falls back to the plain claim path and warps/auto-claim
 * are never blocked by WG (ref-integrations §2.7).
 *
 * <p><b>Owning thread(s):</b> any. <b>Mutability:</b> stateless.
 */
public final class NoopTerritoryGuard implements TerritoryGuard {

    @Override
    public boolean canModifyTerritory(Player player, Location location) {
        return true;
    }
}
