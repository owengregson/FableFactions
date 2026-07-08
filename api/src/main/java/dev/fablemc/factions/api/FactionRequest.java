package dev.fablemc.factions.api;

import java.util.UUID;

/**
 * A mutation another plugin asks FableFactions to perform, submitted through
 * {@link FableFactionsApi#request(FactionRequest)}.
 *
 * <p><b>Owning thread(s):</b> constructed on any thread; the {@code :core} bridge translates it
 * into a kernel intent and enqueues it on the single writer. <b>Mutability:</b> immutable value
 * hierarchy. The reducer's authoritative answer arrives as a {@link RequestResult}.
 *
 * <p>The records are nested so the sealed hierarchy is self-contained. Each carries API-native
 * types only (no kernel type crosses the boundary — CONTRACTS §5); {@code :core} maps every
 * variant onto the corresponding {@code Intent} record and back-fills the internal
 * ordinal/handle from the request's {@link UUID}s.
 */
public sealed interface FactionRequest {

    /** Create a new faction named {@code name}, owned by {@code owner}. */
    record CreateFaction(String name, UUID owner) implements FactionRequest {
    }

    /** Disband the faction {@code factionId} on behalf of {@code actor}. */
    record DisbandFaction(UUID factionId, UUID actor) implements FactionRequest {
    }

    /** {@code player} joins the (open, or otherwise permitted) faction {@code factionId}. */
    record JoinFaction(UUID factionId, UUID player) implements FactionRequest {
    }

    /** {@code player} leaves their current faction. */
    record LeaveFaction(UUID player) implements FactionRequest {
    }

    /** Declare a relation from {@code actorFaction} toward {@code targetFaction}. */
    record SetRelation(UUID actorFaction, UUID targetFaction, RelationType kind, UUID actor)
            implements FactionRequest {
    }

    /** Deposit {@code amount} into {@code factionId}'s bank on behalf of {@code actor}. */
    record DepositBank(UUID factionId, double amount, UUID actor) implements FactionRequest {
    }

    /** Withdraw {@code amount} from {@code factionId}'s bank on behalf of {@code actor}. */
    record WithdrawBank(UUID factionId, double amount, UUID actor) implements FactionRequest {
    }
}
