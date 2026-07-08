package dev.fablemc.factions.core.storage.load;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.ToIntFunction;

import dev.fablemc.factions.core.storage.LegacyImportSupport;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.Home;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.kernel.state.RelationKind;

/**
 * Reads the {@code factions} table and folds each row (plus its ranks/claims and relation wishes)
 * into an immutable {@link Faction} inside a fresh {@link FactionArena} (proposal-C §6.3). Faction
 * ordinals are assigned densely from {@link FactionHandle#FIRST_NORMAL_ORDINAL}; generation stays
 * 0. Relation wishes fold into effective symmetric edges via {@link LegacyImportSupport}.
 *
 * <p><b>Owning thread(s):</b> the boot thread only. <b>Mutability:</b> stateless static reader; the
 * built {@link FactionArena}/{@link NameIndex} are immutable.
 */
final class FactionRows {

    private FactionRows() {
    }

    static List<FactionRow> readFactions(Connection conn, Map<String, Integer> ordinalByFactionId,
                                         Map<Integer, UUID> handleToId, Progress progress)
            throws SQLException {
        List<FactionRow> rows = new ArrayList<>();
        int nextOrdinal = FactionHandle.FIRST_NORMAL_ORDINAL;
        String sql = "SELECT `id`,`name`,`owner_id`,`description`,`motd`,`created_at`,`power_boost`,"
                + "`money`,`flags_json`,`relations_json`,`home_world`,`home_x`,`home_y`,`home_z`,"
                + "`home_yaw`,`home_pitch`,`is_raidable`,`shield_start_hour`,`shield_duration_hours` "
                + "FROM `factions`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String id = rs.getString("id");
                if (id == null) {
                    continue;
                }
                int ordinal = nextOrdinal++;
                ordinalByFactionId.put(id, ordinal);
                handleToId.put(FactionHandle.handle(0, ordinal), UUID.fromString(id));
                FactionRow row = new FactionRow();
                row.ordinal = ordinal;
                row.id = UUID.fromString(id);
                row.name = rs.getString("name");
                row.ownerId = LoadSupport.parseUuid(rs.getString("owner_id"));
                row.description = rs.getString("description");
                row.motd = rs.getString("motd");
                row.createdAt = rs.getLong("created_at");
                row.powerBoost = rs.getDouble("power_boost");
                row.money = rs.getDouble("money");
                row.flagsJson = rs.getString("flags_json");
                row.relationsJson = rs.getString("relations_json");
                row.homeWorld = rs.getString("home_world");
                row.homeX = rs.getDouble("home_x");
                row.homeY = rs.getDouble("home_y");
                row.homeZ = rs.getDouble("home_z");
                row.homeYaw = rs.getFloat("home_yaw");
                row.homePitch = rs.getFloat("home_pitch");
                row.raidable = rs.getInt("is_raidable") == 1;
                row.shieldStartHour = rs.getInt("shield_start_hour");
                if (rs.wasNull()) {
                    row.shieldStartHour = Faction.NO_SHIELD;
                }
                row.shieldDurationHours = rs.getInt("shield_duration_hours");
                rows.add(row);
                progress.tick();
            }
        }
        return rows;
    }

    static BuiltFactions buildFactions(List<FactionRow> rows, Map<String, Integer> ordinalByFactionId,
                                       Map<Integer, List<Rank>> ranksByOrdinal,
                                       Map<Integer, FactionClaimList> claimsByOrdinal,
                                       ToIntFunction<String> worldIndex) {
        // Pass 1: resolve each faction's declared wishes into other-ordinal → kind maps.
        Map<Integer, Map<Integer, Byte>> declared = new HashMap<>();
        for (FactionRow row : rows) {
            Map<Integer, Byte> edges = new TreeMap<>();
            for (Map.Entry<String, Byte> e : LegacyImportSupport.parseRelations(row.relationsJson)
                    .entrySet()) {
                Integer other = ordinalByFactionId.get(e.getKey());
                if (other != null && other != row.ordinal) {
                    edges.put(other, e.getValue());
                }
            }
            declared.put(row.ordinal, edges);
        }
        // Pass 2: build each Faction with sorted relOut (wishes) + relEff (mutual-folded edges).
        FactionArena arena = FactionArena.empty();
        NameIndex names = NameIndex.empty();
        for (FactionRow row : rows) {
            Map<Integer, Byte> wishes = declared.get(row.ordinal);
            int[] relOut = new int[wishes.size()];
            byte[] relOutKind = new byte[wishes.size()];
            int i = 0;
            for (Map.Entry<Integer, Byte> e : wishes.entrySet()) {
                relOut[i] = e.getKey();
                relOutKind[i] = e.getValue();
                i++;
            }
            TreeMap<Integer, Byte> effective = new TreeMap<>();
            for (Map.Entry<Integer, Byte> e : wishes.entrySet()) {
                int other = e.getKey();
                byte back = declared.getOrDefault(other, Map.of())
                        .getOrDefault(row.ordinal, RelationKind.NEUTRAL);
                byte eff = LegacyImportSupport.effectiveKind(e.getValue(), back);
                if (eff != RelationKind.NEUTRAL) {
                    effective.put(other, eff);
                }
            }
            // Enemies are symmetric even when only the OTHER side declared them.
            for (Map.Entry<Integer, Map<Integer, Byte>> other : declared.entrySet()) {
                Byte otherToUs = other.getValue().get(row.ordinal);
                if (otherToUs != null && otherToUs == RelationKind.ENEMY) {
                    effective.put(other.getKey(), RelationKind.ENEMY);
                }
            }
            int[] relEff = new int[effective.size()];
            byte[] relEffKind = new byte[effective.size()];
            i = 0;
            for (Map.Entry<Integer, Byte> e : effective.entrySet()) {
                relEff[i] = e.getKey();
                relEffKind[i] = e.getValue();
                i++;
            }
            if (relOut.length == 0) {
                relOut = RelationEdges.NO_ORDINALS;
                relOutKind = RelationEdges.NO_KINDS;
            }
            if (relEff.length == 0) {
                relEff = RelationEdges.NO_ORDINALS;
                relEffKind = RelationEdges.NO_KINDS;
            }

            Home home = null;
            if (row.homeWorld != null) {
                int wi = worldIndex.applyAsInt(row.homeWorld);
                if (wi >= 0) {
                    home = new Home(wi, row.homeX, row.homeY, row.homeZ, row.homeYaw, row.homePitch);
                }
            }
            List<Rank> rankList = ranksByOrdinal.get(row.ordinal);
            Rank[] ranks = (rankList == null || rankList.isEmpty())
                    ? LoadSupport.defaultRanks() : rankList.toArray(new Rank[0]);
            FactionClaimList claims = claimsByOrdinal.getOrDefault(row.ordinal,
                    FactionClaimList.empty());
            long flagBits = LegacyImportSupport.parseFlags(row.flagsJson, 0L);

            Faction faction = new Faction(
                    row.ordinal, row.id, row.name, NameIndex.fold(row.name), row.ownerId,
                    row.description, row.motd, row.createdAt, row.powerBoost, row.money, flagBits,
                    relOut, relOutKind, relEff, relEffKind, home, row.shieldStartHour,
                    row.shieldDurationHours, row.raidable, ranks, claims.count(), 0.0, 0L,
                    claims, null, null);
            arena = arena.withFaction(row.ordinal, faction);
            if (row.name != null) {
                names = names.with(NameIndex.fold(row.name), row.ordinal);
            }
        }
        return new BuiltFactions(arena, names);
    }

    static final class FactionRow {
        int ordinal;
        UUID id;
        String name;
        UUID ownerId;
        String description;
        String motd;
        long createdAt;
        double powerBoost;
        double money;
        String flagsJson;
        String relationsJson;
        String homeWorld;
        double homeX;
        double homeY;
        double homeZ;
        float homeYaw;
        float homePitch;
        boolean raidable;
        int shieldStartHour;
        int shieldDurationHours;
    }

    record BuiltFactions(FactionArena arena, NameIndex names) {
    }
}
