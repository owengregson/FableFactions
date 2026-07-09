package dev.fablemc.factions.core.chest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.core.storage.Blobs;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.kernel.intent.ChestIntent;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;
import dev.fablemc.factions.platform.probe.Capabilities;
import dev.fablemc.factions.platform.resolve.ItemCodec;
import dev.fablemc.factions.platform.resolve.Players;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * The team-chest engine behind {@code /f chest} (ref-commands-misc.md §2, proposal-C §7). It holds
 * <b>one shared live 54-slot Bukkit inventory per {@code (faction, chest)}</b> with a viewer
 * refcount, so two members editing the same chest see the same items (killing the reference's
 * last-close-wins dupe/loss bug). Contents are serialised through the platform {@link ItemCodec} on
 * the owning region thread, framed with {@link Blobs}, staged to storage through the injected
 * {@link BlobWriter}, and committed into kernel state as {@link ChestIntent.CommitChestContents}
 * intents — a periodic 10s commit while open plus a force-commit on last close and on disable.
 *
 * <p>On Folia (where a shared inventory cannot safely cross region threads) the engine runs in
 * <b>exclusive-open mode (D-13)</b>: a second opener of an already-open chest is refused, so the
 * live inventory is only ever touched by its single owner's region. The exclusive-open decision and
 * every viewer/readiness transition are <b>atomic</b> (each {@link LiveChest} guards its own state
 * with its monitor and eviction is done under the map's per-key lock), so two openers racing on
 * different region threads can never both attach (finding #3 lost-update dupe).
 *
 * <p><b>Durability (AM-17 CRITICAL tier).</b> A commit stages the framed bytes through the injected
 * {@link BlobWriter} and then routes the authoritative commit through the single-writer intent
 * pipeline as {@link ChestIntent.CommitChestContents} (journaled + fsynced), guarded by a monotonic
 * per-session nonce so a stale session's late commit is rejected by the reducer (finding #26). It is
 * NOT a fire-and-forget async DB write that the disable/reload sweep window could drop and dupe.
 *
 * <p><b>Close wiring.</b> {@link #handleClose} drops one viewer and force-commits + evicts on the
 * last close (releasing the Folia exclusive lock so the chest can reopen). It must be driven from an
 * {@code InventoryCloseEvent} whose top holder is a {@link ChestHolder} — the integrator registers a
 * {@link ChestCloseListener} for that (finding #12); the GUI {@code MenuHolder} router never sees a
 * chest (a chest is editable, not a read-only menu).
 *
 * <p><b>Owning thread(s):</b> {@link #open} / {@link #handleClose} run on the opening/closing
 * player's region thread; the periodic sweep marshals each read onto the owning thread; {@link
 * #commitAndCloseAll} (and its alias {@link #forceCommitAll}) run on the disable/reload thread and
 * are exception-isolated per chest. The inventory bytes never touch JDBC here — {@link
 * BlobReader}/{@link BlobWriter} are the storage seams the integrator wires to {@code Blobs.load}/
 * {@code Blobs.store} on the storage thread. <b>Mutability:</b> the map is concurrent; each {@link
 * LiveChest}'s viewer/readiness state is guarded by its own monitor for cross-thread access.
 */
public final class ChestSessions implements Chests {

    /** The fixed team-chest inventory size (ref-commands-misc.md §2.1). */
    public static final int CHEST_SIZE = 54;

    private static final long COMMIT_PERIOD_SECONDS = 10L;
    private static final String EMPTY_SLOT = "-";
    private static final String SLOT_SEPARATOR = "\n";
    private static final Runnable NO_RETIRED = () -> { };

    private static final Logger LOG = Logger.getLogger(ChestSessions.class.getName());

    private final Scheduling scheduling;
    private final IntentBus bus;
    private final SnapshotHub snapshots;
    private final Messages messages;
    private final ItemCodec itemCodec;
    private final Capabilities caps;
    private final BlobReader blobReader;
    private final BlobWriter blobWriter;

    private final ConcurrentHashMap<ChestKey, LiveChest> live = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ChestKey> playerChest = new ConcurrentHashMap<>();

    // Monotonic session-nonce source: seeded from wall-clock so nonces keep climbing across reloads
    // (which recreate this engine but NOT the kernel's recorded nonce), and start above the 0 that
    // chests load back with after a restart — the reducer accepts >= recorded and rejects lower.
    private final AtomicLong nonceSource = new AtomicLong(System.currentTimeMillis());

    private volatile TaskHandle sweepTask;

    /** Constructor injection (CONTRACTS §4): scheduler, bus, snapshots, text, codec, caps, blob IO seams. */
    public ChestSessions(Scheduling scheduling, IntentBus bus, SnapshotHub snapshots, Messages messages,
                         ItemCodec itemCodec, Capabilities caps, BlobReader blobReader, BlobWriter blobWriter) {
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.itemCodec = Objects.requireNonNull(itemCodec, "itemCodec");
        this.caps = Objects.requireNonNull(caps, "caps");
        this.blobReader = Objects.requireNonNull(blobReader, "blobReader");
        this.blobWriter = Objects.requireNonNull(blobWriter, "blobWriter");
    }

    /** Starts the periodic 10s commit sweep (boot / reload wiring). Idempotent. */
    public synchronized void start() {
        if (sweepTask == null) {
            Duration period = Duration.ofSeconds(COMMIT_PERIOD_SECONDS);
            sweepTask = scheduling.repeatAsync(period, period, this::sweep);
        }
    }

    /** Stops the periodic sweep (does NOT flush — {@link #forceCommitAll} is the disable flush). */
    public synchronized void stop() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    @Override
    public void open(Player player, String chestName) {
        UUID id = player.getUniqueId();
        KernelSnapshot snapshot = snapshots.current();
        Faction faction = factionOf(player, snapshot);
        if (faction == null) {
            messages.to(player, Text.NOT_IN_FACTION);
            return;
        }
        int handle = memberFactionHandle(snapshot, id);
        String name = normalize(chestName, snapshot);
        ChestRef ref = snapshot.state().chests().get(faction.idx(), name);
        long blobRef;
        if (ref == null) {
            int count = snapshot.state().chests().countForFaction(faction.idx());
            int max = snapshot.config().limits().maxTeamChests();
            if (max > 0 && count >= max) {
                messages.to(player, Text.LIMIT_REACHED, String.valueOf(max));
                return;
            }
            bus.submit(new ChestIntent.CreateChest(handle, name, id), Origin.player(id));
            blobRef = ChestRef.EMPTY_BLOB;
        } else {
            blobRef = ref.blobRef();
        }

        ChestKey key = new ChestKey(handle, name);
        boolean[] created = {false};
        LiveChest chest = live.computeIfAbsent(key, k -> {
            created[0] = true;
            return new LiveChest(k, nextNonce());
        });
        if (created[0]) {
            initialise(player, chest, key, name, blobRef);
            return;
        }
        // An already-live shared chest: the check-and-attach is atomic under the chest's monitor so
        // exactly one opener wins the Folia exclusive lock and the viewer refcount never races.
        Acquire outcome;
        synchronized (chest) {
            if (chest.isRetired()) {
                outcome = Acquire.RETIRED;
            } else if (!chest.isReady()) {
                outcome = Acquire.LOADING;
            } else if (caps.folia() && chest.viewers() > 0) {
                outcome = Acquire.IN_USE;
            } else {
                chest.addViewer();
                outcome = Acquire.ACQUIRED;
            }
        }
        switch (outcome) {
            case RETIRED -> {
                // Evicted between our computeIfAbsent and the lock (a concurrent last-close). Help
                // drop the dead session and retry against a fresh one — bounded to a single retry.
                live.remove(key, chest);
                open(player, chestName);
            }
            case LOADING -> messages.to(player, Text.LOADING);
            case IN_USE -> messages.to(player, Text.IN_USE);
            case ACQUIRED -> {
                playerChest.put(id, key);
                player.openInventory(chest.inventory());
                messages.to(player, Text.OPENED, name);
            }
            default -> throw new IllegalStateException("unhandled acquire outcome: " + outcome);
        }
    }

    /** The next monotonic session nonce (one per fresh {@link LiveChest}). */
    private long nextNonce() {
        return nonceSource.incrementAndGet();
    }

    /**
     * The chest-close listener hook (wired by the integrator's {@link ChestCloseListener}): drops
     * one viewer for {@code playerId} and, when the last viewer leaves, retires + evicts the shared
     * inventory (releasing the Folia exclusive lock) and force-commits its final contents through
     * the durable intent path. Runs on the closing player's region thread. The viewer decrement and
     * the retire decision are atomic under the chest's monitor so a concurrent opener can never
     * attach to a session that is being torn down.
     */
    public void handleClose(UUID playerId) {
        ChestKey key = playerChest.remove(playerId);
        if (key == null) {
            return;
        }
        LiveChest chest = live.get(key);
        if (chest == null) {
            return;
        }
        boolean evict;
        boolean ready;
        synchronized (chest) {
            evict = chest.removeViewer() <= 0;
            ready = chest.isReady();
            if (evict) {
                chest.retire();
            }
        }
        if (evict) {
            // Retired first (above), so a racing opener sees RETIRED and reopens fresh; the map
            // removal only clears our exact session, and the final commit is authoritative.
            live.remove(key, chest);
            if (ready) {
                commitInline(chest);
            }
        }
    }

    @Override
    public void forceCommitAll() {
        commitAndCloseAll();
    }

    /**
     * Synchronously force-commits and closes EVERY open chest session, exception-isolated per chest,
     * then clears all session state and releases every Folia exclusive-open lock. Safe to call from
     * the disable/reload thread: each chest is serialized on the CURRENT thread and its contents are
     * routed through the durable {@link ChestIntent.CommitChestContents} intent path (AM-17 CRITICAL
     * tier) rather than a fire-and-forget write, and one chest's commit failure never strands the
     * rest.
     *
     * <p><b>Where the orchestrator must call this:</b> the {@code FeatureReconciler} reload teardown
     * (before the feature scope is rebuilt — a reload otherwise never flushes open chests) and
     * {@code onDisable} (already reached via {@link #forceCommitAll}). See the report for the exact
     * wiring; the byte staging still flows through the injected {@link BlobWriter}, so the orchestrator
     * must ensure that seam is durable at disable (a synchronous store, not a dropped async task).
     *
     * <p><b>Owning thread:</b> the caller's (disable/reload) thread; the writer daemon may already be
     * stopped, in which case the enqueued commit intents are drained by the ordered final drain.
     */
    public void commitAndCloseAll() {
        for (LiveChest chest : live.values()) {
            try {
                boolean ready;
                synchronized (chest) {
                    ready = chest.isReady();
                    chest.retire();
                }
                if (ready) {
                    commitInline(chest);
                }
            } catch (RuntimeException isolated) {
                LOG.log(Level.WARNING, "chest commit failed on flush (continuing with the rest)", isolated);
            }
        }
        live.clear();
        playerChest.clear();
    }

    // ── open helpers ──────────────────────────────────────────────────────────────────────────

    private void initialise(Player player, LiveChest chest, ChestKey key, String name, long blobRef) {
        if (blobRef == ChestRef.EMPTY_BLOB) {
            readyAndOpen(player, chest, key, name, null);
            return;
        }
        blobReader.read(blobRef, framed -> scheduling.runOn(player,
                () -> readyAndOpen(player, chest, key, name, framed),
                () -> live.remove(key, chest)));
    }

    private void readyAndOpen(Player player, LiveChest chest, ChestKey key, String name, byte[] framed) {
        Inventory inventory = buildInventory(key, framed);
        // The creator becomes the first viewer atomically with becoming ready, so a Folia opener that
        // arrives in the load window sees either not-ready (LOADING) or viewers>0 (IN_USE), never a
        // ready chest with a zero refcount it could wrongly acquire.
        chest.readyAsOwner(inventory, player.getUniqueId());
        playerChest.put(player.getUniqueId(), key);
        player.openInventory(chest.inventory());
        messages.to(player, Text.OPENED, name);
    }

    // ── commit ────────────────────────────────────────────────────────────────────────────────

    private void sweep() {
        for (LiveChest chest : live.values()) {
            if (!chest.committable()) {
                continue;
            }
            if (caps.folia()) {
                UUID ownerId = chest.owner();
                Player owner = ownerId == null ? null : Players.get(ownerId);
                if (owner != null) {
                    scheduling.runOn(owner, () -> commitIfActive(chest), NO_RETIRED);
                }
            } else {
                scheduling.runGlobal(() -> commitIfActive(chest));
            }
        }
    }

    /**
     * The marshaled sweep commit: skips if this session was evicted/superseded between the sweep
     * read and now (its key no longer maps to this exact {@link LiveChest}) — committing a stale
     * session's bytes over a newer one is the finding #26 lost-update dupe (the deterministic blob
     * ref means a stale write silently corrupts the newer session's contents).
     */
    private void commitIfActive(LiveChest chest) {
        if (live.get(chest.key()) == chest && chest.committable()) {
            commitInline(chest);
        }
    }

    /** Reads the live inventory on the CURRENT (owning) thread, stages the blob, and commits it. */
    private void commitInline(LiveChest chest) {
        Inventory inventory = chest.inventory();
        if (inventory == null) {
            return;
        }
        byte[] framed = serialize(inventory.getContents());
        long ref = blobRefFor(chest.key());
        // Stage the framed bytes, then route the authoritative commit through the journaled intent
        // pipeline (guarded by the session nonce). Bytes first so the ref is only committed for
        // content that has been handed to the durable store.
        blobWriter.write(ref, framed, System.currentTimeMillis());
        bus.submitSystem(new ChestIntent.CommitChestContents(
                chest.key().faction(), chest.key().name(), ref, chest.nonce(), null));
    }

    private Inventory buildInventory(ChestKey key, byte[] framed) {
        ChestHolder holder = new ChestHolder(key);
        Inventory inventory = Bukkit.createInventory(holder, CHEST_SIZE, "Faction Chest: " + key.name());
        holder.bind(inventory);
        if (framed != null) {
            inventory.setContents(deserialize(framed));
        }
        return inventory;
    }

    private byte[] serialize(ItemStack[] contents) {
        StringBuilder builder = new StringBuilder();
        for (int slot = 0; slot < CHEST_SIZE; slot++) {
            if (slot > 0) {
                builder.append(SLOT_SEPARATOR);
            }
            ItemStack item = slot < contents.length ? contents[slot] : null;
            builder.append(item == null ? EMPTY_SLOT : itemCodec.encode(item));
        }
        byte[] payload = builder.toString().getBytes(StandardCharsets.UTF_8);
        int format = itemCodec.modern() ? Blobs.FORMAT_MODERN : Blobs.FORMAT_LEGACY;
        return Blobs.wrap(format, itemCodec.dataVersion(), payload);
    }

    private ItemStack[] deserialize(byte[] framed) {
        ItemStack[] items = new ItemStack[CHEST_SIZE];
        Blobs.Blob blob;
        try {
            blob = Blobs.unwrap(framed);
        } catch (RuntimeException malformed) {
            return items; // corrupt/foreign blob → open empty rather than crash
        }
        String payload = new String(blob.payload(), StandardCharsets.UTF_8);
        if (payload.isEmpty()) {
            return items;
        }
        String[] tokens = payload.split(SLOT_SEPARATOR, -1);
        int count = Math.min(tokens.length, CHEST_SIZE);
        for (int slot = 0; slot < count; slot++) {
            String token = tokens[slot];
            if (token.isEmpty() || token.equals(EMPTY_SLOT)) {
                continue;
            }
            try {
                items[slot] = itemCodec.decode(token);
            } catch (RuntimeException badItem) {
                items[slot] = null; // drop one unreadable stack rather than losing the whole chest
            }
        }
        return items;
    }

    /** A stable 64-bit blob ref per {@code (faction handle, name)} (FNV-1a), never the empty sentinel. */
    private static long blobRefFor(ChestKey key) {
        long hash = 0xcbf29ce484222325L;
        hash = (hash ^ (key.faction() & 0xffffffffL)) * 0x100000001b3L;
        String name = key.name();
        for (int i = 0; i < name.length(); i++) {
            hash = (hash ^ name.charAt(i)) * 0x100000001b3L;
        }
        return hash == ChestRef.EMPTY_BLOB ? 1L : hash;
    }

    private Faction factionOf(Player player, KernelSnapshot snapshot) {
        int ordinal = snapshot.memberOrdinal(player.getUniqueId());
        if (ordinal < 0) {
            return null;
        }
        MemberView member = snapshot.member(ordinal);
        if (member == null) {
            return null;
        }
        Faction faction = snapshot.faction(member.factionHandle());
        return faction != null && faction.isNormal() ? faction : null;
    }

    private static int memberFactionHandle(KernelSnapshot snapshot, UUID id) {
        MemberView member = snapshot.member(snapshot.memberOrdinal(id));
        return member.factionHandle();
    }

    private static String normalize(String chestName, KernelSnapshot snapshot) {
        String trimmed = chestName == null ? "" : chestName.trim().toLowerCase(java.util.Locale.ROOT);
        return trimmed.isEmpty() ? snapshot.config().limits().defaultTeamChestName() : trimmed;
    }

    // ── nested types ────────────────────────────────────────────────────────────────────────────

    /** The outcomes of attaching a viewer to an already-live shared chest. */
    private enum Acquire {
        /** The session was evicted between lookup and lock — retry against a fresh one. */
        RETIRED,
        /** The inventory is still loading; no viewer attached. */
        LOADING,
        /** Folia exclusive-open: another viewer already holds it; refused. */
        IN_USE,
        /** A viewer was attached; open the inventory. */
        ACQUIRED
    }

    /** The identity of one team chest: the owning faction handle and normalized chest name. */
    record ChestKey(int faction, String name) {
    }

    /**
     * One shared live chest inventory with a viewer refcount and a monotonic session nonce. The
     * refcount / readiness / retire transitions are Bukkit-free so they are unit-testable directly.
     *
     * <p><b>Owning thread(s):</b> mutated from the opening/closing region threads and read from the
     * async sweep, so every viewer/readiness accessor is guarded by this instance's monitor.
     * <b>Mutability:</b> monitor-confined; {@link #key} and {@link #nonce} are final.
     */
    static final class LiveChest {

        private final ChestKey key;
        private final long nonce;
        private Inventory inventory;
        private UUID owner;
        private int viewers;
        private boolean ready;
        private boolean retired;

        LiveChest(ChestKey key, long nonce) {
            this.key = key;
            this.nonce = nonce;
        }

        ChestKey key() {
            return key;
        }

        long nonce() {
            return nonce;
        }

        synchronized Inventory inventory() {
            return inventory;
        }

        synchronized UUID owner() {
            return owner;
        }

        synchronized boolean isReady() {
            return ready;
        }

        /** {@code true} once this session has been torn down (last close / flush). */
        synchronized boolean isRetired() {
            return retired;
        }

        /** {@code true} iff ready with at least one viewer — the sweep-commit precondition. */
        synchronized boolean committable() {
            return ready && viewers > 0;
        }

        /** Marks the chest live with its built inventory and first owner (Folia exclusive owner). */
        synchronized void ready(Inventory inventory, UUID owner) {
            this.inventory = inventory;
            this.owner = owner;
            this.ready = true;
        }

        /** Becomes ready with its owner AND that owner as the first viewer, atomically. */
        synchronized void readyAsOwner(Inventory inventory, UUID owner) {
            ready(inventory, owner);
            this.viewers = 1;
        }

        /** Marks this session torn down so no further opener may attach to it. */
        synchronized void retire() {
            this.retired = true;
        }

        /** Adds one viewer; returns the new count. */
        synchronized int addViewer() {
            return ++viewers;
        }

        /** Removes one viewer (floored at 0); returns the remaining count. */
        synchronized int removeViewer() {
            if (viewers > 0) {
                viewers--;
            }
            return viewers;
        }

        synchronized int viewers() {
            return viewers;
        }
    }

    /**
     * The marker {@link InventoryHolder} that identifies a team-chest inventory across the whole
     * version range (custom-holder identification, no {@code InventoryView} call — D-13/AM-16).
     */
    static final class ChestHolder implements InventoryHolder {

        private final ChestKey key;
        private Inventory inventory;

        ChestHolder(ChestKey key) {
            this.key = key;
        }

        ChestKey key() {
            return key;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Reads a raw framed chest blob off-thread; the integrator wires this to {@code Blobs.load}. */
    @FunctionalInterface
    public interface BlobReader {
        /** Loads the bytes under {@code ref} and invokes {@code onLoaded} (with {@code null} if absent). */
        void read(long ref, Consumer<byte[]> onLoaded);
    }

    /** Persists a raw framed chest blob off-thread; the integrator wires this to {@code Blobs.store}. */
    @FunctionalInterface
    public interface BlobWriter {
        /** Stores the framed {@code bytes} under {@code ref}, stamped {@code createdAt}. */
        void write(long ref, byte[] bytes, long createdAt);
    }

    /** Interned chest message keys (house style §8c). */
    private static final class Text {
        static final MessageKey NOT_IN_FACTION = MessageKey.of("general.not-in-faction");
        static final MessageKey LIMIT_REACHED = MessageKey.of("chest.limit-reached");
        static final MessageKey OPENED = MessageKey.of("chest.opened");
        static final MessageKey IN_USE = MessageKey.of("custom.chest.in-use");
        static final MessageKey LOADING = MessageKey.of("custom.chest.loading");

        private Text() {
        }
    }
}
