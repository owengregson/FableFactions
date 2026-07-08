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
 * <p><b>Owning thread(s):</b> the boot thread only. <b>Mutability:</b> stateless static reader; it
 * fills the caller's {@link ClaimAtlas.Builder} and claim map.
 */
final class BoardRows {

    private BoardRows() {
    }

    static int readBoard(Connection conn, Map<String, Integer> ordinalByFactionId,
                         ClaimAtlas.Builder atlas, Map<Integer, FactionClaimList> claimsByOrdinal,
                         ToIntFunction<String> worldIndex, Progress progress) throws SQLException {
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
