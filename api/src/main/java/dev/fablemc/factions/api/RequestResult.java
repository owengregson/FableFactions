package dev.fablemc.factions.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The reducer's authoritative answer to a {@link FactionRequest}, delivered through the
 * {@link java.util.concurrent.CompletionStage} returned by
 * {@link FableFactionsApi#request(FactionRequest)}.
 *
 * <p><b>Owning thread(s):</b> completed on the writer's publish thread; consumed on whatever
 * thread the caller's stage callback runs. <b>Mutability:</b> immutable value.
 *
 * <p>API-native only: a failure carries the reference message-catalog {@code reasonKey} string
 * (never the kernel {@code ReasonCode} type — CONTRACTS §5), so callers can localize or log it.
 * {@code factionId} is present on a successful create/lookup where a new/affected faction id is
 * meaningful.
 */
public record RequestResult(boolean success, Optional<String> reasonKey,
                            Optional<UUID> factionId) {

    private static final RequestResult OK = new RequestResult(true, Optional.empty(), Optional.empty());

    /** A generic success with no associated faction id. */
    public static RequestResult ok() {
        return OK;
    }

    /** A success that produced or affected the faction {@code factionId}. */
    public static RequestResult ok(UUID factionId) {
        return new RequestResult(true, Optional.empty(), Optional.ofNullable(factionId));
    }

    /** A failure carrying the reference message-catalog key that explains it. */
    public static RequestResult failure(String reasonKey) {
        return new RequestResult(false, Optional.of(reasonKey), Optional.empty());
    }
}
