package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.AuditEffect;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Encode/decode for the audit/system effect range ({@link EffectTag.Domain#SYSTEM}, {@code 0x0C00}):
 * audit records and config swaps. (The control-only {@code ContinuationRequested} carries no tag —
 * it never reaches the journal.)
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class SystemCodec {

    private SystemCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case AUDIT_RECORDED -> {
                AuditEffect.AuditRecorded x = (AuditEffect.AuditRecorded) e;
                o.writeInt(x.faction());
                Wire.writeUuid(o, x.actor());
                Wire.writeAuditAction(o, x.action());
                Wire.writeString(o, x.detail());
            }
            case CONFIG_SWAPPED -> Wire.writeString(o, ((SystemEffect.ConfigSwapped) e).diffSummary());
            default -> throw tag.outside(EffectTag.Domain.SYSTEM);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case AUDIT_RECORDED ->
                    new AuditEffect.AuditRecorded(seq, origin, in.readInt(), Wire.readUuid(in),
                            Wire.readAuditAction(in), Wire.readString(in));
            case CONFIG_SWAPPED -> new SystemEffect.ConfigSwapped(seq, origin, Wire.readString(in));
            default -> throw tag.outside(EffectTag.Domain.SYSTEM);
        };
    }
}
