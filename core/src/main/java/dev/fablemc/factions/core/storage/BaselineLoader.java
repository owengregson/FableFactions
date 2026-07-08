package dev.fablemc.factions.core.storage;

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
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;
import javax.sql.DataSource;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.ChestTable;
import dev.fablemc.factions.kernel.state.ClaimAtlas;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.Home;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.MemberDirectory;
import dev.fablemc.factions.kernel.state.MergeTable;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.kernel.state.WarpTable;

/**
 * Boot-time loader that streams FableFactions' own relational projection back into an initial
 * {@link KernelState} (proposal-C §6.3). Boot is <em>load DB → replay journal tail</em>; this is
 * the load half. Every table is read once, single-threaded, before the pipeline serves, and the
 * kernel structures are filled through their COW/builder surfaces ({@link ClaimAtlas.Builder},
 * {@link FactionArena#withFaction}, {@link PlayerLedger#withNewMember} …).
 *
 * <p>Faction ordinals are assigned densely from {@link FactionHandle#FIRST_NORMAL_ORDINAL}; a
 * freshly built arena keeps generation 0, so a faction's handle is
 * {@code FactionHandle.handle(0, ordinal)} — the value stored in claim owner slots and returned
 * to the caller so it can seed the {@link StorageProjector}'s handle→id map. Relation wishes are
 * folded into effective symmetric edges by {@link LegacyImportSupport} (asymmetric ALLY stays a
 * wish). Progress is logged every 10% of total rows.
 *
 * <p><b>Owning thread(s):</b> the boot thread only. <b>Mutability:</b> confined; the returned
 * {@link KernelState} is immutable.
 */
public final class BaselineLoader {

    private final DataSource dataSource;
    private final ToIntFunction<String> worldIndex;   // world name → dense worldIdx (AM-15)
    private final Logger log;

    public BaselineLoader(DataSource dataSource, ToIntFunction<String> worldIndex, Logger log) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.worldIndex = Objects.requireNonNull(worldIndex, "worldIndex");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** The loaded state plus the handle→id map the projector must be seeded with. */
    public record Result(KernelState state, Map<Integer, UUID> factionHandleToId,
                         int factionCount, int memberCount, int claimCount) {
    }

    /** Streams every table into a fresh {@link KernelState} carrying {@code config}. */
    public Result load(ConfigImage config) throws SQLException {
        Objects.requireNonNull(config, "config");
        try (Connection conn = dataSource.getConnection()) {
            long total = count(conn, "factions") + count(conn, "ranks") + count(conn, "board")
                    + count(conn, "players") + count(conn, "warps") + count(conn, "team_chests")
                    + count(conn, "invitations") + count(conn, "merge_requests");
            Progress progress = new Progress(total, log);

            Map<String, Integer> ordinalByFactionId = new HashMap<>();
            Map<Integer, UUID> handleToId = new HashMap<>();
            List<FactionRow> factionRows = readFactions(conn, ordinalByFactionId, handleToId, progress);
            Map<Integer, List<Rank>> ranksByOrdinal = readRanks(conn, ordinalByFactionId, progress);
            ClaimAtlas.Builder atlas = new ClaimAtlas.Builder();
            Map<Integer, FactionClaimList> claimsByOrdinal = new HashMap<>();
            int claimCount = readBoard(conn, ordinalByFactionId, atlas, claimsByOrdinal, progress);

            BuiltFactions built = buildFactions(factionRows, ordinalByFactionId, ranksByOrdinal,
                    claimsByOrdinal);
            PlayerAggregate players = readPlayers(conn, ordinalByFactionId, built.arena, progress);
            WarpTable warps = readWarps(conn, ordinalByFactionId, progress);
            ChestTable chests = readChests(conn, ordinalByFactionId, progress);
            InviteTable invites = readInvites(conn, ordinalByFactionId, progress);
            MergeTable merges = readMerges(conn, ordinalByFactionId, progress);

            KernelState state = KernelState.empty(config)
                    .withFactions(built.arena)
                    .withFactionNames(built.names)
                    .withLedger(players.ledger)
                    .withMembers(players.members)
                    .withClaims(atlas.build())
                    .withWarps(warps)
                    .withChests(chests)
                    .withInvites(invites)
                    .withMergeRequests(merges);

            log.info("[baseline] loaded " + factionRows.size() + " faction(s), "
                    + players.memberCount + " member(s), " + claimCount + " claim(s)");
            return new Result(state, handleToId, factionRows.size(), players.memberCount, claimCount);
        }
    }

    // ── factions ──────────────────────────────────────────────────────────────────────────

    private List<FactionRow> readFactions(Connection conn, Map<String, Integer> ordinalByFactionId,
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
                row.ownerId = parseUuid(rs.getString("owner_id"));
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

    private BuiltFactions buildFactions(List<FactionRow> rows, Map<String, Integer> ordinalByFactionId,
                                        Map<Integer, List<Rank>> ranksByOrdinal,
                                        Map<Integer, FactionClaimList> claimsByOrdinal) {
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
                        .getOrDefault(row.ordinal, dev.fablemc.factions.kernel.state.RelationKind.NEUTRAL);
                byte eff = LegacyImportSupport.effectiveKind(e.getValue(), back);
                if (eff != dev.fablemc.factions.kernel.state.RelationKind.NEUTRAL) {
                    effective.put(other, eff);
                }
            }
            // Enemies are symmetric even when only the OTHER side declared them.
            for (Map.Entry<Integer, Map<Integer, Byte>> other : declared.entrySet()) {
                Byte otherToUs = other.getValue().get(row.ordinal);
                if (otherToUs != null
                        && otherToUs == dev.fablemc.factions.kernel.state.RelationKind.ENEMY) {
                    effective.put(other.getKey(), dev.fablemc.factions.kernel.state.RelationKind.ENEMY);
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
                    ? defaultRanks() : rankList.toArray(new Rank[0]);
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

    // ── ranks ─────────────────────────────────────────────────────────────────────────────

    private Map<Integer, List<Rank>> readRanks(Connection conn, Map<String, Integer> ordinalByFactionId,
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

    private static Rank[] defaultRanks() {
        return new Rank[] {
                new Rank(UUID.randomUUID().toString(), Rank.NAME_OWNER, null, Rank.PRIORITY_OWNER),
                new Rank(UUID.randomUUID().toString(), Rank.NAME_OFFICER, null, Rank.PRIORITY_OFFICER),
                new Rank(UUID.randomUUID().toString(), Rank.NAME_MEMBER, null, Rank.PRIORITY_MEMBER),
        };
    }

    // ── board (claims) ────────────────────────────────────────────────────────────────────

    private int readBoard(Connection conn, Map<String, Integer> ordinalByFactionId,
                          ClaimAtlas.Builder atlas, Map<Integer, FactionClaimList> claimsByOrdinal,
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

    // ── players ───────────────────────────────────────────────────────────────────────────

    private PlayerAggregate readPlayers(Connection conn, Map<String, Integer> ordinalByFactionId,
                                        FactionArena arena, Progress progress) throws SQLException {
        PlayerLedger ledger = PlayerLedger.empty();
        MemberDirectory members = MemberDirectory.empty();
        int memberCount = 0;
        String sql = "SELECT `id`,`faction_id`,`rank_id`,`power_boost`,`power`,`joined_at`,"
                + "`last_activity`,`overriding`,`auto_territory_mode`,`last_death_at`,`death_streak`,"
                + "`power_frozen` FROM `players`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                progress.tick();
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
                            rankIndex(arena, factionOrdinal, rs.getString("rank_id")));
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
                int prefs = PlayerLedger.withAutoMode(0, rs.getInt("auto_territory_mode"));
                ledger = ledger.withPrefsBits(ord, prefs);
                members = members.withMapping(id, ord);
                memberCount++;
            }
        }
        return new PlayerAggregate(ledger, members, memberCount);
    }

    private static int rankIndex(FactionArena arena, int factionOrdinal, String rankId) {
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

    // ── warps / chests / invites / merges ─────────────────────────────────────────────────

    private WarpTable readWarps(Connection conn, Map<String, Integer> ordinalByFactionId,
                                Progress progress) throws SQLException {
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
                dev.fablemc.factions.kernel.state.Warp warp =
                        new dev.fablemc.factions.kernel.state.Warp(rs.getString("name"), wi,
                                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                                rs.getFloat("yaw"), rs.getFloat("pitch"),
                                parseUuid(rs.getString("creator_id")), rs.getLong("created_at"),
                                rs.getString("password"), rs.getDouble("use_cost"));
                table = table.set(ordinal, warp);
            }
        }
        return table;
    }

    private ChestTable readChests(Connection conn, Map<String, Integer> ordinalByFactionId,
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

    private InviteTable readInvites(Connection conn, Map<String, Integer> ordinalByFactionId,
                                    Progress progress) throws SQLException {
        InviteTable table = InviteTable.empty();
        long inviteSeq = 1L;
        String sql = "SELECT `faction_id`,`invitee_id`,`inviter_id`,`created_at` FROM `invitations`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                progress.tick();
                Integer ordinal = ordinalByFactionId.get(rs.getString("faction_id"));
                UUID invitee = parseUuid(rs.getString("invitee_id"));
                if (ordinal == null || invitee == null) {
                    continue;
                }
                table = table.add(new InviteTable.Invite(inviteSeq++, ordinal,
                        parseUuid(rs.getString("inviter_id")), invitee, rs.getLong("created_at")));
            }
        }
        return table;
    }

    private MergeTable readMerges(Connection conn, Map<String, Integer> ordinalByFactionId,
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
                        parseUuid(rs.getString("actor_id")), rs.getLong("created_at")));
            }
        }
        return table;
    }

    // ── shared helpers ────────────────────────────────────────────────────────────────────

    private static long count(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM `" + table + "`");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    private static final class FactionRow {
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

    private record BuiltFactions(FactionArena arena, NameIndex names) {
    }

    private record PlayerAggregate(PlayerLedger ledger, MemberDirectory members, int memberCount) {
    }

    /** Emits a {@code [baseline] N%} line each time cumulative rows cross a 10% boundary. */
    private static final class Progress {
        private final long total;
        private final Logger log;
        private long done;
        private int lastDecile = -1;

        Progress(long total, Logger log) {
            this.total = total;
            this.log = log;
        }

        void tick() {
            done++;
            if (total <= 0) {
                return;
            }
            int decile = (int) (done * 10 / total);
            if (decile > lastDecile && decile <= 10) {
                lastDecile = decile;
                log.info("[baseline] " + (decile * 10) + "% (" + done + "/" + total + " rows)");
            }
        }
    }
}
