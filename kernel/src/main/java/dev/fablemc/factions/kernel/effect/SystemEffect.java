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
        permits SystemEffect.ConfigSwapped, SystemEffect.ContinuationRequested {

    record ConfigSwapped(long seq, Origin origin, String diffSummary) implements SystemEffect {
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
