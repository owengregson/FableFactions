package dev.fablemc.factions.kernel.reduce;

import java.util.List;

import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.intent.ChestIntent;
import dev.fablemc.factions.kernel.intent.ClaimIntent;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.intent.PowerIntent;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.intent.RelationIntent;
import dev.fablemc.factions.kernel.intent.RoleIntent;
import dev.fablemc.factions.kernel.intent.SessionIntent;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.state.KernelState;

/**
 * The single mutation point of the kernel: {@code (state, envelope) -> (state', effects)}
 * (CONTRACTS §2, proposal-C §4.4). Replaces the Wave-1 placeholder.
 *
 * <p><b>Owning thread(s):</b> the {@code fable-kernel} writer only. <b>Mutability:</b> stateless
 * (static dispatch); all working state is a per-call confined {@link ReduceSupport}.
 * <b>Reducer rule:</b> this IS the reducer — the only code allowed to produce a new
 * {@code KernelState}.
 *
 * <p>PURE: no IO, no clock, no Bukkit, no statics. All nondeterminism comes from the envelope
 * ({@code epochMillis}, {@code tick}, {@code rngSeed}). The per-domain reduction logic lives in
 * the package-private {@code XxxReducer} classes (W25-REORG P2a split this class into a
 * dispatcher over the intent sub-interfaces); the shared working context and common helpers live
 * in {@link ReduceSupport}.
 *
 * <p>This dispatcher switches on the intent SUB-INTERFACE and delegates to the owning domain
 * reducer, which switches on the concrete record. An unrecognised intent throws, which the writer's
 * per-intent try/catch (AM-9) turns into {@code Rejected(INTERNAL_ERROR)}.
 *
 * <p><b>Paged bulk ops (AM-5).</b> When a paged intent (disband / unclaim-all / merge / tax-sweep /
 * zone / retag) has more work, the reducer emits a {@code ContinuationRequested(next)}; the
 * {@code WriterThread} re-enqueues {@code next} on the system lane, publishing the intermediate
 * snapshot between pages. Each page touches at most {@link #PAGE_SIZE} entities.
 */
public final class Reducer {

    private Reducer() {
    }

    /** Max entities touched per AM-5 page (≤1024 invariant). */
    public static final int PAGE_SIZE = 1024;

    /** Applies one intent envelope to the state. Never throws for domain reasons. */
    public static Outcome apply(KernelState state, IntentEnvelope envelope) {
        ReduceSupport s = new ReduceSupport(state, envelope);
        Intent i = envelope.intent();

        // Keep the state's tick in step with the envelope so snapshot power reads settle correctly.
        if (s.state.tick() != envelope.tick()) {
            s.state = s.state.withVersionTick(s.state.version(), envelope.tick());
        }

        if (i instanceof LifecycleIntent x) {
            LifecycleReducer.reduce(s, x);
        } else if (i instanceof MembershipIntent x) {
            MembershipReducer.reduce(s, x);
        } else if (i instanceof RoleIntent x) {
            RoleReducer.reduce(s, x);
        } else if (i instanceof ClaimIntent x) {
            ClaimReducer.reduce(s, x);
        } else if (i instanceof RelationIntent x) {
            RelationReducer.reduce(s, x);
        } else if (i instanceof PowerIntent x) {
            PowerReducer.reduce(s, x);
        } else if (i instanceof EconomyIntent x) {
            EconomyReducer.reduce(s, x);
        } else if (i instanceof TravelIntent x) {
            TravelReducer.reduce(s, x);
        } else if (i instanceof ChestIntent x) {
            ChestReducer.reduce(s, x);
        } else if (i instanceof PrefIntent x) {
            PrefReducer.reduce(s, x);
        } else if (i instanceof SessionIntent x) {
            SessionReducer.reduce(s, x);
        } else if (i instanceof SystemIntent x) {
            SystemReducer.reduce(s, x);
        } else {
            throw new IllegalStateException("unhandled intent: " + i.getClass().getName());
        }
        return s.outcome();
    }

    /** The reducer's result: the next state plus the ordered effects it produced. */
    public record Outcome(KernelState next, List<Effect> effects) {
    }
}
