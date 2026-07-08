package dev.fablemc.factions.core.pipeline;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * The one {@link AtomicReference} through which the writer publishes state and every reader
 * observes it (proposal-C §3.3). A read is lock-free and wait-free; the writer's single
 * {@code set} per batch is the happens-before edge that makes the immutable snapshot safely
 * visible (AM-8).
 *
 * <p><b>Owning thread(s):</b> {@link #current()} on any reader thread; {@link #publish} on the
 * single writer thread only. <b>Mutability:</b> the reference is mutable (one writer); the
 * {@link KernelSnapshot} it points at is immutable.
 */
public final class SnapshotHub {

    private final AtomicReference<KernelSnapshot> ref;

    public SnapshotHub(KernelSnapshot initial) {
        this.ref = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    /** The latest published snapshot — lock-free, wait-free. A handler takes this once at entry. */
    public KernelSnapshot current() {
        return ref.get();
    }

    /** Publishes a new snapshot. Called by the writer only, once per drained batch. */
    void publish(KernelSnapshot snapshot) {
        ref.set(snapshot);
    }
}
