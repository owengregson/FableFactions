package dev.fablemc.factions.kernel.effect;

import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * System effects: the config-swap notification and the AM-5 paged-continuation control effect.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * See {@link Effect} for the hierarchy contract.
 */
public sealed interface SystemEffect extends Effect
        permits SystemEffect.ConfigSwapped, SystemEffect.ContinuationRequested,
        SystemEffect.AggregateDriftDetected {

    record ConfigSwapped(long seq, Origin origin, String diffSummary) implements SystemEffect {
    }

    /**
     * A drift-warning diagnostic emitted by the AM-4 {@code ReconcileSweep} when a faction's
     * stored incremental aggregate ({@code aggregate}, e.g. {@code "landCount"}) disagreed with a
     * fresh recompute from ground truth (the claim atlas): {@code stored} was found, {@code
     * recomputed} is the truth. Drift is always a reducer-bug signal — never silent — so {@code
     * :core} fans this out at <b>WARN</b>. The sweep has already corrected the stored value (and
     * the reverse claim index) in the same step; this record is the loud, journaled audit trail.
     *
     * <p>Unlike {@link ContinuationRequested} this is a normal journaled effect: it carries a
     * persistable diagnostic and MUST be tagged in the journal codec (SYSTEM range).
     */
    record AggregateDriftDetected(long seq, Origin origin, int faction, String aggregate,
                                  int stored, int recomputed) implements SystemEffect {
    }

    /**
     * A request that the writer re-enqueue {@code next} on the system lane (behind already-queued
     * intents) to continue a paged bulk operation (AM-5). Emitted by the reducer when a paged
     * intent (disband / unclaim-all / merge / tax-sweep / zone / retag) has more pages to process;
     * the writer publishes the intermediate snapshot, then submits {@code next}. This is the ONLY
     * control effect that carries an {@link Intent} back into the pipeline, and the sole permitted
     * subtype the journal codec excludes (it never reaches the WAL).
     */
    record ContinuationRequested(long seq, Origin origin, Intent next) implements SystemEffect {
    }
}
