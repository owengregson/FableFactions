package dev.fablemc.factions.core.session;

import org.bukkit.entity.Player;

import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * The teleport-initiation seam the travel commands ({@code /f home}, {@code /f warp}) call — the
 * one surface behind which warmup countdowns, combat-tag gating, cooldowns and Vault warp costs
 * live (ref-commands-core.md §7.12, ref-commands-misc.md §5). Command {@code perform} bodies never
 * teleport directly; they hand the request here and the implementation (W3e {@code TeleportSaga})
 * runs the saga on the player's region thread.
 *
 * <p><b>Owning thread(s):</b> called from a command {@code perform} on the player's region/main
 * thread; the implementation marshals its own follow-up work through {@code Scheduling}.
 * <b>Mutability:</b> the implementation is stateful (tracks in-flight warmups per player) but
 * confined to the session/region thread; this interface is a pure seam.
 */
public interface Travel {

    /**
     * Begins a teleport to the caller's faction home using {@code snapshot} (taken once at command
     * dispatch) for the home location and gating checks. A no-op with player feedback when the
     * faction has no home, the player is jailed, or a warmup is already running.
     */
    void beginHome(Player player, KernelSnapshot snapshot);

    /**
     * Begins a teleport to the named faction warp, supplying the optional {@code password} typed on
     * the command line ({@code null} when none was given). The implementation resolves the warp,
     * validates the password, charges any Vault cost and runs the warmup; all feedback flows through
     * the message layer.
     */
    void beginWarp(Player player, String warpName, String password);

    /**
     * Whether {@code player} is currently combat-tagged — the read {@code /f fly} consults before
     * enabling flight so a player cannot gain flight to escape a fight (finding #30). {@code false}
     * when the player has no live session or combat tagging is disabled. Call on the player's region
     * thread (reads the confined {@link PlayerSession}, AM-14).
     */
    boolean inCombat(Player player);
}
