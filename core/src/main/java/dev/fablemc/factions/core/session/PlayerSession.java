package dev.fablemc.factions.core.session;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.entity.Player;

import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * Per-online-player, region-thread-confined tracking state (AM-14, CONTRACTS §4).
 *
 * <p><b>Owning thread(s):</b> created and destroyed by {@link SessionRegistry} on the player's
 * region thread; <b>every mutable field below is owned and mutated ONLY by that player's region
 * thread</b> (reached via {@code Scheduling.runOn}). There is no cross-thread mutable sharing:
 * the session is confined, and the domain {@code KernelSnapshot} it reads is immutable.
 * <b>Mutability:</b> confined mutable value (not thread-safe by design — never touch a session
 * from another thread).
 *
 * <p><b>Identity fields are NOT stored here.</b> Per AM-14, writer-published values — the member
 * ordinal, {@code factions.bypass}/permission bits, faction handle — are re-read from the current
 * snapshot per event, never cached mutably in the session, so a session can never disagree with
 * the published state. This holds only the transient, per-player <em>tracking</em> state the
 * region thread needs between events: last chunk crossed, combat/fly windows, and handles to the
 * warmup task and GUI view (both Wave-4-wired).
 */
public final class PlayerSession {

    /** Sentinel {@link #lastChunkKey()} meaning "no chunk observed yet this session". */
    public static final long NO_CHUNK = Long.MIN_VALUE;

    private final UUID playerId;
    private final Player player;

    private long lastChunkKey = NO_CHUNK;
    private long combatTagUntil;   // epoch millis; 0 = not combat-tagged
    private long flyGraceUntil;    // epoch millis; fly grace window after leaving own territory
    private TaskHandle warmupTask; // active teleport/warp warmup countdown, or null
    private Object guiSession;     // Wave-4 GUI view handle (opaque here), or null

    PlayerSession(UUID playerId, Player player) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.player = player;   // nullable in tests; non-null when created from a live Player
    }

    /** The player's UUID (stable identity; safe to read from any thread). */
    public UUID playerId() {
        return playerId;
    }

    /** The live {@link Player} handle this session tracks (region-thread use). */
    public Player player() {
        return player;
    }

    // ── chunk-crossing tracking (MoveListener) ────────────────────────────────────────────

    /** The last chunk key this player was observed in, or {@link #NO_CHUNK} if none yet. */
    public long lastChunkKey() {
        return lastChunkKey;
    }

    /** Records the player's current chunk key; returns {@code true} if it changed (crossing). */
    public boolean updateChunk(long chunkKey) {
        if (chunkKey == lastChunkKey) {
            return false;
        }
        lastChunkKey = chunkKey;
        return true;
    }

    // ── combat tag (CombatProtectionListener / DeathListener) ──────────────────────────────

    /** Epoch-millis instant the combat tag expires ({@code 0} when not tagged). */
    public long combatTagUntil() {
        return combatTagUntil;
    }

    /** Tags the player as in combat until {@code untilEpochMillis} (only ever extends). */
    public void tagCombat(long untilEpochMillis) {
        if (untilEpochMillis > combatTagUntil) {
            combatTagUntil = untilEpochMillis;
        }
    }

    /** Clears any active combat tag. */
    public void clearCombat() {
        combatTagUntil = 0L;
    }

    /** {@code true} while {@code nowEpochMillis} is inside the combat window. */
    public boolean inCombat(long nowEpochMillis) {
        return nowEpochMillis < combatTagUntil;
    }

    // ── fly grace (GlideListener / fly engine) ─────────────────────────────────────────────

    /** Epoch-millis instant the fly grace window ends ({@code 0} when none). */
    public long flyGraceUntil() {
        return flyGraceUntil;
    }

    /** Grants a fly-grace window until {@code untilEpochMillis} (only ever extends). */
    public void grantFlyGrace(long untilEpochMillis) {
        if (untilEpochMillis > flyGraceUntil) {
            flyGraceUntil = untilEpochMillis;
        }
    }

    /** {@code true} while {@code nowEpochMillis} is inside the fly-grace window. */
    public boolean inFlyGrace(long nowEpochMillis) {
        return nowEpochMillis < flyGraceUntil;
    }

    // ── warmup task handle (TeleportSaga, Wave 4) ─────────────────────────────────────────

    /** The active warmup task, or {@code null}. */
    public TaskHandle warmupTask() {
        return warmupTask;
    }

    /** Sets (or replaces) the active warmup task; cancels any prior one first. */
    public void setWarmupTask(TaskHandle handle) {
        cancelWarmup();
        this.warmupTask = handle;
    }

    /** Cancels and clears any active warmup task. */
    public void cancelWarmup() {
        TaskHandle prior = this.warmupTask;
        this.warmupTask = null;
        if (prior != null && !prior.cancelled()) {
            prior.cancel();
        }
    }

    // ── GUI view handle (MenuManager, Wave 4) ─────────────────────────────────────────────

    /** The open GUI view handle (Wave-4 type, opaque here), or {@code null}. */
    public Object guiSession() {
        return guiSession;
    }

    /** Attaches (or clears with {@code null}) the open GUI view handle. */
    public void setGuiSession(Object guiSession) {
        this.guiSession = guiSession;
    }

    /**
     * Lifecycle teardown, called by {@link SessionRegistry} when the player disconnects (Wave 4
     * wires this on the region thread). Cancels the warmup and drops the GUI handle; the session
     * is discarded afterward.
     */
    void onClose() {
        cancelWarmup();
        this.guiSession = null;
        this.lastChunkKey = NO_CHUNK;
        this.combatTagUntil = 0L;
        this.flyGraceUntil = 0L;
    }
}
