package dev.fablemc.factions.core.storage.legacy;

import java.util.UUID;

import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.Rank;

/**
 * Small parse/default/lookup helpers shared by the {@link PvpIndexImporter} orchestrator and its
 * per-table legacy readers ({@code storage.legacy}).
 *
 * <p><b>Owning thread(s):</b> the boot/migration thread only. <b>Mutability:</b> stateless static
 * helpers.
 */
final class LegacySupport {

    private LegacySupport() {
    }

    static UUID parseUuid(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    static Rank[] defaultRanks() {
        return new Rank[] {
                new Rank(UUID.randomUUID().toString(), Rank.NAME_OWNER, null, Rank.PRIORITY_OWNER),
                new Rank(UUID.randomUUID().toString(), Rank.NAME_OFFICER, null, Rank.PRIORITY_OFFICER),
                new Rank(UUID.randomUUID().toString(), Rank.NAME_MEMBER, null, Rank.PRIORITY_MEMBER),
        };
    }

    static int rankIndex(FactionArena arena, int factionOrdinal, String rankId) {
        if (rankId == null) {
            return 0;
        }
        Faction f = arena.at(factionOrdinal);
        if (f == null || f.ranks() == null) {
            return 0;
        }
        Rank[] ranks = f.ranks();
        for (int i = 0; i < ranks.length; i++) {
            if (rankId.equals(ranks[i].id())) {
                return i;
            }
        }
        return 0;
    }
}
