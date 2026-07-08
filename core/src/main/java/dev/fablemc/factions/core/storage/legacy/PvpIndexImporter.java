package dev.fablemc.factions.core.storage.legacy;

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
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.WarpTable;

/**
 * One-shot migration importer from the reference PvPIndex Factions schema (pvp-data §3) into an
 * initial {@link KernelState}. Unlike {@code BaselineLoader} (which reads FableFactions' own
 * projection), this reads the <em>reference</em> layout: the {@code board} PK is the string
 * {@code "world:chunkX:chunkZ"} (pvp-data §3.3), relations/flags are JSON on the {@code factions}
 * row, prefs are TINYINT columns, and {@code merge_requests} is absent (the reference registered
 * but never created it — pvp-data §3.12). The per-table reads live in the {@code storage.legacy}
 * readers; this class is the orchestrator (W25-REORG §P2b).
 *
 * <p>Two sanitizations are applied per ARCHITECTURE / the critiques:
 * <ul>
 *   <li><b>Asymmetric ALLY/TRUCE wishes stay wishes.</b> A faction's declared relation is kept as
 *       a wish ({@code relOut}); it becomes <em>effective</em> ({@code relEff}) only when mutual —
 *       folded by {@code LegacyImportSupport.effectiveKind}. (ENEMY is symmetric-max.)</li>
 *   <li><b>Fold-case name collisions get a numeric suffix + a loud log.</b> Handled by
 *       {@link LegacySanitizer}.</li>
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
            List<LegacyFactionReader.FactionRow> rows =
                    LegacyFactionReader.readFactions(conn, ordinalByFactionId, handleToId);
            Map<Integer, List<Rank>> ranksByOrdinal =
                    LegacyAncillaryReader.readRanks(conn, ordinalByFactionId);
            ClaimAtlas.Builder atlas = new ClaimAtlas.Builder();
            Map<Integer, FactionClaimList> claimsByOrdinal = new HashMap<>();
            int claimCount = LegacyBoardReader.readBoard(conn, ordinalByFactionId, atlas,
                    claimsByOrdinal, worldIndex);

            LegacyFactionReader.Built built = LegacyFactionReader.buildFactions(rows, ordinalByFactionId,
                    ranksByOrdinal, claimsByOrdinal, worldIndex, log);
            LegacyPlayerReader.PlayerAggregate players =
                    LegacyPlayerReader.readPlayers(conn, ordinalByFactionId, built.arena());
            WarpTable warps = LegacyAncillaryReader.readWarps(conn, ordinalByFactionId, worldIndex);
            ChestTable chests = LegacyAncillaryReader.readChests(conn, ordinalByFactionId);
            InviteTable invites = LegacyAncillaryReader.readInvites(conn, ordinalByFactionId);
            long historyRows = LegacyAncillaryReader.countHistory(conn);

            KernelState state = KernelState.empty(config)
                    .withFactions(built.arena())
                    .withFactionNames(built.names())
                    .withLedger(players.ledger())
                    .withMembers(players.members())
                    .withClaims(atlas.build())
                    .withWarps(warps)
                    .withChests(chests)
                    .withInvites(invites);

            log.info("[pvp-import] imported " + rows.size() + " faction(s), " + players.memberCount()
                    + " member(s), " + claimCount + " claim(s); " + built.renamed()
                    + " fold-case collision(s) renamed; " + historyRows
                    + " LEDGER-tier history row(s) noted (re-projected by Wave 4).");
            return new Result(state, handleToId, rows.size(), players.memberCount(), claimCount,
                    built.renamed(), historyRows);
        }
    }
}
