package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Encode/decode for the lifecycle effect range ({@link EffectTag.Domain#LIFECYCLE}, {@code 0x0100}):
 * faction creation, disband, rename, description/motd, ownership transfer, and merges.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class LifecycleCodec {

    private LifecycleCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case FACTION_CREATED -> {
                LifecycleEffect.FactionCreated x = (LifecycleEffect.FactionCreated) e;
                o.writeInt(x.faction());
                Wire.writeUuid(o, x.id());
                Wire.writeString(o, x.name());
            }
            case FACTION_DISBANDED -> {
                LifecycleEffect.FactionDisbanded x = (LifecycleEffect.FactionDisbanded) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.name());
            }
            case FACTION_RENAMED -> {
                LifecycleEffect.FactionRenamed x = (LifecycleEffect.FactionRenamed) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.oldName());
                Wire.writeString(o, x.newName());
            }
            case DESCRIPTION_CHANGED -> {
                LifecycleEffect.DescriptionChanged x = (LifecycleEffect.DescriptionChanged) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.description());
            }
            case MOTD_CHANGED -> {
                LifecycleEffect.MotdChanged x = (LifecycleEffect.MotdChanged) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.motd());
            }
            case OWNERSHIP_TRANSFERRED -> {
                LifecycleEffect.OwnershipTransferred x = (LifecycleEffect.OwnershipTransferred) e;
                o.writeInt(x.faction());
                Wire.writeUuid(o, x.oldOwner());
                Wire.writeUuid(o, x.newOwner());
            }
            case MERGE_REQUESTED -> {
                LifecycleEffect.MergeRequested x = (LifecycleEffect.MergeRequested) e;
                o.writeInt(x.sender());
                o.writeInt(x.target());
            }
            case MERGE_COMPLETED -> {
                LifecycleEffect.MergeCompleted x = (LifecycleEffect.MergeCompleted) e;
                o.writeInt(x.sender());
                o.writeInt(x.target());
                o.writeInt(x.memberMoves());
                o.writeInt(x.claimMoves());
                o.writeDouble(x.bankMoved());
            }
            default -> throw new IllegalStateException("not a lifecycle tag: " + tag);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case FACTION_CREATED ->
                    new LifecycleEffect.FactionCreated(seq, origin, in.readInt(), Wire.readUuid(in),
                            Wire.readString(in));
            case FACTION_DISBANDED ->
                    new LifecycleEffect.FactionDisbanded(seq, origin, in.readInt(), Wire.readString(in));
            case FACTION_RENAMED ->
                    new LifecycleEffect.FactionRenamed(seq, origin, in.readInt(), Wire.readString(in),
                            Wire.readString(in));
            case DESCRIPTION_CHANGED ->
                    new LifecycleEffect.DescriptionChanged(seq, origin, in.readInt(), Wire.readString(in));
            case MOTD_CHANGED ->
                    new LifecycleEffect.MotdChanged(seq, origin, in.readInt(), Wire.readString(in));
            case OWNERSHIP_TRANSFERRED ->
                    new LifecycleEffect.OwnershipTransferred(seq, origin, in.readInt(), Wire.readUuid(in),
                            Wire.readUuid(in));
            case MERGE_REQUESTED ->
                    new LifecycleEffect.MergeRequested(seq, origin, in.readInt(), in.readInt());
            case MERGE_COMPLETED ->
                    new LifecycleEffect.MergeCompleted(seq, origin, in.readInt(), in.readInt(), in.readInt(),
                            in.readInt(), in.readDouble());
            default -> throw new IllegalStateException("not a lifecycle tag: " + tag);
        };
    }
}
