package dev.fablemc.factions.core.storage.load;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import dev.fablemc.factions.kernel.state.Rank;

/**
 * Small parse/count/default helpers shared by the {@link BaselineLoader} orchestrator and its
 * per-table readers ({@code storage.load}).
 *
 * <p><b>Owning thread(s):</b> the boot thread only. <b>Mutability:</b> stateless static helpers.
 */
final class LoadSupport {

    private LoadSupport() {
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

    static long count(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM `" + table + "`");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    static Rank[] defaultRanks() {
        return new Rank[] {
                new Rank(UUID.randomUUID().toString(), Rank.NAME_OWNER, null, Rank.PRIORITY_OWNER),
                new Rank(UUID.randomUUID().toString(), Rank.NAME_OFFICER, null, Rank.PRIORITY_OFFICER),
                new Rank(UUID.randomUUID().toString(), Rank.NAME_MEMBER, null, Rank.PRIORITY_MEMBER),
        };
    }
}
