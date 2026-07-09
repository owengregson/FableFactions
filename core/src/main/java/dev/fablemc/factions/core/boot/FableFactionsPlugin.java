package dev.fablemc.factions.core.boot;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import java.util.logging.Level;

import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import dev.fablemc.factions.core.metrics.MetricsInit;
import dev.fablemc.factions.core.pipeline.FailureHandler;
import dev.fablemc.factions.core.update.UpdateChecker;
import dev.fablemc.factions.platform.probe.Capabilities;
import dev.fablemc.factions.platform.probe.PlatformProfile;
import dev.fablemc.factions.platform.resolve.Worlds;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.sched.SchedulingFactory;

/**
 * The Bukkit entry point (plugin.yml {@code main}) — a thin {@link JavaPlugin} adapter over the
 * Bukkit-free {@link BootAssembly} write plane and the {@link FeatureReconciler} feature surface
 * (proposal-C §6.3 load path, §6.4 shutdown). {@code onEnable} resolves the platform seam
 * (capabilities → profile → scheduling → world registry), assembles the pipeline (config → storage
 * + advisory lock → baseline → journal replay → snapshot v0 → writer), and converges the Bukkit
 * features into a feature scope; {@code onDisable} runs the ordered drain/flush and closes the scope.
 *
 * <p>This class is also the build's {@code verifyDowngrade} SENTINEL
 * ({@code dev/fablemc/factions/core/boot/FableFactionsPlugin}): the gate asserts it is class-file
 * major 52 in the mega jar's base tree AND major 61 under {@code META-INF/versions/17}. To guarantee
 * the class forks into the modern overlay, {@link #onEnable()} touches {@link java.util.List#of} — a
 * Java-9+ API that JVMDowngrader shims in the base tree while preserving the original call under
 * {@code versions/17}, so the class legitimately exists in both tiers.
 *
 * <p><b>Owning thread(s):</b> the Bukkit main / plugin-lifecycle thread only. <b>Mutability:</b>
 * holds the assembled {@link BootAssembly} + {@link FeatureReconciler}; both are set once in
 * {@code onEnable} and torn down in {@code onDisable}.
 */
public final class FableFactionsPlugin extends JavaPlugin {

    private BootAssembly assembly;
    private FeatureReconciler reconciler;

    @Override
    public void onEnable() {
        // List.of is post-Java-8: jvmdg rewrites this call to a shim in the v52 base tree and
        // preserves the original under versions/17, forking the sentinel (verifyDowngrade).
        List<String> banner = List.of("FableFactions", getDescription().getVersion(), "enabling");
        getLogger().info(String.join(" ", banner));
        try {
            enable();
        } catch (Throwable fatal) {
            getLogger().log(Level.SEVERE, "FableFactions failed to enable — disabling the plugin", fatal);
            safeDisableAfterFailure();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        // §6.4 ordered disable first (reject → drain → fsync → flush → lock release → pool close),
        // then close the feature scope (listeners/timers/integrations). Each isolated + logged.
        try {
            if (assembly != null) {
                assembly.shutdown(reconciler != null ? reconciler::forceCommitChests : () -> { });
            }
        } catch (Throwable failure) {
            getLogger().log(Level.SEVERE, "ordered shutdown failed", failure);
        }
        try {
            if (reconciler != null) {
                reconciler.close();
            }
        } catch (Throwable failure) {
            getLogger().log(Level.SEVERE, "feature scope close failed", failure);
        }
        getLogger().info("FableFactions disabled");
    }

    // ── enable steps ─────────────────────────────────────────────────────────────────────────

    private void enable() {
        File dataFolder = getDataFolder();
        dataFolder.mkdirs();

        // step 2: platform seam — capabilities → profile → scheduling → world registry (B10 report).
        Capabilities caps = Capabilities.detect();
        getLogger().info("[boot] capabilities: " + caps.describe());
        PlatformProfile profile = PlatformProfile.resolve(caps, getLogger()::warning);
        getLogger().info("[boot] " + profile.bootReport());
        Scheduling scheduling = SchedulingFactory.create(this, caps);
        getLogger().info("[boot] scheduling: " + scheduling.describe());

        Worlds worlds = new Worlds();
        for (World world : getServer().getWorlds()) {
            worlds.index(world);
        }
        // AM-15: world name ⇄ dense index, backed by the live server + the COW registry. New worlds
        // self-register lazily on first reference (claims/moves), so no explicit load listener is needed.
        ToIntFunction<String> worldIndex = name -> {
            World world = getServer().getWorld(name);
            return world == null ? -1 : worlds.index(world);
        };
        IntFunction<String> worldResolver = idx -> {
            UUID uid = worlds.uid(idx);
            if (uid == null) {
                return null;
            }
            World world = getServer().getWorld(uid);
            return world == null ? null : world.getName();
        };

        AtomicInteger tick = new AtomicInteger();

        FailureHandler onWriterFatal = cause -> scheduling.runGlobal(() -> {
            getLogger().log(Level.SEVERE, "fable-kernel writer died — disabling the plugin", cause);
            getServer().getPluginManager().disablePlugin(this);
        });

        // Finding #7: bake the container/interact material bitsets (+ world multipliers) from the
        // RUNTIME Material registry at boot and on /fa reload. This is the ONLY Bukkit-Material seam
        // reaching the write plane; BootAssembly stays server-API-free (headless boot test).
        java.util.function.UnaryOperator<dev.fablemc.factions.kernel.config.ConfigImage> configBaker =
                MaterialBaking.finalizer(worldIndex);

        // steps 1 + 3 + 4: config → storage(+lock) → baseline → journal replay → snapshot v0 → pipeline.
        BootAssembly.Deps deps = new BootAssembly.Deps(
                getLogger(), dataFolder, this::getResource, System::currentTimeMillis, tick::get,
                scheduling, worldIndex, worldResolver, UUID.randomUUID(), onWriterFatal,
                line -> getLogger().info(line), null, null, readMysqlPassword(dataFolder), configBaker);
        this.assembly = new BootAssembly(deps);
        assembly.start();

        // boot-once integrations that must not double-register on reload: metrics + update checker.
        UpdateChecker updateChecker = new UpdateChecker(this, scheduling, assembly.config().updates());
        if (assembly.config().updates().enabled()) {
            updateChecker.start();
        }
        MetricsInit.start(this, assembly.snapshots(), assembly.config().metrics(), assembly::backendLabel);

        // step 6: converge the Bukkit feature surface into a feature scope.
        this.reconciler = new FeatureReconciler(this, assembly, caps, worlds, tick::get, updateChecker);
        reconciler.converge();

        // Advance the monotonic tick every server tick (captured into every intent envelope).
        scheduling.repeatGlobal(1L, 1L, tick::incrementAndGet);

        // W5: the :api FableFactionsApi is interface-only in the landed tree (no impl surface), so the
        //     Bukkit ServicesManager registration is deferred; ApiEventBridge is likewise not landed.

        // step 7: the final READY fact line.
        var snapshot = assembly.snapshots().current();
        assembly.report().summary("FableFactions " + getDescription().getVersion()
                + " | server=" + getServer().getName() + " " + getServer().getBukkitVersion()
                + " | scheduling=" + scheduling.describe()
                + " | storage=" + assembly.backendLabel()
                + " | factions=" + assembly.factionCount()
                + " member=" + assembly.memberCount()
                + " claims=" + assembly.claimCount()
                + " | journalSeq=" + assembly.journalSeq()
                + " | stateVersion=" + snapshot.version());
    }

    /** Best-effort shutdown of a half-built assembly when {@code onEnable} throws part-way. */
    private void safeDisableAfterFailure() {
        try {
            if (reconciler != null) {
                reconciler.close();
            }
        } catch (Throwable ignored) {
            // best effort
        }
        try {
            if (assembly != null) {
                assembly.shutdown(() -> { });
            }
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /**
     * Reads the MySQL wallet password from {@code database.yml} (kept out of the kernel config image
     * for hygiene). H2 — the default backend — ignores it. Extracts the default file if missing.
     */
    private String readMysqlPassword(File dataFolder) {
        File dbFile = new File(dataFolder, "database.yml");
        if (!dbFile.isFile()) {
            saveResource("database.yml", false);
        }
        if (!dbFile.isFile()) {
            return "";
        }
        return YamlConfiguration.loadConfiguration(dbFile).getString("mysql.password", "");
    }
}
