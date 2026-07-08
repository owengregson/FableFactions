package dev.fablemc.factions.kernel.effect;

import java.util.UUID;

import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.vocab.BroadcastScope;
import dev.fablemc.factions.kernel.vocab.NotifyPredicate;

/**
 * Feedback effects: the text-delivery vocabulary — notify one player, notify a faction, broadcast,
 * and reject. The reducer never emits text; these carry a {@link MessageKey} plus args (or a
 * {@link ReasonCode}) that the feedback router renders.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * See {@link Effect} for the hierarchy contract.
 */
public sealed interface FeedbackEffect extends Effect
        permits FeedbackEffect.Notify, FeedbackEffect.NotifyFaction, FeedbackEffect.Broadcast,
        FeedbackEffect.Rejected {

    /** Deliver a message to one player. */
    record Notify(long seq, Origin origin, UUID target, MessageKey key, String[] args)
            implements FeedbackEffect {
    }

    /** Deliver a message to a faction's members ({@code predicate} selects which); offline → inbox. */
    record NotifyFaction(long seq, Origin origin, int faction, NotifyPredicate predicate,
                         MessageKey key, String[] args) implements FeedbackEffect {
    }

    /** Broadcast a message ({@code scope} = server/staff). */
    record Broadcast(long seq, Origin origin, BroadcastScope scope, MessageKey key, String[] args)
            implements FeedbackEffect {
    }

    /** A rejection with its 1:1-mapped {@link ReasonCode} and message args. */
    record Rejected(long seq, Origin origin, ReasonCode reason, String[] args)
            implements FeedbackEffect {
    }
}
