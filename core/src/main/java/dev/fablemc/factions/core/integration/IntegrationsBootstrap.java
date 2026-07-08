package dev.fablemc.factions.core.integration;

import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;

import dev.fablemc.factions.core.integration.discordsrv.DiscordNotifier;
import dev.fablemc.factions.core.integration.dynmap.DynmapLayer;
import dev.fablemc.factions.core.integration.essentials.EssentialsInterop;
import dev.fablemc.factions.core.integration.essentials.EssentialsInteropFactory;
import dev.fablemc.factions.core.integration.ezcountdown.EzCountdownNotifier;
import dev.fablemc.factions.core.integration.ezcountdown.EzCountdownSender;
import dev.fablemc.factions.core.integration.lwc.LwcInterop;
import dev.fablemc.factions.core.integration.lwc.LwcInteropFactory;
import dev.fablemc.factions.core.integration.placeholderapi.PlaceholderHook;
import dev.fablemc.factions.core.integration.teamsapi.TeamsApiProvider;
import dev.fablemc.factions.core.integration.vault.VaultEconomy;
import dev.fablemc.factions.core.integration.vault.VaultEconomyFactory;
import dev.fablemc.factions.core.integration.worldguard.TerritoryGuard;
import dev.fablemc.factions.core.integration.worldguard.TerritoryGuardFactory;
import dev.fablemc.factions.core.integration.worldguard.WgRegionMirror;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.platform.resolve.Worlds;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * Wires every third-party integration behind its presence-AND-config gate and exposes the resulting
 * façades plus the single effect sink that drives the OUT-adapters (ref-integrations §0.4, work order
 * W3f). Provider-typed classes are never instantiated (every integration is reflection-only this
 * wave), so this bootstrap loads unconditionally even when all soft-deps are absent.
 *
 * <p><b>Owning thread(s):</b> {@link #start}/{@link #stop} run on the plugin lifecycle (main) thread;
 * the built façades are read from their consumers' threads and the {@linkplain #effectSink() sink}
 * runs on the writer fan-out. <b>Mutability:</b> the façade handles are set once during {@link #start}
 * and read thereafter; the dynmap flush task handle is the only mutable field.
 *
 * <p>Wave-4 wiring: subscribe {@code effectSink()} to the effect fan-out
 * ({@code fanout.subscribe(sink::accept)}) and hand the façade getters to the command/travel/economy
 * layers that consult them.
 */
public final class IntegrationsBootstrap {

    /** The dynmap coalescing flush period — a 2-second debounce (proposal-C §10.3). */
    private static final long DYNMAP_FLUSH_TICKS = 40L;

    private final Plugin plugin;
    private final Logger logger;
    private final Scheduling scheduling;
    private final SnapshotHub snapshots;
    private final Worlds worlds;
    private final Messages messages;
    private final IntegrationSettings settings;
    private final AnnouncementText announcementText;

    private VaultEconomy vaultEconomy;
    private EssentialsInterop essentialsInterop;
    private TerritoryGuard territoryGuard;
    private LwcInterop lwcInterop;
    private PlaceholderHook placeholderHook;
    private TeamsApiProvider teamsApi;
    private IntegrationEffectSink effectSink;

    private DynmapLayer dynmap;
    private TaskHandle dynmapFlush;

    /** Constructor injection of the plugin handle, seams, config toggles, and announcement renderer. */
    public IntegrationsBootstrap(Plugin plugin, Scheduling scheduling, SnapshotHub snapshots,
                                 Worlds worlds, Messages messages, IntegrationSettings settings,
                                 AnnouncementText announcementText) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.scheduling = scheduling;
        this.snapshots = snapshots;
        this.worlds = worlds;
        this.messages = messages;
        this.settings = settings;
        this.announcementText = announcementText;
    }

    /** Builds every integration behind its gate and starts the dynmap flush ticker. Never throws. */
    public void start() {
        vaultEconomy = VaultEconomyFactory.create(logger);
        essentialsInterop = EssentialsInteropFactory.create(settings.essentialsX(), logger);
        territoryGuard = TerritoryGuardFactory.create(
                settings.worldGuard(), settings.worldGuardSyncRegions(), logger);
        lwcInterop = LwcInteropFactory.create(settings, snapshots, worlds, messages, logger);
        lwcInterop.register(plugin);
        placeholderHook = PlaceholderHook.create(logger, snapshots, plugin.getDescription().getVersion());
        teamsApi = TeamsApiProvider.create(logger);

        dynmap = startDynmap();
        DiscordNotifier discord = startDiscord();
        EzCountdownSender ezCountdown = startEzCountdown();
        WgRegionMirror wgMirror = startWgMirror();

        effectSink = new IntegrationEffectSink(snapshots, scheduling, worlds, messages, settings, logger,
                dynmap, discord, ezCountdown, wgMirror, lwcInterop, announcementText);
    }

    /** Tears down live integrations (dynmap flush + markers, LWC listeners, PAPI). Idempotent. */
    public void stop() {
        if (dynmapFlush != null) {
            dynmapFlush.cancel();
            dynmapFlush = null;
        }
        if (dynmap != null) {
            scheduling.runGlobal(dynmap::stop);
        }
        if (lwcInterop != null) {
            lwcInterop.unregister();
        }
        if (placeholderHook != null) {
            placeholderHook.unregister();
        }
    }

    // ── enabled-adapter builders ───────────────────────────────────────────────────────────

    private DynmapLayer startDynmap() {
        if (!settings.dynmap() || !Reflect.pluginPresent("dynmap")) {
            return null;
        }
        DynmapLayer layer = new DynmapLayer(snapshots, worlds, logger);
        if (!layer.start()) {
            return null;
        }
        logger.info("dynmap hooked - faction territory layer enabled.");
        dynmapFlush = scheduling.repeatGlobal(DYNMAP_FLUSH_TICKS, DYNMAP_FLUSH_TICKS, layer::flush);
        return layer;
    }

    private DiscordNotifier startDiscord() {
        if (!settings.discord().enabled()) {
            logger.info("DiscordSRV integration disabled in config.");
            return null;
        }
        DiscordNotifier notifier = new DiscordNotifier(logger, settings.discord().channelId());
        if (!notifier.setup()) {
            logger.info("DiscordSRV not found — faction event broadcasts disabled.");
            return null;
        }
        logger.info("DiscordSRV hooked — faction events will be posted to Discord.");
        return notifier;
    }

    private EzCountdownSender startEzCountdown() {
        boolean configEnabled = snapshots.current().config().notifications().ezCountdownEnabled();
        EzCountdownNotifier notifier = new EzCountdownNotifier(logger);
        if (!configEnabled || !notifier.setup()) {
            logger.info("EzCountdown not found or disabled — faction announcements will use chat only.");
            return null;
        }
        logger.info("EzCountdown hooked — faction relation announcements enabled.");
        return notifier;
    }

    private WgRegionMirror startWgMirror() {
        if (!settings.worldGuardSyncRegions() || !Reflect.pluginPresent("WorldGuard")) {
            return null;
        }
        logger.info("WorldGuard region sync enabled — faction claims mirror as WG regions.");
        return new WgRegionMirror(scheduling, logger);
    }

    // ── façade + sink accessors (Wave-4 wiring) ────────────────────────────────────────────

    /** The integration effect sink; subscribe it to the effect fan-out. */
    public IntegrationEffectSink effectSink() {
        return effectSink;
    }

    /** The economy façade (Noop when Vault absent). */
    public VaultEconomy vaultEconomy() {
        return vaultEconomy;
    }

    /** The EssentialsX teleport façade (Noop when Essentials absent). */
    public EssentialsInterop essentialsInterop() {
        return essentialsInterop;
    }

    /** The WorldGuard territory façade (Noop when WG absent). */
    public TerritoryGuard territoryGuard() {
        return territoryGuard;
    }

    /** The LWC chest-protection façade (Noop when LWC absent). */
    public LwcInterop lwcInterop() {
        return lwcInterop;
    }

    /** The PlaceholderAPI presence hook ({@code %fable_*%} data provider). */
    public PlaceholderHook placeholderHook() {
        return placeholderHook;
    }

    /** The TeamsAPI provider anchor. */
    public TeamsApiProvider teamsApi() {
        return teamsApi;
    }
}
