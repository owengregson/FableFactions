package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

import dev.fablemc.factions.kernel.vocab.ClaimMode;

/**
 * Claim and zone intents: player/admin claim and unclaim (unclaim-all is paged), plus the system
 * safezone/warzone assignment vocabulary (zone-set is paged).
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. {@code zoneOrdinal} stays a raw faction ordinal (the
 * atlas keys zones by ordinal on the hot path; see {@link dev.fablemc.factions.kernel.vocab.ZoneKind}
 * for the cold-path companion). See {@link Intent} for the hierarchy contract.
 */
public sealed interface ClaimIntent extends Intent
        permits ClaimIntent.ClaimChunks, ClaimIntent.UnclaimChunks, ClaimIntent.UnclaimAll,
        ClaimIntent.UnclaimAllPage, ClaimIntent.AdminClaimChunks, ClaimIntent.AdminUnclaimChunks,
        ClaimIntent.SetZoneChunks, ClaimIntent.RemoveZoneChunk, ClaimIntent.ZonePage {

    /** Claim {@code keys} in {@code worldIdx} for {@code faction}; {@code mode} is the claim shape. */
    record ClaimChunks(UUID player, int faction, int worldIdx, long[] keys, ClaimMode mode)
            implements ClaimIntent {
    }

    /** Unclaim {@code keys} in {@code worldIdx} for {@code faction}. */
    record UnclaimChunks(UUID player, int faction, int worldIdx, long[] keys)
            implements ClaimIntent {
    }

    /** Unclaim all of {@code faction}'s land. Paged (AM-5). */
    record UnclaimAll(UUID actor, int faction) implements ClaimIntent {
    }

    /** An unclaim-all page for {@code faction} at {@code cursor}. */
    record UnclaimAllPage(int faction, int cursor, UUID actor) implements ClaimIntent {
    }

    /** Admin-claim unclaimed {@code keys} for {@code faction}. */
    record AdminClaimChunks(int faction, int worldIdx, long[] keys, UUID actor)
            implements ClaimIntent {
    }

    /** Admin-unclaim {@code keys} owned by {@code faction}. */
    record AdminUnclaimChunks(int faction, int worldIdx, long[] keys, UUID actor)
            implements ClaimIntent {
    }

    /** Assign {@code keys} to a system zone ({@code zoneOrdinal} = SAFEZONE=0 / WARZONE=1). Paged (AM-5). */
    record SetZoneChunks(int zoneOrdinal, int worldIdx, long[] keys, UUID actor)
            implements ClaimIntent {
    }

    /** Remove one chunk from a system zone ({@code zoneOrdinal} is a faction ordinal). */
    record RemoveZoneChunk(int zoneOrdinal, int worldIdx, long key, UUID actor)
            implements ClaimIntent {
    }

    /** A zone-assignment page for {@code keys[cursor..]} ({@code zoneOrdinal} is a faction ordinal). */
    record ZonePage(int zoneOrdinal, int worldIdx, long[] keys, int cursor, UUID actor)
            implements ClaimIntent {
    }
}
