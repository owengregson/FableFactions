package dev.fablemc.factions.core.storage.load;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.ChestTable;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.MergeTable;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.Warp;
import dev.fablemc.factions.kernel.state.WarpTable;

/**
 * Reads the ancillary FableFactions tables into their kernel structures: ranks, warps, team chests,
 * invitations, and merge requests.
 *
 * <p><b>Owning thread(s):</b> the boot thread only. <b>Mutability:</b> stateless static reader; the
 * produced tables are immutable.
 */
final class AncillaryRows {

    private AncillaryRows() {
    }

    static Map<Integer, List<Rank>> readRanks(Connection conn, Map<String, Integer> ordinalByFactionId,
                                              Progress progress) throws SQLException {
        Map<Integer, List<Rank>> byOrdinal = new HashMap<>();
        String sql = "SELECT `id`,`faction_id`,`name`,`prefix`,`priority` FROM `ranks`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                progress.tick();
                Integer ordinal = ordinalByFactionId.get(rs.getString("faction_id"));
                if (ordinal == null) {
                    continue;
                }
                Rank rank = new Rank(rs.getString("id"), rs.getString("name"), rs.getString("prefix"),
                        rs.getInt("priority"));
                byOrdinal.computeIfAbsent(ordinal, k -> new ArrayList<>()).add(rank);
            }
        }
        // Highest authority first, so index 0 is the owner rank.
        for (List<Rank> list : byOrdinal.values()) {
            list.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        }
        return byOrdinal;
    }

    static WarpTable readWarps(Connection conn, Map<String, Integer> ordinalByFactionId,
                               java.util.function.ToIntFunction<String> worldIndex, Progress progress)
            throws SQLException {
        WarpTable table = WarpTable.empty();
        String sql = "SELECT `faction_id`,`name`,`world`,`x`,`y`,`z`,`yaw`,`pitch`,`creator_id`,"
                + "`created_at`,`password`,`use_cost` FROM `warps`";
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
                Warp warp = new Warp(rs.getString("name"), wi, rs.getDouble("x"), rs.getDouble("y"),
                        rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch"),
                        LoadSupport.parseUuid(rs.getString("creator_id")), rs.getLong("created_at"),
                        rs.getString("password"), rs.getDouble("use_cost"));
                table = table.set(ordinal, warp);
            }
        }
        return table;
    }

    static ChestTable readChests(Connection conn, Map<String, Integer> ordinalByFactionId,
                                 Progress progress) throws SQLException {
        ChestTable table = ChestTable.empty();
        String sql = "SELECT `faction_id`,`name`,`blob_ref`,`created_at` FROM `team_chests`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                progress.tick();
                Integer ordinal = ordinalByFactionId.get(rs.getString("faction_id"));
                String name = rs.getString("name");
                if (ordinal == null || name == null) {
                    continue;
                }
                long blobRef = rs.getLong("blob_ref");
                ChestRef chest = new ChestRef(name.trim().toLowerCase(Locale.ROOT), blobRef,
                        rs.getLong("created_at"));
                table = table.set(ordinal, chest);
            }
        }
        return table;
    }

    static InviteTable readInvites(Connection conn, Map<String, Integer> ordinalByFactionId,
                                   Progress progress) throws SQLException {
        InviteTable table = InviteTable.empty();
        long inviteSeq = 1L;
        String sql = "SELECT `faction_id`,`invitee_id`,`inviter_id`,`created_at` FROM `invitations`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                progress.tick();
                Integer ordinal = ordinalByFactionId.get(rs.getString("faction_id"));
                java.util.UUID invitee = LoadSupport.parseUuid(rs.getString("invitee_id"));
                if (ordinal == null || invitee == null) {
                    continue;
                }
                table = table.add(new InviteTable.Invite(inviteSeq++, ordinal,
                        LoadSupport.parseUuid(rs.getString("inviter_id")), invitee,
                        rs.getLong("created_at")));
            }
        }
        return table;
    }

    static MergeTable readMerges(Connection conn, Map<String, Integer> ordinalByFactionId,
                                 Progress progress) throws SQLException {
        MergeTable table = MergeTable.empty();
        long mergeSeq = 1L;
        String sql = "SELECT `sender_faction_id`,`target_faction_id`,`actor_id`,`created_at` "
                + "FROM `merge_requests`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                progress.tick();
                Integer sender = ordinalByFactionId.get(rs.getString("sender_faction_id"));
                Integer target = ordinalByFactionId.get(rs.getString("target_faction_id"));
                if (sender == null || target == null) {
                    continue;
                }
                table = table.add(new MergeTable.MergeRequest(mergeSeq++, sender, target,
                        LoadSupport.parseUuid(rs.getString("actor_id")), rs.getLong("created_at")));
            }
        }
        return table;
    }
}
