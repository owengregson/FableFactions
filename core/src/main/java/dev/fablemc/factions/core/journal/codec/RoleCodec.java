package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.RoleEffect;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Encode/decode for the role effect range ({@link EffectTag.Domain#ROLE}, {@code 0x0300}): rank
 * changes and role create/rename/reprioritize/prefix/delete/assign.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class RoleCodec {

    private RoleCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case RANK_CHANGED -> {
                RoleEffect.RankChanged x = (RoleEffect.RankChanged) e;
                o.writeInt(x.faction());
                Wire.writeUuid(o, x.player());
                o.writeInt(x.newRankIdx());
            }
            case ROLE_CREATED -> {
                RoleEffect.RoleCreated x = (RoleEffect.RoleCreated) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.roleId());
                Wire.writeString(o, x.name());
                o.writeInt(x.priority());
            }
            case ROLE_RENAMED -> {
                RoleEffect.RoleRenamed x = (RoleEffect.RoleRenamed) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.roleId());
                Wire.writeString(o, x.oldName());
                Wire.writeString(o, x.newName());
            }
            case ROLE_REPRIORITIZED -> {
                RoleEffect.RoleRePrioritized x = (RoleEffect.RoleRePrioritized) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.roleId());
                o.writeInt(x.priority());
            }
            case ROLE_PREFIX_SET -> {
                RoleEffect.RolePrefixSet x = (RoleEffect.RolePrefixSet) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.roleId());
                Wire.writeString(o, x.prefix());
            }
            case ROLE_DELETED -> {
                RoleEffect.RoleDeleted x = (RoleEffect.RoleDeleted) e;
                o.writeInt(x.faction());
                Wire.writeString(o, x.roleId());
            }
            case ROLE_ASSIGNED -> {
                RoleEffect.RoleAssigned x = (RoleEffect.RoleAssigned) e;
                o.writeInt(x.faction());
                Wire.writeUuid(o, x.player());
                Wire.writeString(o, x.roleId());
            }
            default -> throw tag.outside(EffectTag.Domain.ROLE);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case RANK_CHANGED ->
                    new RoleEffect.RankChanged(seq, origin, in.readInt(), Wire.readUuid(in), in.readInt());
            case ROLE_CREATED ->
                    new RoleEffect.RoleCreated(seq, origin, in.readInt(), Wire.readString(in),
                            Wire.readString(in), in.readInt());
            case ROLE_RENAMED ->
                    new RoleEffect.RoleRenamed(seq, origin, in.readInt(), Wire.readString(in),
                            Wire.readString(in), Wire.readString(in));
            case ROLE_REPRIORITIZED ->
                    new RoleEffect.RoleRePrioritized(seq, origin, in.readInt(), Wire.readString(in),
                            in.readInt());
            case ROLE_PREFIX_SET ->
                    new RoleEffect.RolePrefixSet(seq, origin, in.readInt(), Wire.readString(in),
                            Wire.readString(in));
            case ROLE_DELETED ->
                    new RoleEffect.RoleDeleted(seq, origin, in.readInt(), Wire.readString(in));
            case ROLE_ASSIGNED ->
                    new RoleEffect.RoleAssigned(seq, origin, in.readInt(), Wire.readUuid(in),
                            Wire.readString(in));
            default -> throw tag.outside(EffectTag.Domain.ROLE);
        };
    }
}
