package dev.fablemc.factions.core.command.predefined;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * The predefined-faction authoring seam behind {@code /f predefined claim|sethome|reload}
 * (ref-commands-misc.md §6). These subcommands edit {@code pre-defined.yml} rather than live kernel
 * state; a command body must not parse or write YAML on the server thread (CONTRACTS §4), so it
 * hands the edit to this seam, whose implementation (Wave 4 wiring) persists off-thread and rebuilds
 * the in-memory registry. The live faction create/seed path is a {@code CreateFaction} intent and
 * does not go through this seam.
 *
 * <p><b>Owning thread(s):</b> called from a command {@code perform} on the server thread; the
 * implementation performs its file IO off-thread. <b>Mutability:</b> the implementation owns the
 * on-disk registry; this interface is a pure seam.
 */
public interface Predefined {

    /** {@code true} when the predefined registry is initialized (drives the reload-failed path). */
    boolean available();

    /** Records a pre-claimed chunk for preset {@code factionName} (deduplicated by the impl). */
    void saveClaim(String factionName, World world, int chunkX, int chunkZ);

    /** Records the seed home for preset {@code factionName}. */
    void saveHome(String factionName, Location location);

    /** Reparses {@code pre-defined.yml} and swaps the resulting registry (best-effort, off-thread). */
    void reload();
}
