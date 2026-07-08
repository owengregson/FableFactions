package dev.fablemc.factions.core.pipeline;

import java.util.List;

import dev.fablemc.factions.kernel.effect.Effect;

/**
 * The seam through which committed effects reach the per-player/broadcast feedback layer
 * (Notify / NotifyFaction / Broadcast / Rejected effects) and the API event bridge. This is an
 * INTERFACE ONLY in W2b; Wave 4 supplies the implementation that renders messages and routes
 * them through {@code Scheduling} (proposal-C §3.4, §10.2).
 *
 * <p><b>Owning thread(s):</b> {@link #route} is invoked by {@link EffectFanout} on the writer
 * thread; an implementation must not block (it hops to the region/main thread via the
 * scheduler). <b>Mutability:</b> the batch is read-only.
 */
public interface FeedbackRouter {

    /** A no-op router used until Wave 4 wires the real one. */
    FeedbackRouter NOOP = (batch, lastSeq) -> { };

    /** Routes the ordered {@code batch} of committed effects to feedback delivery. */
    void route(List<Effect> batch, long lastSeq);
}
