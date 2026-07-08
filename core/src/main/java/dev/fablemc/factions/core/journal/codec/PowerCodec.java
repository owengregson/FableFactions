package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.PowerEffect;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.vocab.PowerSource;

/**
 * Encode/decode for the power effect range ({@link EffectTag.Domain#POWER}, {@code 0x0600}): power
 * changes (with their {@link PowerSource}), freeze toggles, death-streak advances, and raidable
 * transitions.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class PowerCodec {

    private PowerCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case POWER_CHANGED -> {
                PowerEffect.PowerChanged x = (PowerEffect.PowerChanged) e;
                Wire.writeUuid(o, x.player());
                o.writeDouble(x.before());
                o.writeDouble(x.after());
                o.writeInt(x.source().code());
                Wire.writeString(o, x.reasonCode());
            }
            case POWER_FROZEN_CHANGED -> {
                PowerEffect.PowerFrozenChanged x = (PowerEffect.PowerFrozenChanged) e;
                Wire.writeUuid(o, x.player());
                o.writeBoolean(x.frozen());
            }
            case DEATH_STREAK_ADVANCED -> {
                PowerEffect.DeathStreakAdvanced x = (PowerEffect.DeathStreakAdvanced) e;
                Wire.writeUuid(o, x.player());
                o.writeInt(x.streak());
            }
            case RAIDABLE_CHANGED -> {
                PowerEffect.RaidableChanged x = (PowerEffect.RaidableChanged) e;
                o.writeInt(x.faction());
                o.writeBoolean(x.nowRaidable());
            }
            default -> throw new IllegalStateException("not a power tag: " + tag);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case POWER_CHANGED ->
                    new PowerEffect.PowerChanged(seq, origin, Wire.readUuid(in), in.readDouble(),
                            in.readDouble(), PowerSource.fromCode(in.readInt()), Wire.readString(in));
            case POWER_FROZEN_CHANGED ->
                    new PowerEffect.PowerFrozenChanged(seq, origin, Wire.readUuid(in), in.readBoolean());
            case DEATH_STREAK_ADVANCED ->
                    new PowerEffect.DeathStreakAdvanced(seq, origin, Wire.readUuid(in), in.readInt());
            case RAIDABLE_CHANGED ->
                    new PowerEffect.RaidableChanged(seq, origin, in.readInt(), in.readBoolean());
            default -> throw new IllegalStateException("not a power tag: " + tag);
        };
    }
}
