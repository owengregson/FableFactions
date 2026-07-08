package dev.fablemc.factions.kernel.effect;

import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.vocab.Relation;

/**
 * Relation effects: the declared wish and the resulting effective relation.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * The {@code kind}/{@code prevKind} carry the cold-path {@link Relation} enum (the hot relation-edge
 * arrays keep the raw {@code RelationKind} byte). See {@link Effect} for the hierarchy contract.
 */
public sealed interface RelationEffect extends Effect
        permits RelationEffect.RelationDeclared, RelationEffect.RelationEffective {

    record RelationDeclared(long seq, Origin origin, int a, int b, Relation kind)
            implements RelationEffect {
    }

    record RelationEffective(long seq, Origin origin, int a, int b, Relation kind, Relation prevKind)
            implements RelationEffect {
    }
}
