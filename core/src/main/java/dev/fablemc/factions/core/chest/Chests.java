package dev.fablemc.factions.core.chest;

import org.bukkit.entity.Player;

/**
 * The team-chest open/commit seam the {@code /f chest} commands call (ref-commands-misc.md §2).
 * The command layer never touches inventory bytes or storage; it asks this seam to open a named
 * chest and the implementation (W3e {@code ChestSessions}) loads the blob, opens the 54-slot view,
 * and commits the contents back on close through the intent pipeline.
 *
 * <p><b>Owning thread(s):</b> {@link #open} is called from a command {@code perform} on the
 * player's region/main thread; {@link #forceCommitAll} is called on shutdown (main thread) to
 * flush every still-open session before the plugin disables. <b>Mutability:</b> the implementation
 * holds per-player open sessions confined to the region/main thread; this interface is a pure seam.
 */
public interface Chests {

    /**
     * Opens the faction team chest named {@code chestName} for {@code player}, auto-creating it when
     * absent and under the configured cap. Missing-chest / limit-reached outcomes are reported to the
     * player through the message layer; this method never blocks.
     */
    void open(Player player, String chestName);

    /**
     * Commits every currently-open chest session's contents back to storage — the shutdown flush so
     * no in-flight edit is lost when the plugin disables.
     */
    void forceCommitAll();
}
