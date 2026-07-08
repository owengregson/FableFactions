package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.TravelEffect;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Encode/decode for the travel effect range ({@link EffectTag.Domain#TRAVEL}, {@code 0x0800}): home
 * set/clear and warp set/delete/password/cost.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class TravelCodec {

    private TravelCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case HOME_SET -> o.writeInt(((TravelEffect.HomeSet) e).faction());
            case HOME_CLEARED -> o.writeInt(((TravelEffect.HomeCleared) e).faction());
            case WARP_SET -> {
                TravelEffect.WarpSet x = (TravelEffect.WarpSet) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.name());
            }
            case WARP_DELETED -> {
                TravelEffect.WarpDeleted x = (TravelEffect.WarpDeleted) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.name());
            }
            case WARP_PASSWORD_SET -> {
                TravelEffect.WarpPasswordSet x = (TravelEffect.WarpPasswordSet) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.name());
                o.writeBoolean(x.cleared());
            }
            case WARP_COST_SET -> {
                TravelEffect.WarpCostSet x = (TravelEffect.WarpCostSet) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.name());
                o.writeDouble(x.cost());
            }
            default -> throw new IllegalStateException("not a travel tag: " + tag);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case HOME_SET -> new TravelEffect.HomeSet(seq, origin, in.readInt());
            case HOME_CLEARED -> new TravelEffect.HomeCleared(seq, origin, in.readInt());
            case WARP_SET -> new TravelEffect.WarpSet(seq, origin, in.readInt(), Wire.readString(in));
            case WARP_DELETED ->
                    new TravelEffect.WarpDeleted(seq, origin, in.readInt(), Wire.readString(in));
            case WARP_PASSWORD_SET ->
                    new TravelEffect.WarpPasswordSet(seq, origin, in.readInt(), Wire.readString(in),
                            in.readBoolean());
            case WARP_COST_SET ->
                    new TravelEffect.WarpCostSet(seq, origin, in.readInt(), Wire.readString(in),
                            in.readDouble());
            default -> throw new IllegalStateException("not a travel tag: " + tag);
        };
    }
}
