package dev.fablemc.factions.core.pipeline;

import java.util.List;

import dev.fablemc.factions.kernel.effect.Effect;

/**
 * A consumer of the writer's ordered effect batches (proposal-C §3.2). The journal and the
 * effect fanout are the two sinks the writer hands each drained batch to, in that order.
 *
 * <p><b>Owning thread(s):</b> {@link #accept} is invoked by the single writer thread, once per
 * published batch, in commit order. An implementation that does heavy work (JDBC, IO) must hand
 * the batch off to its own thread and return promptly — the writer must not block. <b>Mutability:</b>
 * the {@code batch} list is effectively immutable; sinks must only read it.
 */
public interface EffectSink {

    /**
     * Consumes the ordered {@code batch} of effects. {@code lastSeq} is the sequence number of
     * the last effect in the batch — the checkpoint a durable sink advances to once the batch is
     * persisted.
     */
    void accept(List<Effect> batch, long lastSeq);
}
