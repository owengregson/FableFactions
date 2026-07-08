package dev.fablemc.factions.core.storage.legacy;

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
 * Reads the reference {@code board} table, whose PK is the string {@code "world:chunkX:chunkZ"}
 * (pvp-data §3.3), into the {@link ClaimAtlas} and per-faction claim lists. World names never
 * contain {@code ':'}, so the world portion is everything before the last two colons.
 *
 * <p><b>Owning thread(s):</b> the boot/migration thread only. <b>Mutability:</b> stateless static
 * reader; it fills the caller's {@link ClaimAtlas.Builder} and claim map.
 */
final class LegacyBoardReader {

    private LegacyBoardReader() {
    }

    static int readBoard(Connection conn, Map<String, Integer> ordinalByFactionId,
                         ClaimAtlas.Builder atlas, Map<Integer, FactionClaimList> claimsByOrdinal,
                         ToIntFunction<String> worldIndex) throws SQLException {
        int count = 0;
        String sql = "SELECT `id`,`faction_id` FROM `board`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Integer ordinal = ordinalByFactionId.get(rs.getString("faction_id"));
                long[] parsed = parseBoardId(rs.getString("id"));
                if (ordinal == null || parsed == null) {
                    continue;
                }
                int wi = worldIndex.applyAsInt(worldOf(rs.getString("id")));
                if (wi < 0) {
                    continue;
                }
                long key = ChunkKeys.key((int) parsed[0], (int) parsed[1]);
                atlas.put(wi, key, FactionHandle.handle(0, ordinal));
                FactionClaimList list = claimsByOrdinal.getOrDefault(ordinal, FactionClaimList.empty());
                claimsByOrdinal.put(ordinal, list.add(wi, key));
                count++;
            }
        }
        return count;
    }

    /** Parses {@code "world:cx:cz"} into {@code [cx, cz]}; {@code null} if malformed (pvp-data §3.3). */
    private static long[] parseBoardId(String boardId) {
        if (boardId == null) {
            return null;
        }
        int lastColon = boardId.lastIndexOf(':');
        int prevColon = lastColon < 0 ? -1 : boardId.lastIndexOf(':', lastColon - 1);
        if (lastColon < 0 || prevColon < 0) {
            return null;
        }
        try {
            int cx = Integer.parseInt(boardId.substring(prevColon + 1, lastColon));
            int cz = Integer.parseInt(boardId.substring(lastColon + 1));
            return new long[] {cx, cz};
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    /** World name portion of a {@code "world:cx:cz"} id (world names never contain ':', pvp-data §3.3). */
    private static String worldOf(String boardId) {
        if (boardId == null) {
            return null;
        }
        int lastColon = boardId.lastIndexOf(':');
        int prevColon = lastColon < 0 ? -1 : boardId.lastIndexOf(':', lastColon - 1);
        return prevColon < 0 ? null : boardId.substring(0, prevColon);
    }
}
