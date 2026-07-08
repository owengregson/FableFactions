package dev.fablemc.factions.api;

import java.util.Optional;
import java.util.UUID;

/**
 * An immutable, thread-safe read facade over a single kernel snapshot — everything PAPI, a
 * scoreboard, a map integration or another plugin needs, with zero IO (proposal-C §10.1).
 *
 * <p><b>Owning thread(s):</b> any thread; each method is a wait-free read of the immutable
 * snapshot this view was taken over. <b>Mutability:</b> immutable value view; a fresh call to
 * {@link FableFactionsApi#view()} re-reads the latest published snapshot.
 *
 * <p>Every signature is expressed in API-native types only (primitives, {@link String},
 * {@link UUID}, {@link Optional}, and enums / records defined in {@code :api}); no kernel type
 * and no post-1.13.2 Bukkit type crosses this boundary (CONTRACTS §5). Factions are identified
 * to callers by their stable {@link UUID}, never by the kernel's internal ordinal/handle.
 */
public interface FactionsView {

    /** The snapshot version this view reads (monotonic; one bump per published writer batch). */
    long version();

    /** {@code true} when the given chunk is claimed by any faction. */
    boolean isClaimed(String world, int chunkX, int chunkZ);

    /** The faction id owning the given chunk, or empty for wilderness / a system zone. */
    Optional<UUID> claimOwner(String world, int chunkX, int chunkZ);

    /** Summary of the faction with this id, or empty if unknown. */
    Optional<FactionInfo> factionById(UUID factionId);

    /** Summary of the faction whose name matches (case-insensitively), or empty. */
    Optional<FactionInfo> factionByName(String name);

    /** The faction id the player belongs to, or empty if factionless / unknown. */
    Optional<UUID> factionOf(UUID player);

    /** Membership + power summary for a known player, or empty. */
    Optional<MemberInfo> member(UUID player);

    /** Effective relation from {@code factionA} toward {@code factionB}. */
    RelationType relationBetween(UUID factionA, UUID factionB);

    /** The player's current (lazily-accrued) power, or {@code 0.0} if unknown. */
    double power(UUID player);

    /** The faction's bank balance, or {@code 0.0} if unknown. */
    double bank(UUID factionId);

    /** The number of chunks the faction has claimed. */
    int landCount(UUID factionId);

    /** The number of members in the faction. */
    int memberCount(UUID factionId);

    /**
     * A faction's public summary. All fields are API-native; {@code ownerId} may be absent for a
     * system/sentinel faction.
     */
    record FactionInfo(UUID id, String name, Optional<UUID> ownerId, double bank, int landCount,
                       int memberCount, boolean raidable) {
    }

    /** A member's public summary. {@code factionId} is empty when the player is factionless. */
    record MemberInfo(UUID player, Optional<UUID> factionId, String rankName, double power) {
    }
}
