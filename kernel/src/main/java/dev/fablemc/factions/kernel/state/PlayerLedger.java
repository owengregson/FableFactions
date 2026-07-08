package dev.fablemc.factions.kernel.state;

import java.util.Arrays;
import java.util.UUID;

import dev.fablemc.factions.kernel.ids.FactionHandle;

/**
 * Member hot fields as a 256-per-shard COW structure-of-arrays (AM-2), the fix for a
 * per-death whole-arena copy.
 *
 * <p><b>Owning thread(s):</b> primitive getters and {@link #view} on any reader thread; every
 * {@code with*} mutator on the writer only. <b>Mutability:</b> immutable, COW. <b>Reducer
 * rule:</b> only the reducer changes member fields; a change clones the ONE touched shard's
 * column arrays (~4–32 KB) plus the small shard-pointer array — never the whole ledger.
 *
 * <p>Member ordinals map to a shard by {@code ord >>> 8} and to a slot by {@code ord & 0xFF}
 * (256 members per shard). Power is stored as a lazy-accrual pair
 * {@code (powerBase, powerAsOfTick)} (proposal-C §4.5) — settle it with
 * {@code KernelSnapshot.powerAt}. Ordinals are handed out by {@link #nextOrdinal()} (free-list
 * top, else high-water); an evicted member's ordinal is reclaimed.
 */
public final class PlayerLedger {

    // ── Preference bit layout (prefsBits) ────────────────────────────────────────────────
    public static final int PREF_NOTIFY_STATUS = 0;
    public static final int PREF_NOTIFY_INVITES = 1;
    public static final int PREF_NOTIFY_TERRITORY = 2;
    public static final int PREF_NOTIFY_TAX = 3;
    public static final int PREF_TERRITORY_TITLES = 4;
    public static final int PREF_OVERRIDING = 7;
    public static final int PREF_FLY = 8;
    private static final int AUTO_MODE_SHIFT = 5; // 2-bit field: 0=off, 1=claim, 2=unclaim
    private static final int AUTO_MODE_MASK = 0b11 << AUTO_MODE_SHIFT;
    public static final int AUTO_MODE_OFF = 0;
    public static final int AUTO_MODE_CLAIM = 1;
    public static final int AUTO_MODE_UNCLAIM = 2;

    /** Default prefs for a fresh member: notify + territory titles on, no auto-mode, not flying. */
    public static final int DEFAULT_PREFS =
            (1 << PREF_NOTIFY_STATUS) | (1 << PREF_NOTIFY_INVITES)
                    | (1 << PREF_NOTIFY_TERRITORY) | (1 << PREF_NOTIFY_TAX)
                    | (1 << PREF_TERRITORY_TITLES);

    private static final int SHARD_BITS = 8;
    private static final int SLOTS_PER_SHARD = 1 << SHARD_BITS; // 256
    private static final int SLOT_MASK = SLOTS_PER_SHARD - 1;

    private static final PlayerLedger EMPTY = new PlayerLedger(new LedgerShard[0], 0, new int[0], 0, 0);

    private final LedgerShard[] shards; // shards[i] may be null (no members in that band)
    private final int highWater;        // next never-used ordinal
    private final int[] freeList;
    private final int freeCount;
    private final int liveCount;

    private PlayerLedger(LedgerShard[] shards, int highWater, int[] freeList, int freeCount,
                         int liveCount) {
        this.shards = shards;
        this.highWater = highWater;
        this.freeList = freeList;
        this.freeCount = freeCount;
        this.liveCount = liveCount;
    }

    /** The shared empty ledger. */
    public static PlayerLedger empty() {
        return EMPTY;
    }

    /** Number of live member rows. */
    public int size() {
        return liveCount;
    }

    /** One past the highest ordinal ever handed out. */
    public int highWater() {
        return highWater;
    }

    /** The ordinal the next {@link #withNewMember} will use (free-list top, else high-water). */
    public int nextOrdinal() {
        return freeCount > 0 ? freeList[freeCount - 1] : highWater;
    }

    /** {@code true} when {@code ord} names a live member row. */
    public boolean has(int ord) {
        if (ord < 0) {
            return false;
        }
        LedgerShard s = shardOf(ord);
        return s != null && s.occupied[ord & SLOT_MASK];
    }

    // ── primitive getters (zero allocation) ──────────────────────────────────────────────

    public int factionHandle(int ord) {
        return shardOf(ord).factionHandle[ord & SLOT_MASK];
    }

    public int rankIdx(int ord) {
        return shardOf(ord).rankIdx[ord & SLOT_MASK];
    }

    public double powerBase(int ord) {
        return shardOf(ord).powerBase[ord & SLOT_MASK];
    }

    public long powerAsOfTick(int ord) {
        return shardOf(ord).powerAsOfTick[ord & SLOT_MASK];
    }

    public boolean powerFrozen(int ord) {
        return shardOf(ord).powerFrozen[ord & SLOT_MASK];
    }

    public long lastActivity(int ord) {
        return shardOf(ord).lastActivity[ord & SLOT_MASK];
    }

    public long lastDeathAt(int ord) {
        return shardOf(ord).lastDeathAt[ord & SLOT_MASK];
    }

    public int deathStreak(int ord) {
        return shardOf(ord).deathStreak[ord & SLOT_MASK] & 0xFF;
    }

    public int prefsBits(int ord) {
        return shardOf(ord).prefsBits[ord & SLOT_MASK];
    }

    public byte localeIdx(int ord) {
        return shardOf(ord).localeIdx[ord & SLOT_MASK];
    }

    public long joinedAt(int ord) {
        return shardOf(ord).joinedAt[ord & SLOT_MASK];
    }

    public double powerBoost(int ord) {
        return shardOf(ord).powerBoost[ord & SLOT_MASK];
    }

    public UUID uuid(int ord) {
        LedgerShard s = shardOf(ord);
        int slot = ord & SLOT_MASK;
        return new UUID(s.uuidMsb[slot], s.uuidLsb[slot]);
    }

    public String nameLast(int ord) {
        return shardOf(ord).nameLast[ord & SLOT_MASK];
    }

    /** Flyweight view of {@code ord}, or {@code null} if absent. Never store the result. */
    public MemberView view(int ord) {
        return has(ord) ? new View(this, ord) : null;
    }

    // ── COW mutators ─────────────────────────────────────────────────────────────────────

    /**
     * Creates a member row at {@code ord} (which must be {@link #nextOrdinal()}), with default
     * power/prefs, factionless. {@code ord} is consumed from the free-list or extends high-water.
     */
    public PlayerLedger withNewMember(int ord, UUID id, String name) {
        if (ord != nextOrdinal()) {
            throw new IllegalArgumentException("ord (" + ord + ") must equal nextOrdinal()");
        }
        int shardIdx = ord >>> SHARD_BITS;
        int slot = ord & SLOT_MASK;
        LedgerShard[] ns = ensureShards(shards, shardIdx);
        LedgerShard s = ns[shardIdx];
        s = (s == null) ? LedgerShard.blank() : s.cloneAll();
        s.occupied[slot] = true;
        s.uuidMsb[slot] = id.getMostSignificantBits();
        s.uuidLsb[slot] = id.getLeastSignificantBits();
        s.nameLast[slot] = name;
        s.factionHandle[slot] = FactionHandle.WILDERNESS;
        s.rankIdx[slot] = 0;
        s.powerBase[slot] = 0.0;
        s.powerAsOfTick[slot] = 0L;
        s.powerFrozen[slot] = false;
        s.lastActivity[slot] = 0L;
        s.lastDeathAt[slot] = 0L;
        s.joinedAt[slot] = 0L;
        s.deathStreak[slot] = 0;
        s.prefsBits[slot] = DEFAULT_PREFS;
        s.localeIdx[slot] = 0;
        s.powerBoost[slot] = 0.0;
        ns[shardIdx] = s;

        int newFreeCount = freeCount;
        int[] newFree = freeList;
        int newHigh = highWater;
        if (freeCount > 0 && freeList[freeCount - 1] == ord) {
            newFree = Arrays.copyOf(freeList, freeList.length);
            newFreeCount = freeCount - 1;
        } else {
            newHigh = ord + 1;
        }
        return new PlayerLedger(ns, newHigh, newFree, newFreeCount, liveCount + 1);
    }

    public PlayerLedger withFactionHandle(int ord, int handle) {
        return mutate(ord, s -> s.factionHandle[ord & SLOT_MASK] = handle);
    }

    public PlayerLedger withRankIdx(int ord, int rankIdx) {
        return mutate(ord, s -> s.rankIdx[ord & SLOT_MASK] = (short) rankIdx);
    }

    public PlayerLedger withPower(int ord, double base, long asOfTick) {
        return mutate(ord, s -> {
            int slot = ord & SLOT_MASK;
            s.powerBase[slot] = base;
            s.powerAsOfTick[slot] = asOfTick;
        });
    }

    public PlayerLedger withPowerFrozen(int ord, boolean frozen) {
        return mutate(ord, s -> s.powerFrozen[ord & SLOT_MASK] = frozen);
    }

    public PlayerLedger withLastActivity(int ord, long millis) {
        return mutate(ord, s -> s.lastActivity[ord & SLOT_MASK] = millis);
    }

    public PlayerLedger withDeath(int ord, int streak, long atMillis) {
        return mutate(ord, s -> {
            int slot = ord & SLOT_MASK;
            s.deathStreak[slot] = (byte) Math.max(0, Math.min(255, streak));
            s.lastDeathAt[slot] = atMillis;
        });
    }

    public PlayerLedger withPrefsBits(int ord, int prefsBits) {
        return mutate(ord, s -> s.prefsBits[ord & SLOT_MASK] = prefsBits);
    }

    public PlayerLedger withLocaleIdx(int ord, int localeIdx) {
        return mutate(ord, s -> s.localeIdx[ord & SLOT_MASK] = (byte) localeIdx);
    }

    public PlayerLedger withPowerBoost(int ord, double boost) {
        return mutate(ord, s -> s.powerBoost[ord & SLOT_MASK] = boost);
    }

    public PlayerLedger withNameLast(int ord, String name) {
        return mutate(ord, s -> s.nameLast[ord & SLOT_MASK] = name);
    }

    public PlayerLedger withJoinedAt(int ord, long millis) {
        return mutate(ord, s -> s.joinedAt[ord & SLOT_MASK] = millis);
    }

    /** Evicts member {@code ord}, reclaiming its ordinal (no-op if absent). */
    public PlayerLedger without(int ord) {
        if (!has(ord)) {
            return this;
        }
        int shardIdx = ord >>> SHARD_BITS;
        int slot = ord & SLOT_MASK;
        LedgerShard[] ns = Arrays.copyOf(shards, shards.length);
        LedgerShard s = ns[shardIdx].cloneAll();
        s.occupied[slot] = false;
        s.nameLast[slot] = null;
        ns[shardIdx] = s;
        int[] newFree = ensureIntCapacity(freeList, freeCount + 1);
        newFree[freeCount] = ord;
        return new PlayerLedger(ns, highWater, newFree, freeCount + 1, liveCount - 1);
    }

    // ── preference helpers ───────────────────────────────────────────────────────────────

    public static boolean pref(int prefsBits, int bit) {
        return (prefsBits & (1 << bit)) != 0;
    }

    public static int withPref(int prefsBits, int bit, boolean on) {
        return on ? (prefsBits | (1 << bit)) : (prefsBits & ~(1 << bit));
    }

    public static int autoMode(int prefsBits) {
        return (prefsBits & AUTO_MODE_MASK) >>> AUTO_MODE_SHIFT;
    }

    public static int withAutoMode(int prefsBits, int mode) {
        return (prefsBits & ~AUTO_MODE_MASK) | ((mode & 0b11) << AUTO_MODE_SHIFT);
    }

    // ── internals ────────────────────────────────────────────────────────────────────────

    private LedgerShard shardOf(int ord) {
        int shardIdx = ord >>> SHARD_BITS;
        if (shardIdx < 0 || shardIdx >= shards.length) {
            return null;
        }
        return shards[shardIdx];
    }

    private interface SlotMutation {
        void apply(LedgerShard shard);
    }

    private PlayerLedger mutate(int ord, SlotMutation m) {
        if (!has(ord)) {
            throw new IllegalStateException("no member at ordinal " + ord);
        }
        int shardIdx = ord >>> SHARD_BITS;
        LedgerShard[] ns = Arrays.copyOf(shards, shards.length);
        LedgerShard s = ns[shardIdx].cloneAll();
        m.apply(s);
        ns[shardIdx] = s;
        return new PlayerLedger(ns, highWater, freeList, freeCount, liveCount);
    }

    private static LedgerShard[] ensureShards(LedgerShard[] a, int shardIdx) {
        if (shardIdx < a.length) {
            return Arrays.copyOf(a, a.length);
        }
        int cap = Math.max(a.length, 1);
        while (cap <= shardIdx) {
            cap <<= 1;
        }
        return Arrays.copyOf(a, cap);
    }

    private static int[] ensureIntCapacity(int[] a, int need) {
        if (need <= a.length) {
            return Arrays.copyOf(a, Math.max(a.length, 1));
        }
        int cap = Math.max(a.length, 1);
        while (cap < need) {
            cap <<= 1;
        }
        return Arrays.copyOf(a, cap);
    }

    /** Test-only: the shard object backing a band, for COW identity-sharing assertions. */
    LedgerShard shardRef(int shardIndex) {
        return (shardIndex >= 0 && shardIndex < shards.length) ? shards[shardIndex] : null;
    }

    /** One 256-member band of parallel primitive columns (package-private, mutated only before publish). */
    static final class LedgerShard {
        final boolean[] occupied = new boolean[SLOTS_PER_SHARD];
        final long[] uuidMsb = new long[SLOTS_PER_SHARD];
        final long[] uuidLsb = new long[SLOTS_PER_SHARD];
        final String[] nameLast = new String[SLOTS_PER_SHARD];
        final int[] factionHandle = new int[SLOTS_PER_SHARD];
        final short[] rankIdx = new short[SLOTS_PER_SHARD];
        final double[] powerBase = new double[SLOTS_PER_SHARD];
        final long[] powerAsOfTick = new long[SLOTS_PER_SHARD];
        final boolean[] powerFrozen = new boolean[SLOTS_PER_SHARD];
        final long[] lastActivity = new long[SLOTS_PER_SHARD];
        final long[] lastDeathAt = new long[SLOTS_PER_SHARD];
        final long[] joinedAt = new long[SLOTS_PER_SHARD];
        final byte[] deathStreak = new byte[SLOTS_PER_SHARD];
        final int[] prefsBits = new int[SLOTS_PER_SHARD];
        final byte[] localeIdx = new byte[SLOTS_PER_SHARD];
        final double[] powerBoost = new double[SLOTS_PER_SHARD];

        private LedgerShard() {
        }

        static LedgerShard blank() {
            LedgerShard s = new LedgerShard();
            Arrays.fill(s.factionHandle, FactionHandle.WILDERNESS);
            return s;
        }

        LedgerShard cloneAll() {
            LedgerShard c = new LedgerShard();
            System.arraycopy(occupied, 0, c.occupied, 0, SLOTS_PER_SHARD);
            System.arraycopy(uuidMsb, 0, c.uuidMsb, 0, SLOTS_PER_SHARD);
            System.arraycopy(uuidLsb, 0, c.uuidLsb, 0, SLOTS_PER_SHARD);
            System.arraycopy(nameLast, 0, c.nameLast, 0, SLOTS_PER_SHARD);
            System.arraycopy(factionHandle, 0, c.factionHandle, 0, SLOTS_PER_SHARD);
            System.arraycopy(rankIdx, 0, c.rankIdx, 0, SLOTS_PER_SHARD);
            System.arraycopy(powerBase, 0, c.powerBase, 0, SLOTS_PER_SHARD);
            System.arraycopy(powerAsOfTick, 0, c.powerAsOfTick, 0, SLOTS_PER_SHARD);
            System.arraycopy(powerFrozen, 0, c.powerFrozen, 0, SLOTS_PER_SHARD);
            System.arraycopy(lastActivity, 0, c.lastActivity, 0, SLOTS_PER_SHARD);
            System.arraycopy(lastDeathAt, 0, c.lastDeathAt, 0, SLOTS_PER_SHARD);
            System.arraycopy(joinedAt, 0, c.joinedAt, 0, SLOTS_PER_SHARD);
            System.arraycopy(deathStreak, 0, c.deathStreak, 0, SLOTS_PER_SHARD);
            System.arraycopy(prefsBits, 0, c.prefsBits, 0, SLOTS_PER_SHARD);
            System.arraycopy(localeIdx, 0, c.localeIdx, 0, SLOTS_PER_SHARD);
            System.arraycopy(powerBoost, 0, c.powerBoost, 0, SLOTS_PER_SHARD);
            return c;
        }
    }

    /** Immutable flyweight over a member row. */
    private record View(PlayerLedger ledger, int ord) implements MemberView {
        @Override public UUID uuid() { return ledger.uuid(ord); }
        @Override public String nameLast() { return ledger.nameLast(ord); }
        @Override public int factionHandle() { return ledger.factionHandle(ord); }
        @Override public int rankIdx() { return ledger.rankIdx(ord); }
        @Override public double powerBase() { return ledger.powerBase(ord); }
        @Override public long powerAsOfTick() { return ledger.powerAsOfTick(ord); }
        @Override public boolean powerFrozen() { return ledger.powerFrozen(ord); }
        @Override public long lastActivity() { return ledger.lastActivity(ord); }
        @Override public long lastDeathAt() { return ledger.lastDeathAt(ord); }
        @Override public int deathStreak() { return ledger.deathStreak(ord); }
        @Override public int prefsBits() { return ledger.prefsBits(ord); }
        @Override public byte localeIdx() { return ledger.localeIdx(ord); }
        @Override public long joinedAt() { return ledger.joinedAt(ord); }
        @Override public double powerBoost() { return ledger.powerBoost(ord); }
    }
}
