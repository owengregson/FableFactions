package dev.fablemc.factions.kernel.config;

import java.util.Arrays;

/**
 * Derived hot-lookup tables baked at config parse time (AM-14), published atomically inside the
 * snapshot as part of {@link ConfigImage}.
 *
 * <p><b>Owning thread(s):</b> built single-threaded at parse (in {@code :core}) via the
 * {@link Builder}; read on any thread. <b>Mutability:</b> immutable value (frozen arrays).
 * <b>Reducer rule:</b> re-baked and swapped whole on {@code SwapConfig}.
 *
 * <p>Contents:
 * <ul>
 *   <li>{@code containerMaterials} / {@code interactableMaterials} — {@code long[]} bitsets
 *       indexed by an interned material ordinal (fed at parse), for the interact/container
 *       protection checks with no map lookup or boxing;</li>
 *   <li>{@code worldPowerMultipliers} — {@code double[]} by {@code worldIdx};</li>
 *   <li>{@code zonePowerMultipliers} — {@code double[5]} by ZoneContext ordinal
 *       (SAFEZONE, WARZONE, OWN_CLAIMED, ENEMY_CLAIMED, WILDERNESS);</li>
 *   <li>{@code zoneFlagTable} — a {@code long[]} indexed by protection action, reserved for the
 *       {@code Verdicts} baker to encode per-zone default verdict bits (populated by the rules
 *       wave; zero-initialized here).</li>
 * </ul>
 * All zero-argument accessors are range-safe: an out-of-range material ordinal reads
 * {@code false}, an out-of-range world reads multiplier {@code 1.0}.
 */
public record BakedTables(
        long[] containerMaterials,
        long[] interactableMaterials,
        double[] worldPowerMultipliers,
        double[] zonePowerMultipliers,
        long[] zoneFlagTable) {

    /** Number of ZoneContext values (SAFEZONE, WARZONE, OWN_CLAIMED, ENEMY_CLAIMED, WILDERNESS). */
    public static final int ZONE_COUNT = 5;

    /** {@code true} when the material ordinal is a container (chest/furnace/…). */
    public boolean isContainer(int materialOrdinal) {
        return testBit(containerMaterials, materialOrdinal);
    }

    /** {@code true} when the material ordinal is an interactable (door/lever/button/…). */
    public boolean isInteractable(int materialOrdinal) {
        return testBit(interactableMaterials, materialOrdinal);
    }

    /** Death/kill power multiplier for {@code worldIdx} (1.0 when unknown). */
    public double worldMultiplier(int worldIdx) {
        if (worldIdx < 0 || worldIdx >= worldPowerMultipliers.length) {
            return 1.0;
        }
        return worldPowerMultipliers[worldIdx];
    }

    /** Death/kill power multiplier for a ZoneContext ordinal (1.0 when out of range). */
    public double zoneMultiplier(int zoneOrdinal) {
        if (zoneOrdinal < 0 || zoneOrdinal >= zonePowerMultipliers.length) {
            return 1.0;
        }
        return zonePowerMultipliers[zoneOrdinal];
    }

    /** Raw baked verdict word for a protection {@code action} (0 when out of range). */
    public long zoneFlagWord(int action) {
        if (action < 0 || action >= zoneFlagTable.length) {
            return 0L;
        }
        return zoneFlagTable[action];
    }

    private static boolean testBit(long[] bits, int ordinal) {
        if (ordinal < 0) {
            return false;
        }
        int w = ordinal >>> 6;
        if (w >= bits.length) {
            return false;
        }
        return (bits[w] & (1L << (ordinal & 63))) != 0;
    }

    /** Default baked tables: empty material bitsets, unit multipliers, zero verdict table. */
    public static BakedTables defaults(PowerConfig power) {
        double[] zones = new double[] {
                power.zoneMultipliers().safezone(),
                power.zoneMultipliers().warzone(),
                power.zoneMultipliers().ownClaimed(),
                power.zoneMultipliers().enemyClaimed(),
                power.zoneMultipliers().wilderness()
        };
        return new BakedTables(new long[0], new long[0], new double[0], zones, new long[16]);
    }

    /**
     * Single-threaded parse-time builder (fed by the {@code :core} config parser, which owns the
     * interned material-ordinal registry).
     */
    public static final class Builder {
        private long[] container = new long[0];
        private long[] interactable = new long[0];
        private double[] worldMultipliers = new double[0];
        private double[] zoneMultipliers = new double[ZONE_COUNT];
        private long[] zoneFlags = new long[16];

        public Builder() {
            Arrays.fill(zoneMultipliers, 1.0);
        }

        /** Marks a material ordinal as a container. */
        public Builder container(int materialOrdinal) {
            container = setBit(container, materialOrdinal);
            return this;
        }

        /** Marks a material ordinal as interactable. */
        public Builder interactable(int materialOrdinal) {
            interactable = setBit(interactable, materialOrdinal);
            return this;
        }

        /** Sets the death/kill multiplier for {@code worldIdx}. */
        public Builder worldMultiplier(int worldIdx, double multiplier) {
            if (worldIdx >= worldMultipliers.length) {
                int len = worldMultipliers.length;
                worldMultipliers = Arrays.copyOf(worldMultipliers, worldIdx + 1);
                Arrays.fill(worldMultipliers, len, worldMultipliers.length, 1.0);
            }
            worldMultipliers[worldIdx] = multiplier;
            return this;
        }

        /** Sets the death/kill multiplier for a ZoneContext ordinal. */
        public Builder zoneMultiplier(int zoneOrdinal, double multiplier) {
            if (zoneOrdinal >= 0 && zoneOrdinal < zoneMultipliers.length) {
                zoneMultipliers[zoneOrdinal] = multiplier;
            }
            return this;
        }

        /** Sets the raw baked verdict word for a protection action (Verdicts baker). */
        public Builder zoneFlagWord(int action, long word) {
            if (action >= zoneFlags.length) {
                zoneFlags = Arrays.copyOf(zoneFlags, Math.max(zoneFlags.length << 1, action + 1));
            }
            zoneFlags[action] = word;
            return this;
        }

        /** Freezes into an immutable {@link BakedTables}. */
        public BakedTables build() {
            return new BakedTables(container, interactable, worldMultipliers,
                    Arrays.copyOf(zoneMultipliers, zoneMultipliers.length), zoneFlags);
        }

        private static long[] setBit(long[] bits, int ordinal) {
            if (ordinal < 0) {
                return bits;
            }
            int w = ordinal >>> 6;
            if (w >= bits.length) {
                bits = Arrays.copyOf(bits, w + 1);
            }
            bits[w] |= (1L << (ordinal & 63));
            return bits;
        }
    }
}
