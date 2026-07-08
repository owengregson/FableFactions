package dev.fablemc.factions.kernel.state;

import java.util.UUID;

import dev.fablemc.factions.kernel.ids.FactionHandle;

/**
 * A faction — immutable and replaced whole on any faction-scoped change (proposal-C §4.1).
 *
 * <p><b>Owning thread(s):</b> constructed by the writer, published in the snapshot's
 * {@link FactionArena}. <b>Mutability:</b> immutable value; every reachable array is frozen by
 * discipline (AM-8) and mutated only via clone-then-wrap-in-new-record. <b>Reducer rule:</b>
 * created only by the reducer / boot builders.
 *
 * <p>Key derived, incrementally-maintained fields (AM-4): {@link #landCount()},
 * {@link #powerCacheSum()} / {@link #powerCacheTick()} are kept in step by the reducer and
 * reconciled by a low-frequency sweep. {@link #claims()} is the reverse claim index.
 * {@link #tagLegacy()} / {@link #tagMini()} are the chat tag rendered once at mutation time
 * (proposal-C §5d) so the chat hot path never re-parses MiniMessage.
 *
 * <p>Relation arrays ({@code relOut}/{@code relOutKind} declared wishes,
 * {@code relEff}/{@code relEffKind} effective symmetric edges) are sorted ascending by the
 * <b>other faction's ordinal</b>; see {@link RelationEdges}.
 */
public record Faction(
        int idx,
        UUID id,
        String name,
        String nameFolded,
        UUID ownerId,
        String description,
        String motd,
        long createdAt,
        double powerBoost,
        double bank,
        long flagBits,
        int[] relOut,
        byte[] relOutKind,
        int[] relEff,
        byte[] relEffKind,
        Home home,
        int shieldStartHour,
        int shieldDurationHours,
        boolean raidable,
        Rank[] ranks,
        int landCount,
        double powerCacheSum,
        long powerCacheTick,
        FactionClaimList claims,
        String tagLegacy,
        String tagMini) {

    // ── Flag bit layout ──────────────────────────────────────────────────────────────────
    // Low 5 bits (0..4): current flag value. High mirror bits (32..36): "explicitly set"
    // override mask. When a flag's override bit is clear, its effective value comes from the
    // ConfigImage default instead of the stored value bit.
    public static final int FLAG_PVP = 0;
    public static final int FLAG_FRIENDLY_FIRE = 1;
    public static final int FLAG_EXPLOSIONS = 2;
    public static final int FLAG_FIRE_SPREAD = 3;
    public static final int FLAG_OPEN = 4;
    /** Number of defined faction flags. */
    public static final int FLAG_COUNT = 5;
    private static final int OVERRIDE_SHIFT = 32;

    /** No shield configured. */
    public static final int NO_SHIELD = -1;

    /** {@code true} for a normal player faction (not SAFEZONE / WARZONE). */
    public boolean isNormal() {
        return FactionHandle.isNormalOrdinal(idx);
    }

    /** {@code true} for the SAFEZONE sentinel faction (ordinal 0). */
    public boolean isSafezone() {
        return idx == FactionHandle.SAFEZONE_ORDINAL;
    }

    /** {@code true} for the WARZONE sentinel faction (ordinal 1). */
    public boolean isWarzone() {
        return idx == FactionHandle.WARZONE_ORDINAL;
    }

    /** Effective relation kind toward {@code otherOrdinal}; {@link RelationKind#NEUTRAL} default. */
    public byte relationEffective(int otherOrdinal) {
        return RelationEdges.kindOf(relEff, relEffKind, relEff.length, otherOrdinal);
    }

    /** Declared ("wish") relation kind toward {@code otherOrdinal}; NEUTRAL default. */
    public byte relationDeclared(int otherOrdinal) {
        return RelationEdges.kindOf(relOut, relOutKind, relOut.length, otherOrdinal);
    }

    /** {@code true} when this faction has explicitly set the flag (override bit present). */
    public boolean flagOverridden(int flagOrdinal) {
        return (flagBits & (1L << (OVERRIDE_SHIFT + flagOrdinal))) != 0;
    }

    /** The stored flag value bit (meaningful only when {@link #flagOverridden}). */
    public boolean flagStored(int flagOrdinal) {
        return (flagBits & (1L << flagOrdinal)) != 0;
    }

    /**
     * Effective flag value: the stored override when set, else {@code defaultValue} from config.
     */
    public boolean flag(int flagOrdinal, boolean defaultValue) {
        return flagOverridden(flagOrdinal) ? flagStored(flagOrdinal) : defaultValue;
    }

    /** Packs a flag override into {@code bits} (value bit + override bit both set). */
    public static long withFlag(long bits, int flagOrdinal, boolean value) {
        long b = bits | (1L << (OVERRIDE_SHIFT + flagOrdinal));
        if (value) {
            b |= (1L << flagOrdinal);
        } else {
            b &= ~(1L << flagOrdinal);
        }
        return b;
    }

    /** {@code true} when a shield window is configured (regardless of current time-of-day). */
    public boolean hasShield() {
        return shieldStartHour != NO_SHIELD && shieldDurationHours > 0;
    }
}
