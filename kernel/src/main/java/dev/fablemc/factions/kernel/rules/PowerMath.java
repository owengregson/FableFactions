package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.config.BakedTables;
import dev.fablemc.factions.kernel.config.PowerConfig;
import dev.fablemc.factions.kernel.vocab.PowerSource;

/**
 * The power pipeline: transcription of the reference {@code PowerServiceImpl.apply} plus the
 * death-streak / kill-scale / lazy-accrual math (ref-services.md §5, ref-engines.md §3.7,
 * ref-bugs-concurrency Appendix). Pure static functions, unit-pinned per formula.
 *
 * <p><b>Owning thread(s):</b> the reducer's power branches; {@link #settle} also mirrors
 * {@code KernelSnapshot.powerAt}. <b>Mutability:</b> stateless. <b>Reducer rule:</b> power is a
 * lazy-accrual pair {@code (base, asOfTick)}; any power-affecting event settles the base to the
 * current tick first, applies a delta through {@link #apply}, and stores the new base.
 *
 * <p>Deviations pinned here: the per-event clamp is gated to automatic sources ONLY
 * (REGEN/DEATH/KILL — never ADMIN_* or BUY, deviation D-2); death loss honors the streak-scaled
 * magnitude passed by the caller (the coherent reading of the two reference loss keys, which
 * share the default {@code 4.0}); world×zone multipliers apply to DEATH/KILL only.
 */
public final class PowerMath {

    private PowerMath() {
    }

    // ── ZoneContext ordinals (BakedTables.zonePowerMultipliers index) ────────────────────
    public static final int ZONE_SAFEZONE = 0;
    public static final int ZONE_WARZONE = 1;
    public static final int ZONE_OWN_CLAIMED = 2;
    public static final int ZONE_ENEMY_CLAIMED = 3;
    public static final int ZONE_WILDERNESS = 4;

    /** The no-change epsilon: an effective delta smaller than this is dropped (reference 1e-5). */
    public static final double NO_CHANGE_EPSILON = 0.00001;

    /** Server ticks per second — the unit of the accrual clock ({@code IntentEnvelope.tick}). */
    public static final double TICKS_PER_SECOND = 20.0;

    /**
     * The per-server-tick regen rate for the lazy accrual. The configured amount is power per
     * <em>power-tick interval</em> (ref-engines §3.7: the reference applies it once every
     * {@code tickIntervalSeconds}), but the accrual clock {@code dt} is in server ticks (20/s), so
     * the amount is divided across the interval's server ticks. Without this the accrual regenerated
     * ~{@code tickIntervalSeconds*20}× too fast, pinning every online player at max power.
     */
    public static double perTickRegen(PowerConfig pc, boolean online) {
        double perInterval = online ? pc.sourceRegenOnlineAmount() : pc.sourceRegenOfflineAmount();
        return perInterval / (Math.max(1, pc.tickIntervalSeconds()) * TICKS_PER_SECOND);
    }

    /** Clamps {@code v} into {@code [min, max]}. */
    public static double clamp(double v, double min, double max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    /** {@code true} when the freeze flag blocks {@code source}. */
    public static boolean sourceAffectedByFreeze(PowerConfig pc, PowerSource source) {
        if (source.isAdmin()) {
            return false;
        }
        if (source == PowerSource.REGEN_ONLINE || source == PowerSource.REGEN_OFFLINE) {
            return pc.freezeBlocksRegen();
        }
        // DEATH / KILL / BUY
        return pc.freezeBlocksAutomatic();
    }

    /** {@code true} when {@code source} is enabled by config. */
    public static boolean sourceEnabled(PowerConfig pc, PowerSource source) {
        switch (source) {
            case REGEN_ONLINE: return pc.sourceRegenOnlineEnabled();
            case REGEN_OFFLINE: return pc.sourceRegenOfflineEnabled();
            case DEATH: return pc.sourceDeathLossEnabled();
            case KILL: return pc.sourceKillGainEnabled();
            case BUY: return pc.buyEnabled() && pc.sourceBuyEnabled();
            default: return true; // ADMIN_*
        }
    }

    /**
     * The delta magnitude BEFORE multipliers/clamp. Regen sources ignore {@code baseDelta} and
     * apply the configured amount; DEATH is {@code -abs(baseDelta)}, KILL is {@code +abs(baseDelta)}
     * (the streak-scaled loss / scaled gain the caller computed); BUY/ADMIN pass {@code baseDelta}.
     */
    public static double sourceAmount(PowerConfig pc, PowerSource source, double baseDelta) {
        switch (source) {
            case REGEN_ONLINE: return pc.sourceRegenOnlineAmount();
            case REGEN_OFFLINE: return pc.sourceRegenOfflineAmount();
            case DEATH: return -Math.abs(baseDelta);
            case KILL: return Math.abs(baseDelta);
            default: return baseDelta; // BUY / ADMIN_*
        }
    }

    /** DEATH/KILL world×zone multiplier product (1.0 for other sources). */
    public static double applyMultipliers(BakedTables baked, double delta, int worldIdx, int zoneCtx) {
        return delta * baked.worldMultiplier(worldIdx) * baked.zoneMultiplier(zoneCtx);
    }

    /**
     * Per-event clamp, gated to automatic sources only (D-2). Returns {@code delta} unchanged when
     * the source is BUY/ADMIN or {@code maxChangePerEvent <= 0}.
     */
    public static double applyEventClamp(PowerConfig pc, PowerSource source, double delta) {
        if (!source.isAutomatic()) {
            return delta;
        }
        double maxAbs = pc.maxChangePerEvent();
        if (maxAbs <= 0.0) {
            return delta;
        }
        return clamp(delta, -maxAbs, maxAbs);
    }

    /** The immutable outcome of {@link #apply}. */
    public record PowerResult(boolean changed, boolean blockedByFreeze, double before,
                              double after, double effectiveDelta, String reasonCode) {
    }

    /**
     * Applies one power request to a settled {@code before} value, returning the new value and
     * bookkeeping. Never mutates; the reducer stores {@link PowerResult#after()} as the new base.
     */
    public static PowerResult apply(PowerConfig pc, BakedTables baked, double before,
                                    boolean frozen, PowerSource source, double baseDelta,
                                    boolean bypassFreeze, int worldIdx, int zoneCtx,
                                    String reason) {
        if (!bypassFreeze && frozen && sourceAffectedByFreeze(pc, source)) {
            return new PowerResult(false, true, before, before, 0.0, "FROZEN");
        }
        if (!sourceEnabled(pc, source)) {
            return new PowerResult(false, false, before, before, 0.0, "SOURCE_DISABLED");
        }
        double delta = sourceAmount(pc, source, baseDelta);
        if (source == PowerSource.DEATH || source == PowerSource.KILL) {
            delta = applyMultipliers(baked, delta, worldIdx, zoneCtx);
        }
        delta = applyEventClamp(pc, source, delta);
        double after = clamp(before + delta, pc.minPower(), pc.maxPower());
        double eff = after - before;
        if (Math.abs(eff) < NO_CHANGE_EPSILON) {
            return new PowerResult(false, false, before, before, 0.0, "NO_CHANGE");
        }
        String reasonCode = (reason != null && !reason.trim().isEmpty())
                ? reason.trim() : source.sourceName();
        return new PowerResult(true, false, before, after, eff, reasonCode);
    }

    // ── Death streak / kill scale ────────────────────────────────────────────────────────

    /**
     * The new death streak: {@code prevStreak + 1} when the previous death was within the streak
     * window, else {@code 0}. When streaks are disabled, always {@code 0}.
     */
    public static int nextStreak(PowerConfig pc, int prevStreak, long lastDeathAt, long nowMillis) {
        if (!pc.deathStreakEnabled()) {
            return 0;
        }
        long windowMs = (long) pc.deathStreakWindowSeconds() * 1000L;
        if (lastDeathAt > 0 && (nowMillis - lastDeathAt) <= windowMs) {
            return prevStreak + 1;
        }
        return 0;
    }

    /** Death loss magnitude (positive): {@code baseLoss * multiplier^streak} when {@code streak>0}. */
    public static double deathLoss(PowerConfig pc, int streak) {
        double loss = pc.lossOnDeath();
        if (streak > 0) {
            loss = loss * Math.pow(pc.deathStreakMultiplier(), streak);
        }
        return loss;
    }

    /** Kill gain magnitude (positive) after F3 scaling by {@code victimBefore/killerPower}. */
    public static double killGain(PowerConfig pc, double victimBefore, double killerPower) {
        double gain = pc.gainOnKillAmount();
        if (pc.killScaleEnabled()) {
            if (killerPower > 0.0) {
                double ratio = victimBefore / killerPower;
                gain = gain * clamp(ratio, pc.killScaleMinFactor(), pc.killScaleMaxFactor());
            } else {
                gain = gain * pc.killScaleMinFactor();
            }
        }
        return gain;
    }

    /** {@code true} while within the post-start death grace window (no loss). */
    public static boolean inGracePeriod(PowerConfig pc, long nowMillis, long referenceStartMillis) {
        return (nowMillis - referenceStartMillis) < (long) pc.gracePeriodSeconds() * 1000L;
    }

    /**
     * Settles a lazy-accrual pair to a concrete value at {@code tick}, identical to
     * {@code KernelSnapshot.powerAt} (the equivalence property test pins the match).
     */
    public static double settle(PowerConfig pc, boolean online, double base, long asOfTick,
                                boolean frozen, int tick) {
        double min = pc.minPower();
        double max = pc.maxPower();
        double clampedBase = clamp(base, min, max);
        if (frozen && pc.freezeBlocksRegen()) {
            return clampedBase;
        }
        boolean srcEnabled = online ? pc.sourceRegenOnlineEnabled() : pc.sourceRegenOfflineEnabled();
        if (!srcEnabled) {
            return clampedBase;
        }
        long dt = (long) tick - asOfTick;
        if (dt <= 0) {
            return clampedBase;
        }
        return clamp(base + perTickRegen(pc, online) * dt, min, max);
    }
}
