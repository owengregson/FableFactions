package dev.fablemc.factions.kernel.reduce;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import dev.fablemc.factions.kernel.vocab.FactionAuditAction;
import dev.fablemc.factions.kernel.config.BakedTables;
import dev.fablemc.factions.kernel.config.PowerConfig;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.ChestRules;
import dev.fablemc.factions.kernel.rules.ClaimRules;
import dev.fablemc.factions.kernel.rules.DisbandRules;
import dev.fablemc.factions.kernel.rules.EconomyRules;
import dev.fablemc.factions.kernel.rules.FactionAggregates;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.rules.InviteRules;
import dev.fablemc.factions.kernel.rules.MergeRules;
import dev.fablemc.factions.kernel.rules.MoneyMath;
import dev.fablemc.factions.kernel.rules.NameRules;
import dev.fablemc.factions.kernel.rules.PowerMath;
import dev.fablemc.factions.kernel.rules.PrefRules;
import dev.fablemc.factions.kernel.rules.RelationRules;
import dev.fablemc.factions.kernel.rules.RoleRules;
import dev.fablemc.factions.kernel.rules.TravelRules;
import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.ChestTable;
import dev.fablemc.factions.kernel.state.EscrowTable;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.Home;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.MergeTable;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.kernel.state.RelationKind;
import dev.fablemc.factions.kernel.state.Warp;
import dev.fablemc.factions.kernel.state.WarpTable;
import dev.fablemc.factions.kernel.state.ZoneStats;
import dev.fablemc.factions.kernel.vocab.Relation;
import dev.fablemc.factions.kernel.vocab.PowerSource;
import dev.fablemc.factions.kernel.vocab.PagePhase;
import dev.fablemc.factions.kernel.vocab.NotifyPredicate;
import dev.fablemc.factions.kernel.vocab.InviteRemovalReason;
import dev.fablemc.factions.kernel.vocab.EscrowOutcome;
import dev.fablemc.factions.kernel.vocab.EscrowKind;
import dev.fablemc.factions.kernel.vocab.BroadcastScope;
import dev.fablemc.factions.kernel.vocab.BankTxType;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.kernel.intent.SessionIntent;
import dev.fablemc.factions.kernel.intent.RoleIntent;
import dev.fablemc.factions.kernel.intent.RelationIntent;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.intent.PowerIntent;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.kernel.intent.ClaimIntent;
import dev.fablemc.factions.kernel.intent.ChestIntent;
import dev.fablemc.factions.kernel.effect.TravelEffect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.effect.SessionEffect;
import dev.fablemc.factions.kernel.effect.RoleEffect;
import dev.fablemc.factions.kernel.effect.RelationEffect;
import dev.fablemc.factions.kernel.effect.PrefEffect;
import dev.fablemc.factions.kernel.effect.PowerEffect;
import dev.fablemc.factions.kernel.effect.MembershipEffect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.effect.ExternalEffect;
import dev.fablemc.factions.kernel.effect.EconomyEffect;
import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.ChestEffect;
import dev.fablemc.factions.kernel.effect.AuditEffect;

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

        s.effects.add(new RelationEffect.RelationDeclared(s.seq, s.origin, c.actorFaction(), c.targetFaction(),
                c.kind()));
        if (newEff != prevEff) {
            s.effects.add(new RelationEffect.RelationEffective(s.seq, s.origin, c.actorFaction(),
                    c.targetFaction(), Relation.fromCode(newEff), Relation.fromCode(prevEff)));
        }
        s.audit(c.actorFaction(), c.actor(), FactionAuditAction.RELATION_CHANGE,
                RelationKind.name(kind) + " with " + b.name());
        if (c.kind() == Relation.ENEMY
                || (newEff == RelationKind.ALLY) || (newEff == RelationKind.TRUCE)) {
            s.effects.add(new FeedbackEffect.Broadcast(s.seq, s.origin, BroadcastScope.SERVER,
                    MessageKey.of("relations.announce"),
                    new String[] {a.name(), b.name(), RelationKind.name(c.kind().code())}));
        }
    }
}
