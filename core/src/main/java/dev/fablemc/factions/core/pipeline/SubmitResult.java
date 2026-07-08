package dev.fablemc.factions.core.pipeline;

/**
 * The outcome of an {@link IntentBus#submit} on the bounded player lane (proposal-C §3.5).
 *
 * <p><b>Owning thread(s):</b> returned on the caller's thread; the command layer answers
 * immediately from it. <b>Mutability:</b> immutable enum.
 */
public enum SubmitResult {
    /** The intent was accepted onto the writer's queue. */
    ACCEPTED,
    /** The bounded player lane is full — the caller answers {@code general.busy} (AM-9). */
    REJECTED_BUSY,
    /** The pipeline is shutting down — the caller answers {@code general.shutting-down}. */
    REJECTED_SHUTDOWN
}
