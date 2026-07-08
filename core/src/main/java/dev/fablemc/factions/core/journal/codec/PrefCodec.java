package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.PrefEffect;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Encode/decode for the pref effect range ({@link EffectTag.Domain#PREF}, {@code 0x0A00}): faction
 * flags, player pref bits, locale, auto-territory mode, fly, override, and shield windows.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class PrefCodec {

    private PrefCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case FLAG_CHANGED -> {
                PrefEffect.FlagChanged x = (PrefEffect.FlagChanged) e;
                o.writeInt(x.faction());
                o.writeInt(x.flag());
                o.writeBoolean(x.value());
            }
            case PREF_CHANGED -> {
                PrefEffect.PrefChanged x = (PrefEffect.PrefChanged) e;
                Wire.writeUuid(o, x.player());
                o.writeInt(x.prefBit());
                o.writeBoolean(x.value());
            }
            case LOCALE_CHANGED -> {
                PrefEffect.LocaleChanged x = (PrefEffect.LocaleChanged) e;
                Wire.writeUuid(o, x.player());
                o.writeInt(x.localeIdx());
            }
            case AUTO_MODE_CHANGED -> {
                PrefEffect.AutoModeChanged x = (PrefEffect.AutoModeChanged) e;
                Wire.writeUuid(o, x.player());
                o.writeInt(x.mode());
            }
            case FLY_CHANGED -> {
                PrefEffect.FlyChanged x = (PrefEffect.FlyChanged) e;
                Wire.writeUuid(o, x.player());
                o.writeBoolean(x.on());
            }
            case OVERRIDE_CHANGED -> {
                PrefEffect.OverrideChanged x = (PrefEffect.OverrideChanged) e;
                Wire.writeUuid(o, x.player());
                o.writeBoolean(x.on());
            }
            case SHIELD_CHANGED -> {
                PrefEffect.ShieldChanged x = (PrefEffect.ShieldChanged) e;
                o.writeInt(x.faction());
                o.writeInt(x.startHour());
                o.writeInt(x.durationHours());
            }
            default -> throw tag.outside(EffectTag.Domain.PREF);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case FLAG_CHANGED ->
                    new PrefEffect.FlagChanged(seq, origin, in.readInt(), in.readInt(), in.readBoolean());
            case PREF_CHANGED ->
                    new PrefEffect.PrefChanged(seq, origin, Wire.readUuid(in), in.readInt(),
                            in.readBoolean());
            case LOCALE_CHANGED ->
                    new PrefEffect.LocaleChanged(seq, origin, Wire.readUuid(in), in.readInt());
            case AUTO_MODE_CHANGED ->
                    new PrefEffect.AutoModeChanged(seq, origin, Wire.readUuid(in), in.readInt());
            case FLY_CHANGED ->
                    new PrefEffect.FlyChanged(seq, origin, Wire.readUuid(in), in.readBoolean());
            case OVERRIDE_CHANGED ->
                    new PrefEffect.OverrideChanged(seq, origin, Wire.readUuid(in), in.readBoolean());
            case SHIELD_CHANGED ->
                    new PrefEffect.ShieldChanged(seq, origin, in.readInt(), in.readInt(), in.readInt());
            default -> throw tag.outside(EffectTag.Domain.PREF);
        };
    }
}
