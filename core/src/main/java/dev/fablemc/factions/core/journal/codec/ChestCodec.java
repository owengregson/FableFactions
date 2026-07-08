package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.ChestEffect;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Encode/decode for the chest effect range ({@link EffectTag.Domain#CHEST}, {@code 0x0900}): team
 * chest create/delete and contents-blob updates.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class ChestCodec {

    private ChestCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case CHEST_CREATED -> {
                ChestEffect.ChestCreated x = (ChestEffect.ChestCreated) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.name());
            }
            case CHEST_DELETED -> {
                ChestEffect.ChestDeleted x = (ChestEffect.ChestDeleted) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.name());
            }
            case CHEST_CONTENTS_CHANGED -> {
                ChestEffect.ChestContentsChanged x = (ChestEffect.ChestContentsChanged) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.name());
                o.writeLong(x.blobRef());
            }
            default -> throw tag.outside(EffectTag.Domain.CHEST);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case CHEST_CREATED ->
                    new ChestEffect.ChestCreated(seq, origin, in.readInt(), Wire.readString(in));
            case CHEST_DELETED ->
                    new ChestEffect.ChestDeleted(seq, origin, in.readInt(), Wire.readString(in));
            case CHEST_CONTENTS_CHANGED ->
                    new ChestEffect.ChestContentsChanged(seq, origin, in.readInt(), Wire.readString(in),
                            in.readLong());
            default -> throw tag.outside(EffectTag.Domain.CHEST);
        };
    }
}
