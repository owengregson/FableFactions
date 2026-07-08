package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.RelationEdges;

/**
 * Disband-scrub helpers (AM-6). Removing all inbound references to a vanishing faction — relation
 * edges (both directions), invites, merge requests, warps, chests, open escrows — is what makes
 * ordinal reuse safe (a later faction at the same ordinal can never inherit a stale reference).
 *
 * <p><b>Owning thread(s):</b> the reducer's disband final page. <b>Mutability:</b> stateless.
 * <b>Reducer rule:</b> the scrub runs before the generation bump; invites/merges/warps/chests use
 * their tables' own {@code removeFaction}/{@code removeInvolving} methods, and escrows are settled
 * with a refund. This helper covers the relation edges, which live on every other faction record.
 */
public final class DisbandRules {

    private DisbandRules() {
    }

    /**
     * Returns a copy of {@code arena} with every other live faction's outgoing-wish and effective
     * relation edge toward {@code victimOrdinal} removed. The victim slot itself is untouched (the
     * caller frees it afterward).
     */
    public static FactionArena scrubRelations(FactionArena arena, int victimOrdinal) {
        FactionArena result = arena;
        int hw = arena.highWater();
        for (int i = 0; i < hw; i++) {
            if (i == victimOrdinal) {
                continue;
            }
            Faction f = result.at(i);
            if (f == null) {
                continue;
            }
            int[] relOut = f.relOut();
            byte[] relOutKind = f.relOutKind();
            int[] relEff = f.relEff();
            byte[] relEffKind = f.relEffKind();
            boolean hasOut = RelationEdges.indexOf(relOut, relOut.length, victimOrdinal) >= 0;
            boolean hasEff = RelationEdges.indexOf(relEff, relEff.length, victimOrdinal) >= 0;
            if (!hasOut && !hasEff) {
                continue;
            }
            RelationEdges.Edges outE = RelationEdges.without(relOut, relOutKind, relOut.length,
                    victimOrdinal);
            RelationEdges.Edges effE = RelationEdges.without(relEff, relEffKind, relEff.length,
                    victimOrdinal);
            Faction scrubbed = FactionEdit.withRelations(f, outE.ordinals(), outE.kinds(),
                    effE.ordinals(), effE.kinds());
            result = result.replace(i, scrubbed);
        }
        return result;
    }
}
