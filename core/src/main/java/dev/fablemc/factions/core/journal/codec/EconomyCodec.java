package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.EconomyEffect;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.vocab.BankTxType;

/**
 * Encode/decode for the economy effect range ({@link EffectTag.Domain#ECONOMY}, {@code 0x0700}):
 * bank balance changes (with their {@link BankTxType}) and tax charges.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class EconomyCodec {

    private EconomyCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case BANK_CHANGED -> {
                EconomyEffect.BankChanged x = (EconomyEffect.BankChanged) e;
                o.writeInt(x.faction());
                o.writeDouble(x.delta());
                o.writeDouble(x.balance());
                o.writeInt(x.txType().code());
                Wire.writeUuid(o, x.actor());
                o.writeInt(x.counterparty());
                Wire.writeString(o, x.note());
            }
            case TAX_CHARGED -> {
                EconomyEffect.TaxCharged x = (EconomyEffect.TaxCharged) e;
                o.writeInt(x.faction());
                o.writeDouble(x.amount());
                o.writeDouble(x.balance());
            }
            default -> throw tag.outside(EffectTag.Domain.ECONOMY);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case BANK_CHANGED ->
                    new EconomyEffect.BankChanged(seq, origin, in.readInt(), in.readDouble(),
                            in.readDouble(), BankTxType.fromCode(in.readInt()), Wire.readUuid(in),
                            in.readInt(), Wire.readString(in));
            case TAX_CHARGED ->
                    new EconomyEffect.TaxCharged(seq, origin, in.readInt(), in.readDouble(),
                            in.readDouble());
            default -> throw tag.outside(EffectTag.Domain.ECONOMY);
        };
    }
}
