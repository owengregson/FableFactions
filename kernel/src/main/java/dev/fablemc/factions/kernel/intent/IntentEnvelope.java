package dev.fablemc.factions.kernel.intent;

/**
 * A submitted intent plus all the nondeterminism captured at enqueue time.
 *
 * <p><b>Owning thread(s):</b> built on the calling thread at submit, consumed by the single
 * writer. <b>Mutability:</b> immutable value. <b>Reducer rule:</b> the reducer consumes envelope
 * fields ONLY for time/randomness — never the wall clock or {@code Math.random} — which is what
 * makes replay deterministic (proposal-C §4.5, §15 N-1).
 *
 * <p>{@code seq} is the total order the writer assigns; {@code epochMillis}/{@code tick} pin
 * time; {@code rngSeed} seeds a per-envelope {@code SplittableRandom} for any new-row UUIDs.
 */
public record IntentEnvelope(long seq, long epochMillis, int tick, long rngSeed, Origin origin,
                             Intent intent) {
}
