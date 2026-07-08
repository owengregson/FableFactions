package dev.fablemc.factions.core.storage.load;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;
import javax.sql.DataSource;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.state.ChestTable;
import dev.fablemc.factions.kernel.state.ClaimAtlas;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.MergeTable;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.WarpTable;

/**
 * Boot-time loader that streams FableFactions' own relational projection back into an initial
 * {@link KernelState} (proposal-C §6.3). Boot is <em>load DB → replay journal tail</em>; this is
 * the load half. Every table is read once, single-threaded, before the pipeline serves, through
 * the per-table readers in {@code storage.load} ({@code FactionRows}, {@code BoardRows},
 * {@code MemberRows}, {@code AncillaryRows}); this class is the orchestrator (W25-REORG §P2b).
 *
 * <p>Faction ordinals are assigned densely from
 * {@code FactionHandle.FIRST_NORMAL_ORDINAL}; a freshly built arena keeps generation 0, so a
 * faction's handle is {@code FactionHandle.handle(0, ordinal)} — the value stored in claim owner
 * slots and returned to the caller so it can seed the storage projector's handle→id map. Relation
 * wishes are folded into effective symmetric edges by
 * {@code dev.fablemc.factions.core.storage.RowJson} (asymmetric ALLY stays a wish).
 * Progress is logged every 10% of total rows.
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
            long total = LoadSupport.count(conn, "factions") + LoadSupport.count(conn, "ranks")
                    + LoadSupport.count(conn, "board") + LoadSupport.count(conn, "players")
                    + LoadSupport.count(conn, "warps") + LoadSupport.count(conn, "team_chests")
                    + LoadSupport.count(conn, "invitations") + LoadSupport.count(conn, "merge_requests");
            Progress progress = new Progress(total, log);

            Map<String, Integer> ordinalByFactionId = new HashMap<>();
            Map<Integer, UUID> handleToId = new HashMap<>();
            List<FactionRows.FactionRow> factionRows =
                    FactionRows.readFactions(conn, ordinalByFactionId, handleToId, progress);
            Map<Integer, List<Rank>> ranksByOrdinal =
                    AncillaryRows.readRanks(conn, ordinalByFactionId, progress);
            ClaimAtlas.Builder atlas = new ClaimAtlas.Builder();
            Map<Integer, FactionClaimList> claimsByOrdinal = new HashMap<>();
            int claimCount = BoardRows.readBoard(conn, ordinalByFactionId, atlas, claimsByOrdinal,
                    worldIndex, progress);

            FactionRows.BuiltFactions built = FactionRows.buildFactions(factionRows, ordinalByFactionId,
                    ranksByOrdinal, claimsByOrdinal, worldIndex);
            MemberRows.PlayerAggregate players =
                    MemberRows.readPlayers(conn, ordinalByFactionId, built.arena(), progress);
            WarpTable warps = AncillaryRows.readWarps(conn, ordinalByFactionId, worldIndex, progress);
            ChestTable chests = AncillaryRows.readChests(conn, ordinalByFactionId, progress);
            InviteTable invites = AncillaryRows.readInvites(conn, ordinalByFactionId, progress);
            MergeTable merges = AncillaryRows.readMerges(conn, ordinalByFactionId, progress);

            KernelState state = KernelState.empty(config)
                    .withFactions(built.arena())
                    .withFactionNames(built.names())
                    .withLedger(players.ledger())
                    .withMembers(players.members())
                    .withClaims(atlas.build())
                    .withWarps(warps)
                    .withChests(chests)
                    .withInvites(invites)
                    .withMergeRequests(merges);

            log.info("[baseline] loaded " + factionRows.size() + " faction(s), "
                    + players.memberCount() + " member(s), " + claimCount + " claim(s)");
            return new Result(state, handleToId, factionRows.size(), players.memberCount(), claimCount);
        }
    }
}
