package dev.fablemc.factions.kernel.state;

import java.util.UUID;

/** Shared builders for minimal state values in kernel structure tests. */
final class FactionTestSupport {

    private FactionTestSupport() {
    }

    /** A minimal, valid {@link Faction} at the given ordinal with a fresh id and given name. */
    static Faction faction(int idx, String name) {
        return new Faction(
                idx,
                UUID.randomUUID(),
                name,
                NameIndex.fold(name),
                UUID.randomUUID(),
                "",
                "",
                0L,
                0.0,
                0.0,
                0L,
                RelationEdges.NO_ORDINALS,
                RelationEdges.NO_KINDS,
                RelationEdges.NO_ORDINALS,
                RelationEdges.NO_KINDS,
                null,
                Faction.NO_SHIELD,
                0,
                false,
                new Rank[0],
                0,
                0.0,
                0L,
                FactionClaimList.empty(),
                "",
                "");
    }
}
