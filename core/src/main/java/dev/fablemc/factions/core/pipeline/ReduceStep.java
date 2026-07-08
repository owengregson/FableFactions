package dev.fablemc.factions.core.pipeline;

import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.reduce.Reducer;
import dev.fablemc.factions.kernel.state.KernelState;

/**
 * The single reduce step the {@link WriterThread} invokes per intent — a package seam so tests
 * can inject a stub reducer (e.g. one that throws, to exercise the AM-9 failure boundary)
 * without touching the {@code :kernel} {@link Reducer}, which is {@code final} and static.
 *
 * <p><b>Owning thread(s):</b> the writer thread only. <b>Mutability:</b> stateless (must be pure,
 * like the reducer it wraps).
 */
@FunctionalInterface
public interface ReduceStep {

    /** The production step: delegates to the pure kernel {@link Reducer}. */
    ReduceStep KERNEL = Reducer::apply;

    /** Applies one intent envelope to {@code state}, returning the next state and its effects. */
    Reducer.Outcome apply(KernelState state, IntentEnvelope envelope);
}
