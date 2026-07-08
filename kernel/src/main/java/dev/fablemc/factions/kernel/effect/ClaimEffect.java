package dev.fablemc.factions.kernel.effect;

import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Claim and zone effects: claim set/remove and zone set/remove.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * {@code zoneOrdinal} stays a raw faction ordinal (hot-path atlas key). See {@link Effect} for the
 * hierarchy contract.
 */
public sealed interface ClaimEffect extends Effect
        permits ClaimEffect.ClaimSet, ClaimEffect.ClaimRemoved, ClaimEffect.ZoneSet,
        ClaimEffect.ZoneRemoved {

    /** {@code prevOwner} is {@code -1} (wilderness) or the overclaimed victim's handle. */
    record ClaimSet(long seq, Origin origin, int worldIdx, long key, int faction, int prevOwner)
            implements ClaimEffect {
    }

    record ClaimRemoved(long seq, Origin origin, int worldIdx, long key, int prevOwner)
            implements ClaimEffect {
    }

    record ZoneSet(long seq, Origin origin, int zoneOrdinal, int worldIdx, long key, int prevOwner)
            implements ClaimEffect {
    }

    record ZoneRemoved(long seq, Origin origin, int zoneOrdinal, int worldIdx, long key)
            implements ClaimEffect {
    }
}
