package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Encode/decode for the claim effect range ({@link EffectTag.Domain#CLAIM}, {@code 0x0400}): claim
 * set/remove and zone set/remove.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class ClaimCodec {

    private ClaimCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case CLAIM_SET -> {
                ClaimEffect.ClaimSet x = (ClaimEffect.ClaimSet) e;
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
                o.writeInt(x.faction());
                o.writeInt(x.prevOwner());
            }
            case CLAIM_REMOVED -> {
                ClaimEffect.ClaimRemoved x = (ClaimEffect.ClaimRemoved) e;
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
                o.writeInt(x.prevOwner());
            }
            case ZONE_SET -> {
                ClaimEffect.ZoneSet x = (ClaimEffect.ZoneSet) e;
                o.writeInt(x.zoneOrdinal());
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
                o.writeInt(x.prevOwner());
            }
            case ZONE_REMOVED -> {
                ClaimEffect.ZoneRemoved x = (ClaimEffect.ZoneRemoved) e;
                o.writeInt(x.zoneOrdinal());
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
            }
            default -> throw new IllegalStateException("not a claim tag: " + tag);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case CLAIM_SET ->
                    new ClaimEffect.ClaimSet(seq, origin, in.readInt(), in.readLong(), in.readInt(),
                            in.readInt());
            case CLAIM_REMOVED ->
                    new ClaimEffect.ClaimRemoved(seq, origin, in.readInt(), in.readLong(), in.readInt());
            case ZONE_SET ->
                    new ClaimEffect.ZoneSet(seq, origin, in.readInt(), in.readInt(), in.readLong(),
                            in.readInt());
            case ZONE_REMOVED ->
                    new ClaimEffect.ZoneRemoved(seq, origin, in.readInt(), in.readInt(), in.readLong());
            default -> throw new IllegalStateException("not a claim tag: " + tag);
        };
    }
}
