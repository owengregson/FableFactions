package dev.fablemc.factions.kernel.reduce;

import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.effect.RelationEffect;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.RelationIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.rules.RelationRules;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.kernel.state.RelationKind;
import dev.fablemc.factions.kernel.vocab.BroadcastScope;
import dev.fablemc.factions.kernel.vocab.FactionAuditAction;
import dev.fablemc.factions.kernel.vocab.Relation;

/**
 * Relation intents: declare wish / effective relation between two factions.
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved this code unchanged).
 */
final class RelationReducer {

    private RelationReducer() {
    }

    static void reduce(ReduceSupport s, RelationIntent i) {
        if (i instanceof RelationIntent.DeclareRelation x) {
            declareRelation(s, x);
        } else {
            throw new IllegalStateException("unhandled relation intent: " + i.getClass().getName());
        }
    }
    static void declareRelation(ReduceSupport s, RelationIntent.DeclareRelation c) {
        if (!RelationRules.isValidFactionRelation(c.kind().code())) {
            s.reject(ReasonCode.RELATION_SET_FAILED);
            return;
        }
        Faction a = s.resolve(c.actorFaction());
        Faction b = s.resolve(c.targetFaction());
        if (a == null || b == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        if (a.idx() == b.idx()) {
            s.reject(ReasonCode.RELATION_SELF);
            return;
        }
        int actorOrd = s.memberOrd(c.actor());
        if (actorOrd < 0 || FactionHandle.ordinal(s.memberFactionHandle(actorOrd)) != a.idx()) {
            s.reject(ReasonCode.NOT_IN_FACTION);
            return;
        }
        Rank actorRank = s.rankOf(a, actorOrd);
        if (actorRank == null || !actorRank.isOfficerOrAbove()) {
            s.reject(ReasonCode.MUST_BE_OFFICER);
            return;
        }
        byte kind = c.kind().code();
        int aOrd = a.idx();
        int bOrd = b.idx();
        byte prevWishAB = a.relationDeclared(bOrd);
        if (!RelationRules.withinRelationLimit(s.state.config().relation(), a.relOutKind(),
                a.relOut().length, prevWishAB, kind)) {
            s.reject(ReasonCode.RELATION_SET_FAILED);
            return;
        }
        byte prevEff = a.relationEffective(bOrd);

        // Outgoing wish A→B.
        RelationEdges.Edges outAB = RelationEdges.with(a.relOut(), a.relOutKind(),
                a.relOut().length, bOrd, kind);
        byte wishBA;
        if (RelationRules.isBilateral(c.kind().code())) {
            RelationEdges.Edges outBA = RelationEdges.with(b.relOut(), b.relOutKind(),
                    b.relOut().length, aOrd, kind);
            b = FactionEdit.withRelations(b, outBA.ordinals(), outBA.kinds(), b.relEff(),
                    b.relEffKind());
            wishBA = kind == RelationKind.NEUTRAL ? RelationKind.NEUTRAL : kind;
        } else {
            wishBA = b.relationDeclared(aOrd);
        }
        a = FactionEdit.withRelations(a, outAB.ordinals(), outAB.kinds(), a.relEff(), a.relEffKind());

        byte newEff = RelationRules.effectiveKind(kind, wishBA);
        RelationEdges.Edges effAB = RelationEdges.with(a.relEff(), a.relEffKind(),
                a.relEff().length, bOrd, newEff);
        a = FactionEdit.withRelations(a, a.relOut(), a.relOutKind(), effAB.ordinals(), effAB.kinds());
        RelationEdges.Edges effBA = RelationEdges.with(b.relEff(), b.relEffKind(),
                b.relEff().length, aOrd, newEff);
        b = FactionEdit.withRelations(b, b.relOut(), b.relOutKind(), effBA.ordinals(), effBA.kinds());

        s.state = s.state.withFactions(s.state.factions().replace(aOrd, a).replace(bOrd, b));

        s.emit(new RelationEffect.RelationDeclared(s.seq, s.origin, c.actorFaction(), c.targetFaction(),
                c.kind()));
        if (newEff != prevEff) {
            s.emit(new RelationEffect.RelationEffective(s.seq, s.origin, c.actorFaction(),
                    c.targetFaction(), Relation.fromCode(newEff), Relation.fromCode(prevEff)));
        }
        s.audit(c.actorFaction(), c.actor(), FactionAuditAction.RELATION_CHANGE,
                RelationKind.name(kind) + " with " + b.name());
        if (c.kind() == Relation.ENEMY
                || (newEff == RelationKind.ALLY) || (newEff == RelationKind.TRUCE)) {
            s.emit(new FeedbackEffect.Broadcast(s.seq, s.origin, BroadcastScope.SERVER,
                    MessageKey.of("relations.announce"),
                    new String[] {a.name(), b.name(), RelationKind.name(c.kind().code())}));
        }
    }
}
