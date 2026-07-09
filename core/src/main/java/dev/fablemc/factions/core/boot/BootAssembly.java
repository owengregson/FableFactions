package dev.fablemc.factions.core.boot;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.core.config.ConfigFiles;
import dev.fablemc.factions.core.config.ConfigParser;
import dev.fablemc.factions.core.config.ReloadsImpl;
import dev.fablemc.factions.core.economy.EscrowExecutor;
import dev.fablemc.factions.core.economy.VaultAdapter;
import dev.fablemc.factions.core.journal.EffectJournal;
import dev.fablemc.factions.core.journal.JournalReplay;
import dev.fablemc.factions.core.messages.CatalogLoader;
import dev.fablemc.factions.core.messages.LocaleTables;
import dev.fablemc.factions.core.pipeline.EffectFanout;
import dev.fablemc.factions.core.pipeline.FailureHandler;
import dev.fablemc.factions.core.pipeline.FeedbackRouter;
import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.core.pipeline.ReduceStep;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.core.pipeline.WriterThread;
import dev.fablemc.factions.core.storage.EscrowReconciler;
import dev.fablemc.factions.core.storage.StorageBoot;
import dev.fablemc.factions.core.storage.StorageException;
import dev.fablemc.factions.core.storage.StorageProjector;
import dev.fablemc.factions.core.storage.load.BaselineLoader;
import dev.fablemc.factions.core.text.EffectFeedback;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.config.StorageConfigView;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.kernel.state.EscrowTable;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.vocab.EscrowOutcome;
import dev.fablemc.factions.platform.sched.Scheduling;

/**
 * The Bukkit-free heart of {@code onEnable} (proposal-C §6.3 load path, §6.4 shutdown; AM-11 lock,
 * AM-17 durability). It assembles the whole write plane — config image, message catalog, storage
 * pool + advisory lock, baseline load, journal-tail replay, snapshot v0, the intent bus, effect
 * journal, storage projector, effect fan-out, feedback router and the single {@code fable-kernel}
 * writer — and owns the ordered shutdown. {@link FableFactionsPlugin} is a thin {@link org.bukkit
 * JavaPlugin} adapter over it; {@link FeatureReconciler} bolts the Bukkit-side listeners/commands/
 * timers/integrations on top through the exposed {@link #bus}/{@link #snapshots}/{@link #fanout}.
 *
 * <p>Because it depends on no {@code JavaPlugin}, the whole graph is constructible with an in-memory
 * H2 and a fake {@link Scheduling} for the headless boot-order test (work order acceptance): it
 * asserts snapshot v0 is published, that the writer takes one {@code CreateFaction} intent end to end
 * (reduced → journaled → projected row), and that the ordered shutdown flushes the projection.
 *
 * <p><b>Owning thread(s):</b> the constructor and {@link #start}/{@link #shutdown} run on the boot/
 * disable thread; after {@link #start} the {@code fable-kernel} and {@code fable-storage} daemons own
 * their state. <b>Mutability:</b> the assembled collaborators are each internally thread-safe for how
 * they are used (single-writer pipeline, COW snapshot, SPSC storage handoff).
 */
public final class BootAssembly {

    /** The H2 advisory-lock fence TTL, heartbeat-refreshed on the storage cadence (AM-11). */
    public static final long LOCK_TTL_MILLIS = 60_000L;

    private static final String JOURNAL_SUBPATH = "data/journal";

    /**
     * The injected boot dependencies — everything the Bukkit-free core needs, none of it a
     * {@code JavaPlugin}. The plugin adapter fills these from the server; the headless test fills
     * them from an in-memory H2 + a fake scheduler.
     *
     * @param log          the boot logger
     * @param dataFolder   the plugin data folder (config files, H2 file DB, journal segments)
     * @param resourceOpener opens a bundled resource by name (config/message defaults); {@code null} → absent
     * @param clock        the wall clock captured into every intent envelope + used for lock fence
     * @param tick         the monotonic server-tick supplier captured into envelopes
     * @param scheduling   the platform scheduling facade (feedback marshalling, escrow, async)
     * @param worldIndex   world name → dense worldIdx (AM-15) for the baseline loader
     * @param worldResolver worldIdx → world name (AM-15) for the storage projector
     * @param instanceId   this instance's advisory-lock owner UUID
     * @param onWriterFatal the AM-9 hook the writer calls if its thread dies (disable the plugin)
     * @param reportSink   where boot-report lines are written (B10 no silent degradation)
     * @param feedbackOverride a {@link FeedbackRouter} to use instead of the real {@link EffectFeedback}
     *                     (the headless test injects {@link FeedbackRouter#NOOP}; production passes {@code null})
     * @param storageOverride a {@link StorageConfigView} that supersedes {@code config.storage()}
     *                     (the test injects an {@code mem:} H2; production passes {@code null})
     * @param mysqlPassword the MySQL wallet password (kept out of the kernel image; empty for H2)
     * @param configBaker  the AM-14 finalizer that bakes the container/interact material bitsets +
     *                     world multipliers from the runtime {@code Material} registry (finding #7);
     *                     {@code null} → identity (the headless test, which has no protection surface)
     */
    public record Deps(
            Logger logger,
            File dataFolder,
            @Nullable Function<String, InputStream> resourceOpener,
            LongSupplier clock,
            IntSupplier tick,
            Scheduling scheduling,
            ToIntFunction<String> worldIndex,
            IntFunction<String> worldResolver,
            UUID instanceId,
            FailureHandler onWriterFatal,
            Consumer<String> reportSink,
            @Nullable FeedbackRouter feedbackOverride,
            @Nullable StorageConfigView storageOverride,
            @Nullable String mysqlPassword,
            @Nullable UnaryOperator<ConfigImage> configBaker) {
    }

    private final Logger logger;
    private final BootReport report;
    private final Scheduling scheduling;

    private final ConfigFiles configFiles;
    private final ConfigImage config;
    private final LocaleTables catalog;
    private final Messages messages;
    private final FeedbackRouter feedback;

    private final StorageBoot storage;
    private final StorageProjector projector;
    private final EffectJournal journal;
    private final Path journalDir;

    private final SnapshotHub hub;
    private final IntentBus bus;
    private final EffectFanout fanout;
    private final WriterThread writer;
    private final VaultAdapter vault;
    private final EscrowExecutor escrow;
    private final ReloadsImpl reloads;

    private final String backendLabel;
    private final int factionCount;
    private final int memberCount;
    private final int claimCount;
    private final long journalSeqAtBoot;
    private final EscrowTable recoveredEscrows;

    /**
     * Assembles the whole write plane in proposal-C §6.3 order and publishes snapshot v0. Does NOT
     * start the background daemons — call {@link #start()} for that. Throws (so the plugin disables
     * loudly) if the AM-11 advisory lock is denied or storage cannot open.
     */
    public BootAssembly(Deps deps) {
        Objects.requireNonNull(deps, "deps");
        this.logger = deps.logger();
        this.scheduling = deps.scheduling();
        this.report = new BootReport(deps.reportSink());
        Function<String, InputStream> opener = deps.resourceOpener() != null
                ? deps.resourceOpener() : name -> null;

        // ── step 1: config image + message catalog (parse issues logged, B10) ────────────────────
        List<String> issues = new ArrayList<>();
        this.configFiles = new ConfigFiles(deps.dataFolder(), opener);
        configFiles.extractDefaults(issues);
        ConfigParser.Sources sources = configFiles.loadAll(issues);
        ConfigImage parsed = ConfigParser.parse(sources, null, issues);   // world-blind at boot (AM-14)
        // Finding #7: bake the container/interact material bitsets (+ world multipliers) from the
        // RUNTIME Material registry so protection is not dead. The finalizer also re-bakes on reload.
        UnaryOperator<ConfigImage> configBaker = deps.configBaker() != null
                ? deps.configBaker() : UnaryOperator.identity();
        this.config = configBaker.apply(parsed);
        report.line("config", "parsed (" + issues.size() + " issue(s))");
        for (String issue : issues) {
            report.line("config-issue", issue);
        }
        report.line("baked", "protection materials: "
                + countBits(config.baked().containerMaterials()) + " container, "
                + countBits(config.baked().interactableMaterials())
                + " interactable (runtime Material registry, AM-14)");
        List<String> catalogIssues = new ArrayList<>();
        this.catalog = CatalogLoader.load(opener, config.language().defaultLocale(), catalogIssues);
        for (String issue : catalogIssues) {
            report.line("messages-issue", issue);
        }
        report.line("messages", catalog.localeCount() + " locale(s), default="
                + config.language().defaultLocale());

        // ── step 3: storage pool → advisory lock → migrate (acquires the leak-prone resources) ───
        StorageConfigView view = deps.storageOverride() != null ? deps.storageOverride() : config.storage();
        Runnable onFenceLost = () -> deps.onWriterFatal().onWriterFailed(new StorageException(
                "advisory lock fence lost — another instance took over the database (AM-11)"));
        this.storage = StorageBoot.open(view, deps.mysqlPassword(), deps.dataFolder(), deps.instanceId(),
                LOCK_TTL_MILLIS, deps.clock(), deps.worldIndex(), deps.worldResolver(), logger,
                committedSeq -> { /* projector-commit ack (AM-17); journal fsync ack gates CRITICAL below */ },
                onFenceLost);
        this.backendLabel = storage.backendLabel();
        report.line("storage", "backend=" + backendLabel + ", advisory lock acquired (AM-11)");

        // Finding #17: any failure AFTER the pool + lock are acquired must release BOTH — otherwise a
        // re-enable refuses ("another live instance"). Wrap the rest of boot and close on failure.
        EffectJournal journalLocal = null;
        boolean booted = false;
        try {
            this.projector = storage.projector();
            projector.setOnFatal(cause -> deps.onWriterFatal().onWriterFailed(cause));

            long checkpoint = storage.checkpoint();
            this.journalDir = new File(deps.dataFolder(), JOURNAL_SUBPATH).toPath();
            journalLocal = new EffectJournal(journalDir, EffectJournal.DEFAULT_SEGMENT_BYTES,
                    committedSeq -> { /* AM-17: fsync ack — release CRITICAL confirmations (Wave 4 consumer) */ });
            this.journal = journalLocal;

            // Finding #2: replay the journal tail, project it to the DB and FLUSH synchronously FIRST,
            // then loadBaseline — so the authoritative in-memory state0 reflects DB + recovered tail
            // (fsync-confirmed bank ops must not revert in memory). The projector daemon is not started
            // here, so the tail flush is single-threaded on the boot thread.
            List<Effect> tail = new ArrayList<>();
            JournalReplay.ReplayResult replay = JournalReplay.replayFrom(journalDir, checkpoint, tail::add);
            if (!tail.isEmpty()) {
                // Seed the projector handle→id map from the pre-tail DB so the tail's faction handles
                // resolve, project the tail, and flush it durably into the DB.
                BaselineLoader.Result preTail = storage.loadBaseline(config);
                for (Map.Entry<Integer, UUID> entry : preTail.factionHandleToId().entrySet()) {
                    projector.seedFaction(entry.getKey(), entry.getValue());
                }
                projector.accept(tail, replay.lastSeq());
                projector.flushNow();
                projector.clearFactionSeeds();   // reseed authoritatively from the post-tail baseline
                report.line("journal", "recovered " + replay.recordsReplayed() + " effect(s) from the tail "
                        + "(seq " + (checkpoint + 1) + ".." + replay.lastSeq() + "); projected + flushed to "
                        + "the DB before baseline load"
                        + (replay.truncated() ? " (torn tail trimmed at the kill -9 boundary)" : ""));
            } else {
                report.line("journal", "clean — no tail beyond checkpoint"
                        + (replay.truncated() ? " (torn tail trimmed)" : ""));
            }

            BaselineLoader.Result baseline = storage.loadBaseline(config);   // now reflects DB + tail
            for (Map.Entry<Integer, UUID> entry : baseline.factionHandleToId().entrySet()) {
                projector.seedFaction(entry.getKey(), entry.getValue());
            }
            this.factionCount = baseline.factionCount();
            this.memberCount = baseline.memberCount();
            this.claimCount = baseline.claimCount();
            report.line("baseline", factionCount + " faction(s), " + memberCount + " member(s), "
                    + claimCount + " claim(s); DB checkpoint=" + checkpoint);

            long lastCommittedSeq = Math.max(checkpoint, replay.lastSeq());
            this.journalSeqAtBoot = lastCommittedSeq;
            long startSeq = lastCommittedSeq + 1;

            KernelState state0 = baseline.state();
            this.recoveredEscrows = state0.escrows();   // finding #3: reconciled after the writer starts
            if (recoveredEscrows.size() > 0) {
                report.line("escrows", recoveredEscrows.size() + " open escrow(s) recovered from "
                        + "ff_escrows — AM-7 FAILED-settle reconciliation scheduled at start");
            }

            // ── step 4: snapshot v0 + pipeline (journal + projector + feedback + escrow) ─────────
            this.hub = new SnapshotHub(new KernelSnapshot(state0));   // publish snapshot v0
            report.line("snapshot", "v0 published (state version " + state0.version() + ")");

            // Finding #3: wire the ff_escrows durable mirror now that the hub exists, and seed the set
            // of already-persisted escrow ids so the first post-boot flush can delete settled ones.
            projector.setEscrowSource(() -> hub.current().state().escrows());
            List<Long> recoveredIds = new ArrayList<>();
            recoveredEscrows.forEach(e -> recoveredIds.add(e.id()));
            projector.seedOpenEscrows(recoveredIds);

            this.messages = new Messages(catalog, hub, scheduling);
            this.feedback = deps.feedbackOverride() != null
                    ? deps.feedbackOverride()
                    : new EffectFeedback(messages, hub, scheduling);
            this.fanout = new EffectFanout(projector, feedback);

            // The bus wakes the writer; break the construction cycle with a settable wake box.
            AtomicReference<Runnable> wake = new AtomicReference<>(() -> { });
            this.bus = new IntentBus(IntentBus.DEFAULT_CAPACITY, deps.clock(), deps.tick(),
                    () -> wake.get().run());
            this.writer = new WriterThread(bus, hub, state0, startSeq, ReduceStep.KERNEL, journal, fanout,
                    deps.onWriterFatal(), deps.clock(), logger);
            wake.set(writer::wake);

            this.vault = new VaultAdapter();
            this.escrow = new EscrowExecutor(vault, scheduling, bus);
            fanout.subscribe(escrow::accept);   // external-effect sagas (Vault payouts/refunds, AM-7)
            report.line("pipeline", "writer(fable-kernel) + journal(fsync off-writer) + "
                    + "projector(fable-storage) + fan-out wired; escrow subscribed; startSeq=" + startSeq);

            // Re-bake the material bitsets on /fa reload too (never swap in an empty image, finding #7).
            this.reloads = new ReloadsImpl(configFiles, bus, scheduling, null, configBaker);
            booted = true;
        } finally {
            if (!booted) {
                if (journalLocal != null) {
                    try {
                        journalLocal.close();
                    } catch (RuntimeException ignore) {
                        // best effort — the pool/lock release below is what matters for re-enable
                    }
                }
                try {
                    storage.close();   // release the advisory lock + close the Hikari pool (finding #17)
                } catch (RuntimeException ignore) {
                    // best effort
                }
            }
        }
    }

    /** Population count of a bitset word array (boot-report material counts). */
    private static int countBits(long[] words) {
        int count = 0;
        for (long word : words) {
            count += Long.bitCount(word);
        }
        return count;
    }

    /** Starts the {@code fable-storage} projector and {@code fable-kernel} writer daemons. */
    public void start() {
        projector.start();
        writer.start();
        report.line("threads", "fable-storage + fable-kernel started");
        // Finding #3: reconcile escrows recovered from ff_escrows now that the writer can reduce the
        // compensating SettleEscrow(FAILED) intents (AM-7 bank re-credit / wallet refund).
        if (recoveredEscrows.size() > 0) {
            int reconciled = EscrowReconciler.reconcile(recoveredEscrows,
                    id -> bus.submitSystem(new EconomyIntent.SettleEscrow(id, EscrowOutcome.FAILED)),
                    logger);
            report.line("escrows", "reconciled " + reconciled + " recovered escrow(s) via "
                    + "SettleEscrow(FAILED) (AM-7)");
        }
    }

    /**
     * The ordered disable (proposal-C §6.4): reject new player intents → stop the writer (interrupt +
     * bounded re-join if wedged) → force-commit chest sessions → drain those final intents ONLY if the
     * writer is confirmed dead (never a second writer over live state, finding #6) → fsync the journal
     * → flush + checkpoint the projector → close the journal → release the advisory lock + close the
     * pool. Every step is isolated in its own try/catch and logged; a timeout or failure at any step
     * never loses committed data — the journal replays on the next boot.
     *
     * <p>The {@code chestForceCommit} hook is the cross-agent seam for
     * {@code ChestSessions.commitAndCloseAll()} (currently {@code forceCommitAll()} via
     * {@link FeatureReconciler#forceCommitChests()}): it runs AFTER the writer stops and BEFORE storage
     * stops, so the final drain picks up its {@code CommitChestContents} intents.
     *
     * @param chestForceCommit force-commits open chest sessions onto the system lane (a no-op if the
     *                         feature is not wired); run after the writer stops so the final drain sees it
     */
    public void shutdown(Runnable chestForceCommit) {
        step("reject-new-intents", bus::beginShutdown);
        step("writer-drain-and-stop", writer::shutdown);
        step("chest-force-commit", chestForceCommit);
        step("final-intent-drain", writer::drainRemaining);   // self-guards on writer.isStopped() (finding #6)
        step("journal-final-fsync", journal::fsyncBarrier);
        step("projector-flush-and-checkpoint", projector::shutdown);
        step("journal-close", journal::close);
        step("storage-close", storage::close);
        report.line("shutdown", "ordered disable complete");
    }

    private void step(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            logger.log(Level.SEVERE, "[shutdown] step '" + name + "' failed (continuing)", failure);
        }
    }

    // ── accessors for the Bukkit-side reconciler / plugin adapter ────────────────────────────────

    public BootReport report() {
        return report;
    }

    public ConfigImage config() {
        return config;
    }

    public ConfigFiles configFiles() {
        return configFiles;
    }

    public LocaleTables catalog() {
        return catalog;
    }

    public Messages messages() {
        return messages;
    }

    public SnapshotHub snapshots() {
        return hub;
    }

    public Scheduling scheduling() {
        return scheduling;
    }

    public IntentBus bus() {
        return bus;
    }

    public EffectFanout fanout() {
        return fanout;
    }

    public WriterThread writer() {
        return writer;
    }

    public StorageProjector projector() {
        return projector;
    }

    public StorageBoot storage() {
        return storage;
    }

    public VaultAdapter vault() {
        return vault;
    }

    public EscrowExecutor escrow() {
        return escrow;
    }

    public ReloadsImpl reloads() {
        return reloads;
    }

    public String backendLabel() {
        return backendLabel;
    }

    public int factionCount() {
        return factionCount;
    }

    public int memberCount() {
        return memberCount;
    }

    public int claimCount() {
        return claimCount;
    }

    /** The last committed effect seq at boot (DB checkpoint folded with the replayed journal tail). */
    public long journalSeq() {
        return journalSeqAtBoot;
    }
}
