package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

import dev.fablemc.factions.kernel.vocab.Relation;

/**
 * Faction relation intents.
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. The {@code kind} carries the cold-path
 * {@link Relation} enum (the hot relation-edge arrays keep the raw {@code RelationKind} byte).
 * See {@link Intent} for the hierarchy contract.
 */
public sealed interface RelationIntent extends Intent permits RelationIntent.DeclareRelation {

    /** Declare relation {@code kind} from {@code actorFaction} toward {@code targetFaction}. */
    record DeclareRelation(int actorFaction, int targetFaction, Relation kind, UUID actor)
            implements RelationIntent {
    }
}
