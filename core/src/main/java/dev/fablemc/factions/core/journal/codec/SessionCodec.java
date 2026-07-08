package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.SessionEffect;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Encode/decode for the session effect range ({@link EffectTag.Domain#SESSION}, {@code 0x0B00}):
 * session start/end and inbox queue/deliver.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class SessionCodec {

    private SessionCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case SESSION_STARTED -> {
                SessionEffect.SessionStarted x = (SessionEffect.SessionStarted) e;
                Wire.writeUuid(o, x.player());
                o.writeLong(x.lastActivity());
            }
            case SESSION_ENDED -> {
                SessionEffect.SessionEnded x = (SessionEffect.SessionEnded) e;
                Wire.writeUuid(o, x.player());
                o.writeLong(x.lastActivity());
            }
            case INBOX_QUEUED -> {
                SessionEffect.InboxQueued x = (SessionEffect.InboxQueued) e;
                Wire.writeUuid(o, x.player());
                Wire.writeMessageKey(o, x.key());
                Wire.writeStringArray(o, x.args());
            }
            case INBOX_DELIVERED -> {
                SessionEffect.InboxDelivered x = (SessionEffect.InboxDelivered) e;
                Wire.writeUuid(o, x.player());
                Wire.writeLongArray(o, x.ids());
            }
            default -> throw new IllegalStateException("not a session tag: " + tag);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case SESSION_STARTED ->
                    new SessionEffect.SessionStarted(seq, origin, Wire.readUuid(in), in.readLong());
            case SESSION_ENDED ->
                    new SessionEffect.SessionEnded(seq, origin, Wire.readUuid(in), in.readLong());
            case INBOX_QUEUED ->
                    new SessionEffect.InboxQueued(seq, origin, Wire.readUuid(in), Wire.readMessageKey(in),
                            Wire.readStringArray(in));
            case INBOX_DELIVERED ->
                    new SessionEffect.InboxDelivered(seq, origin, Wire.readUuid(in), Wire.readLongArray(in));
            default -> throw new IllegalStateException("not a session tag: " + tag);
        };
    }
}
