package dev.fablemc.factions.core.pipeline;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.fablemc.factions.kernel.effect.Effect;

/**
 * Fans one ordered effect batch out from the writer to the storage projector, N read-model
 * subscribers, and the feedback router (proposal-C §3.2). The storage sink is an SPSC handoff —
 * the projector's {@code accept} only enqueues and returns, so this call stays cheap on the
 * writer thread.
 *
 * <p><b>Owning thread(s):</b> {@link #accept} runs on the single writer thread;
 * {@link #subscribe}/{@code close} may run on any thread (the subscriber list is copy-on-write).
 * <b>Mutability:</b> the subscriber list is COW; batches are read-only.
 *
 * <p>This is itself an {@link EffectSink} so the writer treats journal and fanout uniformly:
 * after publishing the snapshot it hands the batch to the journal sink, then to this fanout.
 */
public final class EffectFanout implements EffectSink {

    private final EffectSink storage;
    private final FeedbackRouter feedback;
    private final CopyOnWriteArrayList<FactionsEffectSubscriber> subscribers =
            new CopyOnWriteArrayList<>();

    public EffectFanout(EffectSink storage, FeedbackRouter feedback) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    /** A subscriber consuming committed effect batches in commit order (Wave 4 read models). */
    public interface FactionsEffectSubscriber {
        void onBatch(List<Effect> batch, long lastSeq);
    }

    /**
     * Registers {@code subscriber}; close the returned handle to unsubscribe. The handle's
     * {@code close()} is idempotent.
     */
    public AutoCloseable subscribe(FactionsEffectSubscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.add(subscriber);
        return new AutoCloseable() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                subscribers.remove(subscriber);
            }
        };
    }

    @Override
    public void accept(List<Effect> batch, long lastSeq) {
        // 1) durable projection first (SPSC enqueue — the projector owns its own flush thread).
        storage.accept(batch, lastSeq);
        // 2) read-model subscribers, in registration order.
        for (int i = 0; i < subscribers.size(); i++) {
            subscribers.get(i).onBatch(batch, lastSeq);
        }
        // 3) feedback / API event bridge (Wave 4 implementation).
        feedback.route(batch, lastSeq);
    }
}
