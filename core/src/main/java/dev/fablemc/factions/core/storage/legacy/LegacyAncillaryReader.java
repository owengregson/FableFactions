package dev.fablemc.factions.core.storage.legacy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.ChestTable;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.Warp;
import dev.fablemc.factions.kernel.state.WarpTable;

/**
 * Reads the reference ancillary tables — ranks, warps, team chests (contents migrate to the blob
 * store separately, so the imported {@link ChestRef} starts empty), and invitations — plus the
 * loss-tolerant LEDGER-tier history count ({@code power_history} + {@code bank_transactions}).
 *
 * <p><b>Owning thread(s):</b> the boot/migration thread only. <b>Mutability:</b> stateless static
 * reader; the produced tables are immutable.
 */
final class LegacyAncillaryReader {

    private LegacyAncillaryReader() {
    }

    static Map<Integer, List<Rank>> readRanks(Connection conn, Map<String, Integer> ordinalByFactionId)
            throws SQLException {
        Map<Integer, List<Rank>> byOrdinal = new HashMap<>();
        String sql = "SELECT `id`,`faction_id`,`name`,`prefix`,`priority` FROM `ranks`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Integer ordinal = ordinalByFactionId.get(rs.getString("faction_id"));
                if (ordinal == null) {
                    continue;
                }
                byOrdinal.computeIfAbsent(ordinal, k -> new ArrayList<>())
                        .add(new Rank(rs.getString("id"), rs.getString("name"), rs.getString("prefix"),
                                rs.getInt("priority")));
            }
        }
        for (List<Rank> list : byOrdinal.values()) {
            list.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        }
        return byOrdinal;
    }

    static WarpTable readWarps(Connection conn, Map<String, Integer> ordinalByFactionId,
                               ToIntFunction<String> worldIndex) throws SQLException {
        WarpTable table = WarpTable.empty();
        String sql = "SELECT `faction_id`,`name`,`world`,`x`,`y`,`z`,`yaw`,`pitch`,`creator_id`,"
                + "`created_at`,`password`,`use_cost` FROM `warps`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
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
                        LegacySupport.parseUuid(rs.getString("creator_id")), rs.getLong("created_at"),
                        rs.getString("password"), rs.getDouble("use_cost"));
                table = table.set(ordinal, warp);
            }
        }
        return table;
    }

    static ChestTable readChests(Connection conn, Map<String, Integer> ordinalByFactionId)
            throws SQLException {
        ChestTable table = ChestTable.empty();
        String sql = "SELECT `faction_id`,`name`,`created_at` FROM `team_chests`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Integer ordinal = ordinalByFactionId.get(rs.getString("faction_id"));
                String name = rs.getString("name");
                if (ordinal == null || name == null) {
                    continue;
                }
                // Reference stored contents inline (Base64 TEXT); the byte payload migrates to the
                // blob store separately, so the imported ChestRef starts with the empty-blob sentinel.
                table = table.set(ordinal, new ChestRef(name.trim().toLowerCase(Locale.ROOT),
                        ChestRef.EMPTY_BLOB, rs.getLong("created_at")));
            }
        }
        return table;
    }

    static InviteTable readInvites(Connection conn, Map<String, Integer> ordinalByFactionId)
            throws SQLException {
        InviteTable table = InviteTable.empty();
        long inviteSeq = 1L;
        String sql = "SELECT `faction_id`,`invitee_id`,`inviter_id`,`created_at` FROM `invitations`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Integer ordinal = ordinalByFactionId.get(rs.getString("faction_id"));
                UUID invitee = LegacySupport.parseUuid(rs.getString("invitee_id"));
                if (ordinal == null || invitee == null) {
                    continue;
                }
                table = table.add(new InviteTable.Invite(inviteSeq++, ordinal,
                        LegacySupport.parseUuid(rs.getString("inviter_id")), invitee,
                        rs.getLong("created_at")));
            }
        }
        return table;
    }

    static long countHistory(Connection conn) {
        return safeCount(conn, "power_history") + safeCount(conn, "bank_transactions");
    }

    private static long safeCount(Connection conn, String table) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM `" + table + "`");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException absent) {
            return 0L;   // table not present in this legacy DB — nothing to carry forward
        }
    }
}
