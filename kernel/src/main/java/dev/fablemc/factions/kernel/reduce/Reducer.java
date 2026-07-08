package dev.fablemc.factions.kernel.reduce;

import java.util.List;

import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.state.KernelState;

/**
 * The single mutation point of the kernel: {@code (state, envelope) -> (state', effects)}.
 *
 * <p>PURE: no IO, no clock, no Bukkit, no statics. All nondeterminism comes from the
 * envelope (epochMillis, tick, rngSeed). Runs only on the {@code fable-kernel} writer
 * thread.</p>
 *
 * <p>PLACEHOLDER — Wave 2a replaces this body with the full sealed-switch implementation
 * per CONTRACTS §2 / proposal-C §4.4. The signature below is pinned; do not change it.</p>
 */
public final class Reducer {

    private Reducer() {
    }

    /** Applies one intent envelope to the state. Never throws for domain reasons. */
    public static Outcome apply(KernelState state, IntentEnvelope envelope) {
        throw new UnsupportedOperationException("Reducer not yet implemented (Wave 2a)");
    }

    /** The reducer's result: the next state plus the ordered effects it produced. */
    public record Outcome(KernelState next, List<Effect> effects) {
    }
}
