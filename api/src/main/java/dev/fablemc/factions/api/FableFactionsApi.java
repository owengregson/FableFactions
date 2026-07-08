package dev.fablemc.factions.api;

import java.util.concurrent.CompletionStage;

/**
 * The public entry point third-party plugins use to read and mutate faction state
 * (proposal-C §10.1). Obtain the singleton from the Bukkit {@code ServicesManager} once
 * FableFactions has enabled.
 *
 * <p><b>Owning thread(s):</b> {@link #view()} and {@link #subscribe} are callable on any
 * thread; {@link #request} is callable on any thread and never blocks (it enqueues on the
 * single writer and returns immediately). <b>Mutability:</b> the implementation is thread-safe.
 *
 * <p>No method exposes a kernel type or a post-1.13.2 Bukkit type (CONTRACTS §5): reads return
 * an immutable {@link FactionsView}; writes take a {@link FactionRequest} and complete with a
 * {@link RequestResult}; subscriptions take a {@link FactionsEffectListener}.
 */
public interface FableFactionsApi {

    /** A fresh immutable read view over the latest published snapshot. */
    FactionsView view();

    /**
     * Submits a mutation and returns a stage that completes with the reducer's authoritative
     * answer once the intent is applied (on the writer's publish thread). Never blocks the
     * caller and never throws for domain reasons — a domain refusal is a failed
     * {@link RequestResult}, not an exception.
     */
    CompletionStage<RequestResult> request(FactionRequest request);

    /**
     * Registers {@code listener} for commit-order effect notifications; close the returned
     * handle to unsubscribe. The handle's {@code close()} is idempotent.
     */
    AutoCloseable subscribe(FactionsEffectListener listener);
}
