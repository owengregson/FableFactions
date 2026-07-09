package dev.fablemc.factions.core.storage.load;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.function.ToIntFunction;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.ClaimAtlas;
import dev.fablemc.factions.kernel.state.FactionClaimList;

/**
 * Reads the {@code board} table into the {@link ClaimAtlas} and per-faction claim lists (FableFactions'
 * own schema stores {@code (world, cx, cz)} columns).
 *
 * <p><b>Unloaded worlds (finding #23).</b> A claim in a world not loaded at boot (a lazily-loaded
 * Multiverse world, a world unloaded at runtime) resolves to {@code worldIdx == -1} and cannot enter
 * the {@link ClaimAtlas} (which is keyed by dense worldIdx). Previously such claims were <b>silently
 * dropped</b> — the territory became unprotected and re-claimable while its {@code board} rows still
 * existed. They are still not activated in this slice (full AM-15 lazy activation on
 * {@code WorldLoadEvent} needs a kernel {@code ClaimAtlas} deferred-claim column + a load listener,
 * owned by other agents), but they are no longer silent: each is counted per world and surfaced by
 * {@link BaselineLoader} as a loud boot warning. The {@code board} rows are never deleted, so the
 * activation path is: on {@code WorldLoadEvent} the loader re-reads {@code board} for that world into
 * the atlas (documented in the finding report).
 *
 * <p><b>Owning thread(s):</b> the boot thread only. <b>Mutability:</b> stateless static reader; it
 * fills the caller's {@link ClaimAtlas.Builder}, claim map, and the per-world deferred counter.
 */
final class BoardRows {

    private BoardRows() {
    }

    static int readBoard(Connection conn, Map<String, Integer> ordinalByFactionId,
                         ClaimAtlas.Builder atlas, Map<Integer, FactionClaimList> claimsByOrdinal,
                         ToIntFunction<String> worldIndex, Map<String, Integer> deferredByWorld,
                         Progress progress) throws SQLException {
        int count = 0;
        String sql = "SELECT `world`,`cx`,`cz`,`faction_id` FROM `board`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                progress.tick();
                Integer ordinal = ordinalByFactionId.get(rs.getString("faction_id"));
                String world = rs.getString("world");
                if (ordinal == null || world == null) {
                    continue;
                }
                int wi = worldIndex.applyAsInt(world);
                if (wi < 0) {
                    // World not loaded at boot: retain the count (do NOT drop silently) so boot warns
                    // loudly and an admin knows the territory is inactive until the world loads.
                    deferredByWorld.merge(world, 1, Integer::sum);
                    continue;
                }
                long key = ChunkKeys.key(rs.getInt("cx"), rs.getInt("cz"));
                atlas.put(wi, key, FactionHandle.handle(0, ordinal));
                FactionClaimList list = claimsByOrdinal.getOrDefault(ordinal, FactionClaimList.empty());
                claimsByOrdinal.put(ordinal, list.add(wi, key));
                count++;
            }
        }
        return count;
    }
}
