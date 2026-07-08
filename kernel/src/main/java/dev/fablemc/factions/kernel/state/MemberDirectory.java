package dev.fablemc.factions.kernel.state;

import java.util.Arrays;
import java.util.UUID;

import dev.fablemc.factions.kernel.ids.ChunkKeys;

/**
 * 256-shard COW open-addressed {@code UUID → member ordinal} directory (AM-2).
 *
 * <p><b>Owning thread(s):</b> {@link #get} on any reader thread; {@code with*} mutators on the
 * writer only. <b>Mutability:</b> immutable, COW. <b>Reducer rule:</b> only the reducer changes
 * mappings, and a membership change copies exactly one shard's arrays plus the 256-entry shard
 * pointer array — never the whole directory.
 *
 * <p>Sharding is by a mixed hash of the UUID's 128 bits into {@code [0,256)}; each shard is a
 * frozen open-addressed table ({@code long[] msb / long[] lsb / int[] ordinal}, load ≤ 0.6) with
 * an {@code ordinal == -1} empty marker. {@link #get} extracts the UUID's msb/lsb without
 * allocating and probes ~1–2 slots (proposal-C §5b relation path).
 */
public final class MemberDirectory {

    /** Returned by {@link #get} when the UUID is unknown. */
    public static final int ABSENT = -1;

    private static final int SHARD_COUNT = 256;
    private static final MemberDirectory EMPTY = new MemberDirectory(new Shard[SHARD_COUNT], 0);

    private final Shard[] shards; // shards[i] == null ⇒ empty shard
    private final int size;

    private MemberDirectory(Shard[] shards, int size) {
        this.shards = shards;
        this.size = size;
    }

    /** The shared empty directory. */
    public static MemberDirectory empty() {
        return EMPTY;
    }

    /** Number of known members. */
    public int size() {
        return size;
    }

    /** Member ordinal for {@code id}, or {@link #ABSENT}. Zero allocation. */
    public int get(UUID id) {
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        Shard s = shards[shardIndex(msb, lsb)];
        if (s == null) {
            return ABSENT;
        }
        return s.get(msb, lsb);
    }

    /** {@code true} when {@code id} has a mapping. */
    public boolean contains(UUID id) {
        return get(id) != ABSENT;
    }

    /** Returns a copy mapping {@code id → ordinal} (insert or overwrite). */
    public MemberDirectory withMapping(UUID id, int ordinal) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("member ordinal must be >= 0: " + ordinal);
        }
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        int si = shardIndex(msb, lsb);
        Shard s = shards[si];
        if (s == null) {
            s = Shard.EMPTY;
        }
        boolean existed = s.get(msb, lsb) != ABSENT;
        Shard s2 = s.withPut(msb, lsb, ordinal);
        Shard[] ns = Arrays.copyOf(shards, shards.length);
        ns[si] = s2;
        return new MemberDirectory(ns, existed ? size : size + 1);
    }

    /** Returns a copy with any mapping for {@code id} removed (no-op if absent). */
    public MemberDirectory withoutMapping(UUID id) {
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        int si = shardIndex(msb, lsb);
        Shard s = shards[si];
        if (s == null || s.get(msb, lsb) == ABSENT) {
            return this;
        }
        Shard s2 = s.withRemove(msb, lsb);
        Shard[] ns = Arrays.copyOf(shards, shards.length);
        ns[si] = s2.isEmpty() ? null : s2;
        return new MemberDirectory(ns, size - 1);
    }

    private static int shardIndex(long msb, long lsb) {
        return (int) (ChunkKeys.mix(msb ^ lsb) & (SHARD_COUNT - 1));
    }

    // ── Shard: frozen open-addressed 128-bit-key → ordinal ────────────────────────────────

    private static final class Shard {
        private static final double MAX_LOAD = 0.6;
        static final Shard EMPTY = new Shard(new long[2], new long[2], filled(2), 0);

        private final long[] msb;
        private final long[] lsb;
        private final int[] ordinal; // -1 ⇒ empty slot
        private final int size;
        private final int mask;

        private Shard(long[] msb, long[] lsb, int[] ordinal, int size) {
            this.msb = msb;
            this.lsb = lsb;
            this.ordinal = ordinal;
            this.size = size;
            this.mask = msb.length - 1;
        }

        boolean isEmpty() {
            return size == 0;
        }

        int get(long keyMsb, long keyLsb) {
            int i = (int) (ChunkKeys.mix(keyMsb ^ (keyLsb * 0x9E3779B97F4A7C15L)) & mask);
            while (true) {
                int o = ordinal[i];
                if (o == ABSENT) {
                    return ABSENT;
                }
                if (msb[i] == keyMsb && lsb[i] == keyLsb) {
                    return o;
                }
                i = (i + 1) & mask;
            }
        }

        Shard withPut(long keyMsb, long keyLsb, int ord) {
            boolean existed = get(keyMsb, keyLsb) != ABSENT;
            int newSize = existed ? size : size + 1;
            Shard t = allocFor(newSize);
            t.copyFrom(this, keyMsb, keyLsb);
            t.insertRaw(keyMsb, keyLsb, ord);
            return t;
        }

        Shard withRemove(long keyMsb, long keyLsb) {
            if (get(keyMsb, keyLsb) == ABSENT) {
                return this;
            }
            int newSize = size - 1;
            if (newSize == 0) {
                return EMPTY;
            }
            Shard t = allocFor(newSize);
            t.copyFrom(this, keyMsb, keyLsb); // copy all but the removed key
            return t;
        }

        private void copyFrom(Shard src, long skipMsb, long skipLsb) {
            for (int i = 0; i < src.ordinal.length; i++) {
                int o = src.ordinal[i];
                if (o != ABSENT && !(src.msb[i] == skipMsb && src.lsb[i] == skipLsb)) {
                    insertRaw(src.msb[i], src.lsb[i], o);
                }
            }
        }

        private void insertRaw(long keyMsb, long keyLsb, int ord) {
            int i = (int) (ChunkKeys.mix(keyMsb ^ (keyLsb * 0x9E3779B97F4A7C15L)) & mask);
            while (ordinal[i] != ABSENT) {
                if (msb[i] == keyMsb && lsb[i] == keyLsb) {
                    ordinal[i] = ord;
                    return;
                }
                i = (i + 1) & mask;
            }
            msb[i] = keyMsb;
            lsb[i] = keyLsb;
            ordinal[i] = ord;
        }

        private static Shard allocFor(int liveEntries) {
            int cap = capacityFor(liveEntries);
            return new Shard(new long[cap], new long[cap], filled(cap), liveEntries);
        }

        private static int capacityFor(int liveEntries) {
            int min = (int) Math.ceil(liveEntries / MAX_LOAD) + 1;
            int cap = 2;
            while (cap < min) {
                cap <<= 1;
            }
            return cap;
        }

        private static int[] filled(int cap) {
            int[] a = new int[cap];
            Arrays.fill(a, ABSENT);
            return a;
        }
    }
}
