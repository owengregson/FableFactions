package dev.fablemc.factions.core.integration.essentials;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The EssentialsX teleport/jail/vanish façade the home/warp travel flows consult (ref-integrations
 * §4). One interface so the travel engine never imports {@code com.earth2me.essentials}; the
 * reflective implementation covers presence and {@link NoopEssentialsInterop} covers absence.
 *
 * <p><b>Owning thread(s):</b> called from the travel/teleport flow on the player's region thread.
 * <b>Mutability:</b> implementations hold only a resolved Essentials handle.
 */
public interface EssentialsInterop {

    /**
     * Attempts an Essentials async teleport. Returns {@code true} when Essentials <b>handles</b> it
     * (and fires exactly one of {@code onSuccess}/{@code onFailure} on completion); returns
     * {@code false} when the caller must fall back to a native {@code player.teleport(dest)}
     * (ref-integrations §4.1).
     */
    boolean teleport(Player player, Location dest, Runnable onSuccess, Runnable onFailure);

    /** {@code true} when Essentials reports the player jailed (blocks teleports at call sites). */
    boolean isJailed(Player player);

    /** {@code true} when Essentials reports the player vanished (exposed for completeness). */
    boolean isVanished(Player player);
}
