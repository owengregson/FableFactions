package dev.fablemc.factions.kernel.state;

import java.util.UUID;

/**
 * Flyweight read accessor over one {@link PlayerLedger} member row.
 *
 * <p><b>Owning thread(s):</b> any reader thread (backed by an immutable published ledger).
 * <b>Mutability:</b> immutable view — <b>never stored</b> by readers (take it, read it,
 * discard it); it pins nothing beyond the already-immutable ledger. <b>Reducer rule:</b> the
 * ledger it views is replaced whole on any member mutation.
 *
 * <p>Power is stored as a lazy-accrual pair {@code (powerBase, powerAsOfTick)} (proposal-C
 * §4.5); use {@code KernelSnapshot.powerAt(ordinal, tick)} to settle it to a concrete value.
 */
public interface MemberView {

    /** The member's UUID. */
    UUID uuid();

    /** Last-seen name (may be a legacy import placeholder). */
    String nameLast();

    /** Current faction handle, or {@link dev.fablemc.factions.kernel.ids.FactionHandle#WILDERNESS}. */
    int factionHandle();

    /** Index into the faction's rank array (0 = lowest). */
    int rankIdx();

    /** Settled power at {@link #powerAsOfTick()}. */
    double powerBase();

    /** Power-tick at which {@link #powerBase()} was last settled. */
    long powerAsOfTick();

    /** {@code true} when accrual is frozen (admin freeze). */
    boolean powerFrozen();

    /** Last activity epoch millis (0 = never / unknown). */
    long lastActivity();

    /** Last death epoch millis (0 = never). */
    long lastDeathAt();

    /** Consecutive-death streak counter (F2). */
    int deathStreak();

    /** Packed per-player preference bits (see {@link PlayerLedger}). */
    int prefsBits();

    /** Interned locale index. */
    byte localeIdx();

    /** Faction-join epoch millis. */
    long joinedAt();

    /** Per-player additive power boost. */
    double powerBoost();
}
