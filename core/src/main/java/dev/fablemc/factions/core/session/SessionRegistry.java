package dev.fablemc.factions.core.session;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

/**
 * The registry of live {@link PlayerSession}s, keyed by player UUID (AM-14, CONTRACTS §4).
 *
 * <p><b>Owning thread(s):</b> a session is created on {@link #open} and removed on {@link #close}
 * — both run on the joining/leaving player's region thread (Wave 4 wires them off the
 * join/quit listeners via {@code Scheduling.runOn}). The <em>map itself</em> is a
 * {@link ConcurrentHashMap} because different region threads create/remove distinct sessions
 * concurrently, but <b>each {@link PlayerSession} is confined to its own player's region
 * thread</b> and must never be mutated from another. {@link #get}/{@link #forEach} therefore hand
 * back a session for the caller to touch only when the caller IS that player's region thread.
 * <b>Mutability:</b> the map is thread-safe; the sessions it holds are confined.
 *
 * <p>This is the seam Wave 4 wires: {@link #open}/{@link #close} are the join/quit lifecycle
 * hooks; the listeners hop to the player's region thread before calling them.
 */
public final class SessionRegistry {

    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    /**
     * Opens (or returns the existing) session for {@code player}. Idempotent: a re-open for an
     * already-tracked player returns the live session rather than replacing it.
     */
    public PlayerSession open(Player player) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        UUID id = player.getUniqueId();
        return sessions.computeIfAbsent(id, key -> new PlayerSession(key, player));
    }

    /**
     * Opens (or returns) a session by raw UUID with an optional {@link Player} handle — used by
     * tests and by boot paths that do not hold a live {@code Player}.
     */
    public PlayerSession open(UUID playerId, Player player) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        return sessions.computeIfAbsent(playerId, key -> new PlayerSession(key, player));
    }

    /** The live session for {@code playerId}, or {@code null} if the player is not tracked. */
    public PlayerSession get(UUID playerId) {
        return playerId == null ? null : sessions.get(playerId);
    }

    /** {@code true} if a session exists for {@code playerId}. */
    public boolean has(UUID playerId) {
        return playerId != null && sessions.containsKey(playerId);
    }

    /**
     * Closes and removes the session for {@code playerId}, running its {@link PlayerSession#onClose}
     * teardown (cancels warmup, drops the GUI handle). Returns the removed session, or {@code null}
     * if none was tracked. Must be called on the player's region thread.
     */
    public PlayerSession close(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        PlayerSession removed = sessions.remove(playerId);
        if (removed != null) {
            removed.onClose();
        }
        return removed;
    }

    /** The number of live sessions (telemetry / tests). */
    public int size() {
        return sessions.size();
    }

    /**
     * Applies {@code action} to every live session. Note the confinement contract: the caller is
     * responsible for only <em>mutating</em> a session it owns (its player's region thread) — a
     * global sweep should read only, or hop per-player via the scheduler.
     */
    public void forEach(Consumer<PlayerSession> action) {
        sessions.values().forEach(action);
    }

    /** Closes and removes every session (plugin disable). Runs teardown on each. */
    public void clear() {
        for (UUID id : sessions.keySet()) {
            close(id);
        }
    }
}
