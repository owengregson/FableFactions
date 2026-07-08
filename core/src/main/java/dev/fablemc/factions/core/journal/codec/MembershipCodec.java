package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.MembershipEffect;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.vocab.InviteRemovalReason;

/**
 * Encode/decode for the membership effect range ({@link EffectTag.Domain#MEMBERSHIP},
 * {@code 0x0200}): joins, leaves/kicks, and invite create/remove.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class MembershipCodec {

    private MembershipCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case MEMBER_JOINED -> {
                MembershipEffect.MemberJoined x = (MembershipEffect.MemberJoined) e;
                o.writeInt(x.faction());
                Wire.writeUuid(o, x.player());
            }
            case MEMBER_LEFT -> {
                MembershipEffect.MemberLeft x = (MembershipEffect.MemberLeft) e;
                o.writeInt(x.faction());
                Wire.writeUuid(o, x.player());
                o.writeBoolean(x.kicked());
            }
            case INVITE_CREATED -> {
                MembershipEffect.InviteCreated x = (MembershipEffect.InviteCreated) e;
                o.writeInt(x.faction());
                Wire.writeUuid(o, x.invitee());
                o.writeLong(x.inviteId());
            }
            case INVITE_REMOVED -> {
                MembershipEffect.InviteRemoved x = (MembershipEffect.InviteRemoved) e;
                o.writeInt(x.faction());
                Wire.writeUuid(o, x.invitee());
                o.writeInt(x.reason().code());
            }
            default -> throw new IllegalStateException("not a membership tag: " + tag);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case MEMBER_JOINED ->
                    new MembershipEffect.MemberJoined(seq, origin, in.readInt(), Wire.readUuid(in));
            case MEMBER_LEFT ->
                    new MembershipEffect.MemberLeft(seq, origin, in.readInt(), Wire.readUuid(in),
                            in.readBoolean());
            case INVITE_CREATED ->
                    new MembershipEffect.InviteCreated(seq, origin, in.readInt(), Wire.readUuid(in),
                            in.readLong());
            case INVITE_REMOVED ->
                    new MembershipEffect.InviteRemoved(seq, origin, in.readInt(), Wire.readUuid(in),
                            InviteRemovalReason.fromCode(in.readInt()));
            default -> throw new IllegalStateException("not a membership tag: " + tag);
        };
    }
}
