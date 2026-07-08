package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.vocab.BroadcastScope;
import dev.fablemc.factions.kernel.vocab.NotifyPredicate;

/**
 * Encode/decode for the feedback effect range ({@link EffectTag.Domain#FEEDBACK}, {@code 0x0D00}):
 * player notifies, faction notifies (with their {@link NotifyPredicate}), broadcasts (with their
 * {@link BroadcastScope}), and rejections.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class FeedbackCodec {

    private FeedbackCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case NOTIFY -> {
                FeedbackEffect.Notify x = (FeedbackEffect.Notify) e;
                Wire.writeUuid(o, x.target());
                Wire.writeMessageKey(o, x.key());
                Wire.writeStringArray(o, x.args());
            }
            case NOTIFY_FACTION -> {
                FeedbackEffect.NotifyFaction x = (FeedbackEffect.NotifyFaction) e;
                o.writeInt(x.faction());
                o.writeInt(x.predicate().code());
                Wire.writeMessageKey(o, x.key());
                Wire.writeStringArray(o, x.args());
            }
            case BROADCAST -> {
                FeedbackEffect.Broadcast x = (FeedbackEffect.Broadcast) e;
                o.writeInt(x.scope().code());
                Wire.writeMessageKey(o, x.key());
                Wire.writeStringArray(o, x.args());
            }
            case REJECTED -> {
                FeedbackEffect.Rejected x = (FeedbackEffect.Rejected) e;
                Wire.writeString(o, x.reason() == null ? null : x.reason().name());
                Wire.writeStringArray(o, x.args());
            }
            default -> throw new IllegalStateException("not a feedback tag: " + tag);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case NOTIFY ->
                    new FeedbackEffect.Notify(seq, origin, Wire.readUuid(in), Wire.readMessageKey(in),
                            Wire.readStringArray(in));
            case NOTIFY_FACTION ->
                    new FeedbackEffect.NotifyFaction(seq, origin, in.readInt(),
                            NotifyPredicate.fromCode(in.readInt()), Wire.readMessageKey(in),
                            Wire.readStringArray(in));
            case BROADCAST ->
                    new FeedbackEffect.Broadcast(seq, origin, BroadcastScope.fromCode(in.readInt()),
                            Wire.readMessageKey(in), Wire.readStringArray(in));
            case REJECTED ->
                    new FeedbackEffect.Rejected(seq, origin, Wire.readReasonCode(in),
                            Wire.readStringArray(in));
            default -> throw new IllegalStateException("not a feedback tag: " + tag);
        };
    }
}
