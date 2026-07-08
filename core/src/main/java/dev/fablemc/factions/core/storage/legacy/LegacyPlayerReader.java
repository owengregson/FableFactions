package dev.fablemc.factions.core.storage.legacy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.MemberDirectory;
import dev.fablemc.factions.kernel.state.PlayerLedger;

/**
 * Reads the reference {@code players} table (TINYINT prefs; no {@code overriding} column) into the
 * {@link PlayerLedger} and {@link MemberDirectory}.
 *
 * <p><b>Owning thread(s):</b> the boot/migration thread only. <b>Mutability:</b> stateless static
 * reader; the produced ledger/directory are immutable.
 */
final class LegacyPlayerReader {

    private LegacyPlayerReader() {
    }

    static PlayerAggregate readPlayers(Connection conn, Map<String, Integer> ordinalByFactionId,
                                       FactionArena arena) throws SQLException {
        PlayerLedger ledger = PlayerLedger.empty();
        MemberDirectory members = MemberDirectory.empty();
        int memberCount = 0;
        String sql = "SELECT `id`,`faction_id`,`rank_id`,`power_boost`,`power`,`joined_at`,"
                + "`last_activity`,`auto_territory_mode`,`last_death_at`,`death_streak`,`power_frozen` "
                + "FROM `players`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String idStr = rs.getString("id");
                if (idStr == null) {
                    continue;
                }
                UUID id = UUID.fromString(idStr);
                int ord = ledger.nextOrdinal();
                ledger = ledger.withNewMember(ord, id, null);
                Integer factionOrdinal = ordinalByFactionId.get(rs.getString("faction_id"));
                if (factionOrdinal != null) {
                    ledger = ledger.withFactionHandle(ord, FactionHandle.handle(0, factionOrdinal));
                    ledger = ledger.withRankIdx(ord,
                            LegacySupport.rankIndex(arena, factionOrdinal, rs.getString("rank_id")));
                } else {
                    ledger = ledger.withFactionHandle(ord, FactionHandle.WILDERNESS);
                }
                ledger = ledger.withPowerBoost(ord, rs.getDouble("power_boost"));
                ledger = ledger.withPower(ord, rs.getDouble("power"), 0L);
                ledger = ledger.withJoinedAt(ord, rs.getLong("joined_at"));
                ledger = ledger.withLastActivity(ord, rs.getLong("last_activity"));
                ledger = ledger.withDeath(ord, rs.getInt("death_streak"), rs.getLong("last_death_at"));
                if (rs.getInt("power_frozen") == 1) {
                    ledger = ledger.withPowerFrozen(ord, true);
                }
                ledger = ledger.withPrefsBits(ord,
                        PlayerLedger.withAutoMode(0, rs.getInt("auto_territory_mode")));
                members = members.withMapping(id, ord);
                memberCount++;
            }
        }
        return new PlayerAggregate(ledger, members, memberCount);
    }

    record PlayerAggregate(PlayerLedger ledger, MemberDirectory members, int memberCount) {
    }
}
