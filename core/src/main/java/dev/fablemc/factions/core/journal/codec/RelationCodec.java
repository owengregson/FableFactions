package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import dev.fablemc.factions.core.journal.EffectTag;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.RelationEffect;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.vocab.Relation;

/**
 * Encode/decode for the relation effect range ({@link EffectTag.Domain#RELATION}, {@code 0x0500}):
 * declared wishes and effective symmetric edges. The {@link Relation} companion carries the cold-path
 * byte code (hot arrays keep the {@code RelationKind} bytes).
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class RelationCodec {

    private RelationCodec() {
    }

    public static void encode(DataOutputStream o, EffectTag tag, Effect e) throws IOException {
        switch (tag) {
            case RELATION_DECLARED -> {
                RelationEffect.RelationDeclared x = (RelationEffect.RelationDeclared) e;
                o.writeInt(x.a());
                o.writeInt(x.b());
                o.writeInt(x.kind().code());
            }
            case RELATION_EFFECTIVE -> {
                RelationEffect.RelationEffective x = (RelationEffect.RelationEffective) e;
                o.writeInt(x.a());
                o.writeInt(x.b());
                o.writeInt(x.kind().code());
                o.writeInt(x.prevKind().code());
            }
            default -> throw new IllegalStateException("not a relation tag: " + tag);
        }
    }

    public static Effect decode(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case RELATION_DECLARED ->
                    new RelationEffect.RelationDeclared(seq, origin, in.readInt(), in.readInt(),
                            Relation.fromCode((byte) in.readInt()));
            case RELATION_EFFECTIVE ->
                    new RelationEffect.RelationEffective(seq, origin, in.readInt(), in.readInt(),
                            Relation.fromCode((byte) in.readInt()),
                            Relation.fromCode((byte) in.readInt()));
            default -> throw new IllegalStateException("not a relation tag: " + tag);
        };
    }
}
