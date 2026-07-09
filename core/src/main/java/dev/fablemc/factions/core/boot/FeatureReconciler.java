package dev.fablemc.factions.core.boot;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.core.chest.ChestCloseListener;
import dev.fablemc.factions.core.chest.ChestSessions;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.FCommandExecutor;
import dev.fablemc.factions.core.command.FaCommandExecutor;
import dev.fablemc.factions.core.command.VaultBridge;
import dev.fablemc.factions.core.command.admin.AdminQueries;
import dev.fablemc.factions.core.economy.TaxScheduler;
import dev.fablemc.factions.core.economy.VaultAdapter;
import dev.fablemc.factions.core.gui.GuiListener;
import dev.fablemc.factions.core.gui.MenuManager;
import dev.fablemc.factions.core.integration.AnnouncementText;
import dev.fablemc.factions.core.integration.IntegrationSettings;
import dev.fablemc.factions.core.integration.IntegrationsBootstrap;
import dev.fablemc.factions.core.listen.ListenerContext;
import dev.fablemc.factions.core.listen.ListenerLoadout;
import dev.fablemc.factions.core.listen.TerritorySync;
import dev.fablemc.factions.core.power.PowerTicker;
import dev.fablemc.factions.core.session.CombatTags;
import dev.fablemc.factions.core.session.SessionRegistry;
import dev.fablemc.factions.core.session.TeleportSaga;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.core.update.UpdateChecker;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.intent.SessionIntent;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.platform.gui.MenuModel;
import dev.fablemc.factions.platform.probe.Capabilities;
import dev.fablemc.factions.platform.resolve.ItemCodec;
import dev.fablemc.factions.platform.resolve.Players;
import dev.fablemc.factions.platform.resolve.Worlds;
import dev.fablemc.factions.platform.life.Scope;

/**
 * Wires the Bukkit-facing feature surface on top of the {@link BootAssembly} write plane
 * (proposal-C §6.3 step 6, §8; AM-13 ListenerGate; mental-seam.md §8). Everything a subsystem
 * acquires — protection/event listeners, the {@code /f} + {@code /fa} command trees, the GUI
 * listener, the power-tick / tax / lock-heartbeat timers, the integration adapters and their effect
 * sink — is registered into ONE feature {@link Scope}, so a {@code /fa reload} (a {@code ConfigSwapped}
 * effect) closes the scope whole and reconverges from the new image, which kills the reload-leak bug
 * class (BUG-3): no handler is ever registered twice over a stale one.
 *
 * <p>Bukkit-bound and therefore not part of the headless boot-order test; the pipeline it sits on top
 * of is. A few seams have no landed production implementation yet — those are marked {@code // W5:}
 * and given a safe degraded stand-in rather than being silently skipped (B10).
 *
 * <p><b>Owning thread(s):</b> {@link #converge}/{@link #close} run on the plugin main/boot thread
 * (registration is a main-thread operation); the {@code ConfigSwapped} detector marshals the
 * reconverge back onto the global thread. <b>Mutability:</b> confined to the main thread; the scope
 * is swapped atomically per converge.
 */
public final class FeatureReconciler {

    private static final MessageKey UPDATE_NOTICE = MessageKey.of("update.available");

    private final JavaPlugin plugin;
    private final BootAssembly assembly;
    private final Capabilities caps;
    private final Worlds worlds;
    private final IntSupplier tick;
    private final UpdateChecker updateChecker;
    private final Logger logger;

    private final SessionRegistry sessions = new SessionRegistry();   // one per plugin lifetime

    private @Nullable Scope scope;
    private @Nullable ChestSessions chests;   // the live set (disable force-commits it)

    public FeatureReconciler(JavaPlugin plugin, BootAssembly assembly, Capabilities caps, Worlds worlds,
                             IntSupplier tick, UpdateChecker updateChecker) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.caps = Objects.requireNonNull(caps, "caps");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.tick = Objects.requireNonNull(tick, "tick");
        this.updateChecker = Objects.requireNonNull(updateChecker, "updateChecker");
        this.logger = plugin.getLogger();
    }

    /** Registers every feature into a fresh scope and replays connect intents for online players. */
    public void converge() {
        Scope s = new Scope(plugin);
        BootReport report = assembly.report();
        ConfigImage config = assembly.config();
        Messages messages = assembly.messages();
        VaultAdapter vault = assembly.vault();
        var snapshots = assembly.snapshots();
        var bus = assembly.bus();

        // ── integrations (behind their own gates) + their effect sink into the fan-out ───────────
        // W5: per-config integration toggles use IntegrationSettings.defaults() until the raw
        //     config.yml section is threaded through the assembly (only the parsed image is kept).
        AnnouncementText announce = (key, args) -> key.key();   // W5: plain (non-Adventure) fallback
        IntegrationsBootstrap integrations = new IntegrationsBootstrap(plugin, assembly.scheduling(),
                snapshots, worlds, messages, IntegrationSettings.defaults(), announce);
        integrations.start();
        s.closeable(assembly.fanout().subscribe(integrations.effectSink()::accept));
        s.closeable(integrations::stop);
        report.line("integrations", "started (defaults) + effect sink subscribed");

        // ── sessions / teleport / chests / GUI ──────────────────────────────────────────────────
        CombatTags combat = CombatTags.defaults();
        TeleportSaga teleport = new TeleportSaga(assembly.scheduling(), sessions, messages, worlds,
                vault, combat, snapshots, caps, integrations.essentialsInterop());

        ItemCodec itemCodec = ItemCodec.create(caps.serializeAsBytes());
        ChestSessions.BlobReader blobReader = (ref, onLoaded) ->
                assembly.scheduling().runAsync(() -> onLoaded.accept(assembly.storage().loadBlob(ref)));
        ChestSessions.BlobWriter blobWriter = (ref, bytes, createdAt) ->
                assembly.scheduling().runAsync(() -> assembly.storage().storeBlob(ref, bytes, createdAt));
        ChestSessions liveChests = new ChestSessions(assembly.scheduling(), bus, snapshots, messages,
                itemCodec, caps, blobReader, blobWriter);
        liveChests.start();
        this.chests = liveChests;
        s.closeable(liveChests::stop);

        // W5: gui.yml → MenuModel parsing is not landed; an empty catalog means /f gui falls back to
        //     help (MenuManager.openDefault returns false) rather than failing.
        MenuManager menus = new MenuManager(snapshots, sessions, messages, bus, assembly.catalog(),
                Map.<String, MenuModel>of());
        s.listen(new GuiListener(menus));
        s.listen(new ChestCloseListener(liveChests));

        // ── protection / event listeners (baseline direct, probe-gated via ListenerGate, AM-13) ──
        ListenerContext ctx = new ListenerContext(snapshots, bus, messages, worlds, assembly.scheduling(),
                sessions);
        Consumer<Player> updateNotice = player -> {
            if (updateChecker.updateAvailable() && config.updates().notifyOpsOnJoin() && player.isOp()) {
                messages.to(player, UPDATE_NOTICE);
            }
        };
        new ListenerLoadout(ctx, TerritorySync.NONE, updateNotice).install(plugin, s, caps);
        report.line("listeners", "protection/event loadout installed (baseline + probe-gated)");

        // ── command trees ({@code /f}, {@code /fa}) — plugin.yml owns the roots ──────────────────
        VaultBridge vaultBridge = new VaultBridge() {
            @Override
            public boolean present() {
                return vault.present();
            }

            @Override
            public boolean withdraw(Player player, double amount) {
                return vault.withdraw(player, amount);
            }

            @Override
            public void deposit(Player player, double amount) {
                vault.deposit(player, amount);
            }
        };
        CommandContext.Services services = new CommandContext.Services(plugin, bus, messages,
                assembly.scheduling(), teleport, liveChests, menus, assembly.reloads());
        FCommandExecutor fExec = new FCommandExecutor(snapshots, services, worlds, vaultBridge, List.of());
        bindCommand("f", fExec, fExec);
        AdminQueries queries = new StorageAdminQueries(assembly.storage(), assembly.scheduling());
        FaCommandExecutor faExec = new FaCommandExecutor(services, snapshots, worlds, queries);
        bindCommand("fa", faExec, faExec);
        report.line("commands", "/f and /fa executors bound");

        // ── periodic timers (AM-12: enqueue a system intent, never touch Bukkit) ─────────────────
        PowerTicker powerTicker = new PowerTicker(assembly.scheduling(), bus, snapshots, tick);
        powerTicker.start();
        s.closeable(powerTicker::stop);
        TaxScheduler taxScheduler = new TaxScheduler(assembly.scheduling(), bus, snapshots, tick);
        taxScheduler.start();
        s.closeable(taxScheduler::stop);
        // AM-4: low-frequency aggregate reconciliation (every 30 min). The reducer recomputes a
        // rotating subset of factions from ground truth, self-heals any landCount drift, and emits a
        // loud AggregateDriftDetected effect — drift is a bug signal, never silent.
        s.task(assembly.scheduling().repeatAsync(Duration.ofMinutes(30), Duration.ofMinutes(30),
                () -> bus.submitSystem(new SystemIntent.ReconcileSweep(0))));
        // AM-11: refresh the H2 advisory-lock fence on the storage cadence.
        s.task(assembly.scheduling().repeatAsync(Duration.ofSeconds(15), Duration.ofSeconds(15),
                assembly.storage()::heartbeat));
        report.line("timers", "power-tick + tax-sweep + advisory-lock heartbeat scheduled");

        // ── ConfigSwapped → reconverge (subscribe): close the scope diff, reopen per new image ───
        s.closeable(assembly.fanout().subscribe((batch, seq) -> {
            if (containsConfigSwap(batch)) {
                assembly.scheduling().runGlobal(this::reconverge);
            }
        }));

        this.scope = s;

        // ── PlayerConnected replay for already-online players (reload / hot-enable) ───────────────
        replayOnlinePlayers();
    }

    /**
     * Closes the current feature scope and rebuilds it from the (now-swapped) config image. Open chest
     * sessions are committed + closed FIRST so no chest edit made under the old config is lost across
     * the reload (cross-agent chest hook, part a). This runs on the {@code ConfigSwapped} path; a
     * strictly pre-swap commit (before the {@code SwapConfig} intent) would require a pre-swap hook in
     * {@code ReloadsImpl}, which is outside this slice's edit scope.
     */
    public void reconverge() {
        forceCommitChests();   // ChestSessions.commitAndCloseAll() when the chest agent lands it
        close();
        converge();
        logger.info("[reload] features reconverged from the swapped config image.");
    }

    /** Closes the feature scope (idempotent), tearing down listeners, timers and integrations. */
    public void close() {
        if (scope != null) {
            scope.close();
            scope = null;
        }
    }

    /**
     * Force-commits every open chest session onto the system lane (ordered disable §6.4 part b, and
     * the reload path part a). This is the call site the chest agent's {@code commitAndCloseAll()}
     * should replace {@code forceCommitAll()} at once landed — both the {@link BootAssembly#shutdown}
     * hook and {@link #reconverge()} route through here.
     */
    public void forceCommitChests() {
        if (chests != null) {
            chests.forceCommitAll();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private void bindCommand(String name, org.bukkit.command.CommandExecutor exec,
                             org.bukkit.command.TabCompleter completer) {
        PluginCommand command = plugin.getCommand(name);
        if (command == null) {
            logger.severe("plugin.yml declares no '" + name + "' command — /" + name + " is unavailable");
            return;
        }
        command.setExecutor(exec);
        command.setTabCompleter(completer);
    }

    private void replayOnlinePlayers() {
        for (Player player : Players.online()) {
            sessions.open(player);
            assembly.bus().submitSystem(new SessionIntent.PlayerConnected(
                    player.getUniqueId(), player.getName(), null));
        }
    }

    private static boolean containsConfigSwap(List<Effect> batch) {
        for (int i = 0; i < batch.size(); i++) {
            if (batch.get(i) instanceof SystemEffect.ConfigSwapped) {
                return true;
            }
        }
        return false;
    }
}
