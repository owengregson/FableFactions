package dev.fablemc.factions.core.metrics;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.java.JavaPlugin;

import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.RelationKind;

/**
 * bStats metrics init (ref-integrations §bStats, proposal-C §10.3). bStats is a typed, shaded
 * dependency — used directly here (not reflectively). Every chart callback is fed from a wait-free
 * snapshot read; no chart ever scans the database (the counters live in kernel state).
 *
 * <p><b>Owning thread(s):</b> {@link #start} on the boot thread; each chart callback runs on
 * bStats' async submission thread, where the snapshot read is safe (wait-free, immutable).
 * <b>Mutability:</b> stateless — the {@link Metrics} instance owns its own scheduling.
 */
public final class MetricsInit {

    private MetricsInit() {
    }

    /**
     * Registers bStats with the four snapshot-fed charts (database backend, total factions, total
     * claims, relation drilldown) when {@code integrations} enables it. A no-op when disabled.
     *
     * @param backendLabel supplies {@code "H2"}/{@code "MySQL"} for the database-type pie (no DB scan)
     */
    public static void start(JavaPlugin plugin, SnapshotHub snapshots, ConfigImage.Metrics config,
                             Supplier<String> backendLabel) {
        Logger logger = plugin.getLogger();
        if (!config.bstatsEnabled()) {
            logger.info("bStats metrics disabled in config.");
            return;
        }
        Metrics metrics = new Metrics(plugin, config.bstatsPluginId());
        metrics.addCustomChart(new SimplePie("database_backend", backendLabel::get));
        metrics.addCustomChart(new SingleLineChart("total_factions",
                () -> totalFactions(snapshots.current())));
        metrics.addCustomChart(new SingleLineChart("total_claims",
                () -> totalClaims(snapshots.current())));
        metrics.addCustomChart(new AdvancedPie("faction_relations",
                () -> relationBreakdown(snapshots.current())));
        logger.info("bStats metrics enabled (plugin id " + config.bstatsPluginId() + ").");
    }

    private static int totalFactions(KernelSnapshot snapshot) {
        FactionArena arena = snapshot.state().factions();
        int hw = arena.highWater();
        int count = 0;
        for (int ord = 0; ord < hw; ord++) {
            Faction faction = arena.at(ord);
            if (faction != null && faction.isNormal()) {
                count++;
            }
        }
        return count;
    }

    private static int totalClaims(KernelSnapshot snapshot) {
        FactionArena arena = snapshot.state().factions();
        int hw = arena.highWater();
        int total = 0;
        for (int ord = 0; ord < hw; ord++) {
            Faction faction = arena.at(ord);
            if (faction != null && faction.isNormal()) {
                total += faction.landCount();
            }
        }
        return total;
    }

    /**
     * Counts effective relation edges by kind over unique normal-faction pairs (ally/truce/enemy).
     * O(n²/2) over the faction set — acceptable at bStats' ~30-minute submission cadence.
     */
    private static Map<String, Integer> relationBreakdown(KernelSnapshot snapshot) {
        FactionArena arena = snapshot.state().factions();
        int hw = arena.highWater();
        int ally = 0;
        int truce = 0;
        int enemy = 0;
        for (int a = 0; a < hw; a++) {
            Faction fa = arena.at(a);
            if (fa == null || !fa.isNormal()) {
                continue;
            }
            for (int b = a + 1; b < hw; b++) {
                Faction fb = arena.at(b);
                if (fb == null || !fb.isNormal()) {
                    continue;
                }
                switch (fa.relationEffective(fb.idx())) {
                    case RelationKind.ALLY -> ally++;
                    case RelationKind.TRUCE -> truce++;
                    case RelationKind.ENEMY -> enemy++;
                    default -> { /* MEMBER/NEUTRAL are not charted */ }
                }
            }
        }
        Map<String, Integer> breakdown = new HashMap<>();
        breakdown.put("ally", ally);
        breakdown.put("truce", truce);
        breakdown.put("enemy", enemy);
        return breakdown;
    }
}
