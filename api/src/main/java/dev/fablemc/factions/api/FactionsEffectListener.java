package dev.fablemc.factions.api;

import java.util.UUID;

/**
 * A read-model subscriber notified, on the global thread, of committed domain changes as the
 * writer publishes them (proposal-C §10.1). Use it to maintain an external projection (a
 * leaderboard, a web map, a cache) driven by the authoritative effect stream.
 *
 * <p><b>Owning thread(s):</b> every callback fires on the global/main thread from the
 * {@code :core} effect bridge, in commit order. <b>Mutability:</b> the listener implementation
 * owns its own state; callbacks must be non-blocking (never do IO on the calling thread).
 *
 * <p>Every method is a default no-op so implementers override only what they need. All
 * parameters are API-native (no kernel type — CONTRACTS §5); factions are identified by
 * {@link UUID}. This is deliberately coarser than the internal effect vocabulary: it surfaces
 * the commit-level facts external consumers care about, not every internal delta.
 */
public interface FactionsEffectListener {

    /** A faction was created. */
    default void onFactionCreated(UUID factionId, String name) {
    }

    /** A faction was disbanded. */
    default void onFactionDisbanded(UUID factionId, String name) {
    }

    /** A player joined a faction. */
    default void onMemberJoined(UUID factionId, UUID player) {
    }

    /** A player left a faction ({@code kicked} distinguishes a kick from a voluntary leave). */
    default void onMemberLeft(UUID factionId, UUID player, boolean kicked) {
    }

    /** A chunk was claimed for {@code factionId} (whether from wilderness or by overclaim). */
    default void onChunkClaimed(String world, int chunkX, int chunkZ, UUID factionId) {
    }

    /** A chunk was unclaimed (returned to wilderness). */
    default void onChunkUnclaimed(String world, int chunkX, int chunkZ, UUID factionId) {
    }

    /** The effective relation between two factions changed. */
    default void onRelationChanged(UUID factionA, UUID factionB, RelationType kind) {
    }

    /** A faction bank movement committed ({@code balance} is the post-transaction balance). */
    default void onBankTransaction(UUID factionId, BankTransactionType type, double delta,
                                   double balance) {
    }
}
