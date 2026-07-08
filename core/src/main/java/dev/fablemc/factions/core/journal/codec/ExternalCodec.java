package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.ExternalEffect;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Encode/decode for the external effect range ({@link EffectTag.Domain#EXTERNAL}, {@code 0x0E00}):
 * integration-bound requests — escrow payout/refund, WorldGuard region upsert/remove, and LWC purge.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class ExternalCodec {

    private ExternalCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case PAYOUT_REQUESTED -> {
                ExternalEffect.PayoutRequested x = (ExternalEffect.PayoutRequested) e;
                o.writeLong(x.escrowId());
                Wire.writeUuid(o, x.player());
                o.writeDouble(x.amount());
            }
            case ESCROW_REFUND -> {
                ExternalEffect.EscrowRefund x = (ExternalEffect.EscrowRefund) e;
                o.writeLong(x.escrowId());
                Wire.writeUuid(o, x.player());
                o.writeDouble(x.amount());
            }
            case WG_REGION_UPSERT -> {
                ExternalEffect.WgRegionUpsert x = (ExternalEffect.WgRegionUpsert) e;
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
                o.writeInt(x.faction());
            }
            case WG_REGION_REMOVE -> {
                ExternalEffect.WgRegionRemove x = (ExternalEffect.WgRegionRemove) e;
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
            }
            case LWC_PURGE_REQUESTED -> {
                ExternalEffect.LwcPurgeRequested x = (ExternalEffect.LwcPurgeRequested) e;
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
                o.writeInt(x.newOwner());
            }
            default -> throw tag.outside(EffectTag.Domain.EXTERNAL);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case PAYOUT_REQUESTED ->
                    new ExternalEffect.PayoutRequested(seq, origin, in.readLong(), Wire.readUuid(in),
                            in.readDouble());
            case ESCROW_REFUND ->
                    new ExternalEffect.EscrowRefund(seq, origin, in.readLong(), Wire.readUuid(in),
                            in.readDouble());
            case WG_REGION_UPSERT ->
                    new ExternalEffect.WgRegionUpsert(seq, origin, in.readInt(), in.readLong(),
                            in.readInt());
            case WG_REGION_REMOVE ->
                    new ExternalEffect.WgRegionRemove(seq, origin, in.readInt(), in.readLong());
            case LWC_PURGE_REQUESTED ->
                    new ExternalEffect.LwcPurgeRequested(seq, origin, in.readInt(), in.readLong(),
                            in.readInt());
            default -> throw tag.outside(EffectTag.Domain.EXTERNAL);
        };
    }
}
