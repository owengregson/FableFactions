package dev.fablemc.factions.core.integration.worldguard;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The WorldGuard territory façade the claim / sethome / warp-set pre-flight consults
 * (ref-integrations §2.1, proposal-C §10.3). One interface so protection code never imports
 * {@code com.sk89q}; the reflective implementation covers presence and {@link NoopTerritoryGuard}
 * covers absence.
 *
 * <p><b>Owning thread(s):</b> called from command pre-flight / protection listeners on the region
 * thread. <b>Mutability:</b> implementations are immutable.
 */
public interface TerritoryGuard {

    /** {@code true} when {@code player} may modify {@code location} per WorldGuard (fail-open). */
    boolean canModifyTerritory(Player player, Location location);

    /** {@code true} when this guard mirrors faction claims as WorldGuard regions (sync mode). */
    default boolean syncsBuildProtection() {
        return false;
    }

    /** {@code true} when {@code location} sits inside a faction-mirrored WorldGuard region. */
    default boolean isFactionRegion(Location location) {
        return false;
    }
}
