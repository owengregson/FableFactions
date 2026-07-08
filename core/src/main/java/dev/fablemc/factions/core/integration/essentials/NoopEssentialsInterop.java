package dev.fablemc.factions.core.integration.essentials;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The Essentials-absent façade: {@link #teleport} never handles (returns {@code false} so the
 * native teleport path runs) and both status checks return {@code false} (ref-integrations §4.5).
 *
 * <p><b>Owning thread(s):</b> any. <b>Mutability:</b> stateless.
 */
public final class NoopEssentialsInterop implements EssentialsInterop {

    @Override
    public boolean teleport(Player player, Location dest, Runnable onSuccess, Runnable onFailure) {
        return false;
    }

    @Override
    public boolean isJailed(Player player) {
        return false;
    }

    @Override
    public boolean isVanished(Player player) {
        return false;
    }
}
