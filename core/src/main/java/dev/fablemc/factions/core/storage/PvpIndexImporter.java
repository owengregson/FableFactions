package dev.fablemc.factions.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.kernel.state.RelationKind;
import dev.fablemc.factions.kernel.state.WarpTable;

/**
 * One-shot migration importer from the reference PvPIndex Factions schema (pvp-data §3) into an
 * initial {@link KernelState}. Unlike {@link BaselineLoader} (which reads FableFactions' own
 * projection), this reads the <em>reference</em> layout: the {@code board} PK is the string
 * {@code "world:chunkX:chunkZ"} (pvp-data §3.3), relations/flags are JSON on the {@code factions}
 * row, prefs are TINYINT columns, and {@code merge_requests} is absent (the reference registered
 * but never created it — pvp-data §3.12).
 *
 * <p>Two sanitizations are applied per ARCHITECTURE / the critiques:
 * <ul>
 *   <li><b>Asymmetric ALLY/TRUCE wishes stay wishes.</b> A faction's declared relation is kept as
 *       a wish ({@code relOut}); it becomes <em>effective</em> ({@code relEff}) only when mutual —
 *       folded by {@link LegacyImportSupport#effectiveKind}. (ENEMY is symmetric-max.)</li>
 *   <li><b>Fold-case name collisions get a numeric suffix + a loud log.</b> The reference allowed
 *       {@code "Wolves"} and {@code "wolves"} to coexist; the kernel's {@link NameIndex} is
 *       case-folded and unique, so a colliding faction is renamed {@code name2}, {@code name3}, …
 *       and the rename is logged at WARNING.</li>
 * </ul>
 *
 * <p>The LEDGER-tier history tables ({@code power_history}, {@code bank_transactions}) are
 * loss-tolerant and carry no authoritative kernel state; the importer counts and reports them
 * (Wave-4 re-projection copies the rows) rather than folding them into the snapshot.
 *
 * <p><b>Owning thread(s):</b> the boot/migration thread only. <b>Mutability:</b> confined; the
 * produced {@link KernelState} is immutable.
 */
public final class PvpIndexImporter {

    private final DataSource source;
    private final ToIntFunction<String> worldIndex;
    private final Logger log;

    public PvpIndexImporter(DataSource source, ToIntFunction<String> worldIndex, Logger log) {
        this.source = Objects.requireNonNull(source, "source");
        this.worldIndex = Objects.requireNonNull(worldIndex, "worldIndex");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** The imported state, the handle→id seed map, and per-category counts. */
    public record Result(KernelState state, Map<Integer, UUID> factionHandleToId,
                         int factionCount, int memberCount, int claimCount, int renamedCollisions,
                         long historyRows) {
    }

    /** Reads the reference schema into a fresh {@link KernelState} carrying {@code config}. */
    public Result importAll(ConfigImage config) throws SQLException {
        Objects.requireNonNull(config, "config");
        try (Connection conn = source.getConnection()) {
            Map<String, Integer> ordinalByFactionId = new HashMap<>();
            Map<Integer, UUID> handleToId = new HashMap<>();
            List<FactionRow> rows = readFactions(conn, ordinalByFactionId, handleToId);
            Map<Integer, List<Rank>> ranksByOrdinal = readRanks(conn, ordinalByFactionId);
            ClaimAtlas.Builder atlas = new ClaimAtlas.Builder();
            Map<Integer, FactionClaimList> claimsByOrdinal = new HashMap<>();
            int claimCount = readBoard(conn, ordinalByFactionId, atlas, claimsByOrdinal);

            Built built = buildFactions(rows, ordinalByFactionId, ranksByOrdinal, claimsByOrdinal);
            PlayerAggregate players = readPlayers(conn, ordinalByFactionId, built.arena);
            WarpTable warps = readWarps(conn, ordinalByFactionId);
            ChestTable chests = readChests(conn, ordinalByFactionId);
            InviteTable invites = readInvites(conn, ordinalByFactionId);
            long historyRows = countHistory(conn);

            KernelState state = KernelState.empty(config)
                    .withFactions(built.arena)
                    .withFactionNames(built.names)
                    .withLedger(players.ledger)
                    .withMembers(players.members)
                    .withClaims(atlas.build())
                    .withWarps(warps)
                    .withChests(chests)
                    .withInvites(invites);

            log.info("[pvp-import] imported " + rows.size() + " faction(s), " + players.memberCount
                    + " member(s), " + claimCount + " claim(s); " + built.renamed
                    + " fold-case collision(s) renamed; " + historyRows
                    + " LEDGER-tier history row(s) noted (re-projected by Wave 4).");
            return new Result(state, handleToId, rows.size(), players.memberCount, claimCount,
                    built.renamed, historyRows);
        }
    }

    // ── factions (+ fold-case collision sanitization) ─────────────────────────────────────

    private List<FactionRow> readFactions(Connection conn, Map<String, Integer> ordinalByFactionId,
                                          Map<Integer, UUID> handleToId) throws SQLException {
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
            }
        }
        return rows;
    }

    private Built buildFactions(List<FactionRow> rows, Map<String, Integer> ordinalByFactionId,
                                Map<Integer, List<Rank>> ranksByOrdinal,
                                Map<Integer, FactionClaimList> claimsByOrdinal) {
        // Declared wishes per faction (other-ordinal → kind).
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

        FactionArena arena = FactionArena.empty();
        NameIndex names = NameIndex.empty();
        Set<String> usedFolded = new HashSet<>();
        int renamed = 0;
        for (FactionRow row : rows) {
            String name = sanitizeName(row.name, row.id, usedFolded);
            if (name != null && !name.equals(row.name)) {
                renamed++;
            }
            String folded = NameIndex.fold(name);

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
                    ? defaultRanks() : rankList.toArray(new Rank[0]);
            FactionClaimList claims = claimsByOrdinal.getOrDefault(row.ordinal,
                    FactionClaimList.empty());
            long flagBits = LegacyImportSupport.parseFlags(row.flagsJson, 0L);

            Faction faction = new Faction(
                    row.ordinal, row.id, name, folded, row.ownerId, row.description, row.motd,
                    row.createdAt, row.powerBoost, row.money, flagBits, relOut, relOutKind, relEff,
                    relEffKind, home, row.shieldStartHour, row.shieldDurationHours, row.raidable,
                    ranks, claims.count(), 0.0, 0L, claims, null, null);
            arena = arena.withFaction(row.ordinal, faction);
            names = names.with(folded, row.ordinal);
        }
        return new Built(arena, names, renamed);
    }

    /**
     * Sanitization rule 2: if {@code name}'s fold-case is already used, append the smallest numeric
     * suffix that makes it unique and log the rename loudly. Returns the (possibly suffixed) name.
     */
    private String sanitizeName(String name, UUID id, Set<String> usedFolded) {
        String base = name == null ? ("faction-" + id) : name;
        String folded = NameIndex.fold(base);
        if (usedFolded.add(folded)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String candidate = base + n;
            String candidateFolded = NameIndex.fold(candidate);
            if (usedFolded.add(candidateFolded)) {
                log.warning("[pvp-import] fold-case name collision: faction " + id + " '" + base
                        + "' renamed to '" + candidate + "' (case-insensitive uniqueness).");
                return candidate;
            }
        }
    }

    // ── ranks ─────────────────────────────────────────────────────────────────────────────

    private Map<Integer, List<Rank>> readRanks(Connection conn, Map<String, Integer> ordinalByFactionId)
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

    private static Rank[] defaultRanks() {
        return new Rank[] {
                new Rank(UUID.randomUUID().toString(), Rank.NAME_OWNER, null, Rank.PRIORITY_OWNER),
                new Rank(UUID.randomUUID().toString(), Rank.NAME_OFFICER, null, Rank.PRIORITY_OFFICER),
                new Rank(UUID.randomUUID().toString(), Rank.NAME_MEMBER, null, Rank.PRIORITY_MEMBER),
        };
    }

    // ── board (reference string PK "world:cx:cz") ─────────────────────────────────────────

    private int readBoard(Connection conn, Map<String, Integer> ordinalByFactionId,
                          ClaimAtlas.Builder atlas, Map<Integer, FactionClaimList> claimsByOrdinal)
            throws SQLException {
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

    // ── players (TINYINT prefs) ───────────────────────────────────────────────────────────

    private PlayerAggregate readPlayers(Connection conn, Map<String, Integer> ordinalByFactionId,
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
                ledger = ledger.withPrefsBits(ord,
                        PlayerLedger.withAutoMode(0, rs.getInt("auto_territory_mode")));
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

    // ── warps / chests / invitations ──────────────────────────────────────────────────────

    private WarpTable readWarps(Connection conn, Map<String, Integer> ordinalByFactionId)
            throws SQLException {
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

    private ChestTable readChests(Connection conn, Map<String, Integer> ordinalByFactionId)
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

    private InviteTable readInvites(Connection conn, Map<String, Integer> ordinalByFactionId)
            throws SQLException {
        InviteTable table = InviteTable.empty();
        long inviteSeq = 1L;
        String sql = "SELECT `faction_id`,`invitee_id`,`inviter_id`,`created_at` FROM `invitations`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
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

    // ── LEDGER-tier history (counted, not folded into state) ──────────────────────────────

    private long countHistory(Connection conn) {
        return safeCount(conn, "power_history") + safeCount(conn, "bank_transactions");
    }

    private long safeCount(Connection conn, String table) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM `" + table + "`");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException absent) {
            return 0L;   // table not present in this legacy DB — nothing to carry forward
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

    private record Built(FactionArena arena, NameIndex names, int renamed) {
    }

    private record PlayerAggregate(PlayerLedger ledger, MemberDirectory members, int memberCount) {
    }
}
