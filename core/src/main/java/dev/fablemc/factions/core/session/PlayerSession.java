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
    private Runnable warmupAbort;  // offline-safe refund, run iff a paid warmup is torn down early
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

    /**
     * Arms a warmup: stores the countdown {@code handle} and an optional {@code abort} action — an
     * offline-safe refund for a paid warp — that runs ONLY if the warmup is torn down before it
     * completes (see {@link #abortWarmup()}). Any prior task is cancelled first, its abort dropped
     * (not run): arming is only reached after the saga confirmed no warmup was already active.
     */
    public void armWarmup(TaskHandle handle, Runnable abort) {
        TaskHandle prior = this.warmupTask;
        this.warmupTask = handle;
        this.warmupAbort = abort;
        if (prior != null && !prior.cancelled()) {
            prior.cancel();
        }
    }

    /**
     * Ends the warmup normally — the teleport fired, or the saga already settled the money itself on
     * a move/combat cancel. Cancels the task and drops the abort hook WITHOUT refunding.
     */
    public void completeWarmup() {
        this.warmupAbort = null;
        TaskHandle handle = this.warmupTask;
        this.warmupTask = null;
        if (handle != null && !handle.cancelled()) {
            handle.cancel();
        }
    }

    /**
     * Aborts an in-flight warmup because the entity retired mid-countdown or the session is being
     * torn down (logout/kick): cancels the task and RUNS the abort refund exactly once, so a charged
     * warp's cost is returned rather than eaten (findings #27/#36/#59). The abort hook is captured
     * then cleared, so a second abort (a scheduler retire and a teardown both firing) never double-
     * refunds. A no-op when no warmup is armed. Must run on the player's region thread (AM-14).
     */
    public void abortWarmup() {
        Runnable abort = this.warmupAbort;
        this.warmupAbort = null;
        TaskHandle handle = this.warmupTask;
        this.warmupTask = null;
        if (handle != null && !handle.cancelled()) {
            handle.cancel();
        }
        if (abort != null) {
            abort.run();
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
     * wires this on the region thread). Aborts the warmup — refunding a charged warp whose teleport
     * will now never fire — and drops the GUI handle; the session is discarded afterward.
     */
    void onClose() {
        abortWarmup();
        this.guiSession = null;
        this.lastChunkKey = NO_CHUNK;
        this.combatTagUntil = 0L;
        this.flyGraceUntil = 0L;
    }
}
