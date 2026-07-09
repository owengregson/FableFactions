package dev.fablemc.factions.core.session;

import java.util.Objects;
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
     * Combat-tag expiries (epoch-millis) that outlive a disconnect, keyed by player UUID, so a
     * relog inside the window cannot shed the tag (finding #28). Written on {@link #close} from the
     * still-live session's field before it is zeroed; consumed by {@link #restoreCombatTag} on the
     * next join. Cross-thread (distinct region threads close/open distinct players), hence concurrent.
     */
    private final ConcurrentHashMap<UUID, Long> combatTagCarry = new ConcurrentHashMap<>();

    /**
     * Opens (or returns the existing) session for {@code player}. Idempotent: a re-open for an
     * already-tracked player returns the live session rather than replacing it.
     */
    public PlayerSession open(Player player) {
        Objects.requireNonNull(player, "player");
        UUID id = player.getUniqueId();
        return sessions.computeIfAbsent(id, key -> new PlayerSession(key, player));
    }

    /**
     * Opens (or returns) a session by raw UUID with an optional {@link Player} handle — used by
     * tests and by boot paths that do not hold a live {@code Player}.
     */
    public PlayerSession open(UUID playerId, Player player) {
        Objects.requireNonNull(playerId, "playerId");
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
     * teardown (aborts the warmup — refunding a paid warp in flight — and drops the GUI handle) and
     * carrying a live combat tag forward for re-login. Returns the removed session, or {@code null}
     * if none was tracked. Must be called on the player's region thread.
     */
    public PlayerSession close(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        PlayerSession removed = sessions.remove(playerId);
        if (removed != null) {
            // Carry a still-live combat tag across the disconnect BEFORE onClose zeroes the field,
            // so a fast relog re-applies it (finding #28). restoreCombatTag decides freshness on join.
            long tagUntil = removed.combatTagUntil();
            if (tagUntil > 0L) {
                combatTagCarry.put(playerId, tagUntil);
            }
            removed.onClose();
        }
        return removed;
    }

    /**
     * Re-applies a combat tag that outlived the player's disconnect: if the player left while
     * combat-tagged and the carried expiry is still in the future at {@code nowEpochMillis}, stamps
     * it back onto the freshly-opened live session so a re-login cannot escape the window (finding
     * #28). The carried entry is consumed either way. Returns the re-applied expiry epoch-millis, or
     * {@code 0} when there was none / it had already lapsed / the session is not open.
     *
     * <p>The join handler calls this right after {@link #open(Player)} — on the joining player's
     * region thread (AM-14), since it mutates the confined session. See {@code SessionListener.onJoin}.
     */
    public long restoreCombatTag(UUID playerId, long nowEpochMillis) {
        if (playerId == null) {
            return 0L;
        }
        Long carried = combatTagCarry.remove(playerId);
        if (carried == null || carried <= nowEpochMillis) {
            return 0L;
        }
        PlayerSession session = sessions.get(playerId);
        if (session == null) {
            return 0L;
        }
        session.tagCombat(carried);
        return carried;
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
