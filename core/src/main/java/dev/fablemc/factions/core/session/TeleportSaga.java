package dev.fablemc.factions.core.session;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import dev.fablemc.factions.core.economy.VaultAdapter;
import dev.fablemc.factions.core.integration.essentials.EssentialsInterop;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.Home;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;
import dev.fablemc.factions.kernel.state.Warp;
import dev.fablemc.factions.platform.probe.Capabilities;
import dev.fablemc.factions.platform.resolve.Worlds;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * The first-party teleport saga behind {@code /f home} and {@code /f warp} (proposal-C §4.6 / D-6):
 * validate the destination world is loaded <b>before</b> charging → charge any warp cost → run a
 * warmup countdown on the player's own region thread that cancels on movement, combat-tag or damage
 * → teleport ({@code teleportAsync} when the capability probes true, else a sync teleport on the
 * owning thread) → refund on <b>any</b> failure after the charge. This gives the plugin its own
 * cancel-on-move/damage warmup (reopening the reference's instant-teleport combat-escape bug) rather
 * than delegating to Essentials.
 *
 * <p><b>Owning thread(s):</b> {@link #beginHome}/{@link #beginWarp} are called from a command
 * {@code perform} on the player's region/main thread, so the confined {@link PlayerSession} (AM-14)
 * is mutated directly (the warmup handle) and the warmup ticks re-enter on that same region thread
 * via {@link Scheduling#repeatOn}. <b>Mutability:</b> immutable (only injected collaborators);
 * per-player warmup state lives in the session.
 */
public final class TeleportSaga implements Travel {

    private static final int WARMUP_SECONDS = 3;
    private static final long CHECK_PERIOD_TICKS = 10L;
    private static final int WARMUP_TICKS = WARMUP_SECONDS * 20;

    /** Reflective {@code Entity#teleportAsync(Location)} (Paper 1.13+), or {@code null} on legacy. */
    private static final Method TELEPORT_ASYNC = resolveTeleportAsync();

    private final Scheduling scheduling;
    private final SessionRegistry sessions;
    private final Messages messages;
    private final Worlds worlds;
    private final VaultAdapter vault;
    private final CombatTags combatTags;
    private final SnapshotHub snapshots;
    private final Capabilities caps;
    /** EssentialsX jail/teleport façade for the {@code /f home} + {@code /f warp} jail gate; {@code null} disables the gate (graceful absence, ref-integrations §4). */
    private final EssentialsInterop essentials;

    /**
     * Full constructor injection (CONTRACTS §4): scheduler, sessions, text, world registry, Vault,
     * combat, snapshots, caps, and the EssentialsX façade behind the jail gate. Boot should call this
     * overload so a jailed player cannot {@code /f home} / {@code /f warp} out (finding #13 jail gate).
     */
    public TeleportSaga(Scheduling scheduling, SessionRegistry sessions, Messages messages, Worlds worlds,
                        VaultAdapter vault, CombatTags combatTags, SnapshotHub snapshots, Capabilities caps,
                        EssentialsInterop essentials) {
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.combatTags = Objects.requireNonNull(combatTags, "combatTags");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.caps = Objects.requireNonNull(caps, "caps");
        this.essentials = essentials;   // nullable: absent Essentials ⇒ no jail blocking (Noop parity)
    }

    /**
     * Legacy constructor without the EssentialsX façade — the jail gate is disabled (parity with
     * {@code NoopEssentialsInterop}). Retained so the current boot wiring still compiles; boot should
     * migrate to the {@code essentials}-taking overload to actually block jailed teleports.
     */
    public TeleportSaga(Scheduling scheduling, SessionRegistry sessions, Messages messages, Worlds worlds,
                        VaultAdapter vault, CombatTags combatTags, SnapshotHub snapshots, Capabilities caps) {
        this(scheduling, sessions, messages, worlds, vault, combatTags, snapshots, caps, null);
    }

    @Override
    public void beginHome(Player player, KernelSnapshot snapshot) {
        if (isJailed(player)) {
            messages.to(player, Text.HOME_JAILED);
            return;
        }
        Faction faction = factionOf(player, snapshot);
        if (faction == null) {
            return; // command guard already reported the "not in a faction" case
        }
        Home home = faction.home();
        if (home == null) {
            messages.to(player, Text.HOME_NO_HOME);
            return;
        }
        World world = worldOf(home.worldIdx());
        if (world == null) {
            messages.to(player, Text.WORLD_NOT_LOADED);
            return;
        }
        Location dest = new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        PlayerSession session = sessions.get(player.getUniqueId());
        begin(player, session, dest, Text.HOME_TELEPORTING, Text.HOME_TELEPORTED, Text.HOME_TELEPORT_FAILED,
                null, 0.0, false);
    }

    @Override
    public void beginWarp(Player player, String warpName, String password) {
        if (isJailed(player)) {
            messages.to(player, Text.WARP_JAILED);
            return;
        }
        KernelSnapshot snapshot = snapshots.current();
        Faction faction = factionOf(player, snapshot);
        if (faction == null) {
            return;
        }
        Warp warp = snapshot.state().warps().get(faction.idx(), warpName);
        if (warp == null) {
            messages.to(player, Text.WARP_NOT_FOUND, warpName);
            return;
        }
        if (hasPassword(warp) && (password == null || !warp.password().equals(password))) {
            messages.to(player, Text.WARP_PASSWORD_REQUIRED, warp.name());
            return;
        }
        // Validate the destination world is loaded BEFORE charging (no charge on a dead world).
        World world = worldOf(warp.worldIdx());
        if (world == null) {
            messages.to(player, Text.WORLD_NOT_LOADED);
            return;
        }
        // Reject BEFORE charging (findings #14/#34/#36): a warmup already running or an active combat
        // tag would otherwise take the player's money then silently drop the teleport with no refund.
        // The charge below runs only once we know the saga will start; any later abort refunds.
        PlayerSession session = sessions.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (session != null && session.warmupTask() != null) {
            messages.to(player, Text.ALREADY_WARMING);
            return;
        }
        if (combatTags.inCombat(session, now)) {
            messages.to(player, Text.COMBAT_BLOCKED);
            return;
        }
        double cost = warp.useCost();
        boolean charged = false;
        if (cost > 0.0) {
            if (!vault.present()) {
                messages.to(player, Text.WARP_COST_NO_ECONOMY);
                return;
            }
            if (!vault.has(player, cost) || !vault.withdraw(player, cost)) {
                messages.to(player, Text.WARP_COST_INSUFFICIENT, money(cost));
                return;
            }
            charged = true;
            messages.to(player, Text.WARP_COST_CHARGED, money(cost), warp.name());
        }
        Location dest = new Location(world, warp.x(), warp.y(), warp.z(), warp.yaw(), warp.pitch());
        begin(player, session, dest, Text.WARP_TELEPORTING, Text.WARP_TELEPORTED, Text.WARP_TELEPORT_FAILED,
                warp.name(), cost, charged);
    }

    /**
     * Whether {@code player} is currently combat-tagged — the read the {@code /f fly} command consults
     * before enabling flight (finding #30). Delegates to the confined session's window via
     * {@link CombatTags}; {@code false} when the player has no live session or tagging is disabled.
     * Must be called on the player's region thread (reads the confined session, AM-14).
     */
    @Override
    public boolean inCombat(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        return combatTags.inCombat(session, System.currentTimeMillis());
    }

    /** The jail gate: {@code true} only when a wired Essentials façade reports the player jailed. */
    private boolean isJailed(Player player) {
        return essentials != null && essentials.isJailed(player);
    }

    // ── warmup ───────────────────────────────────────────────────────────────────────────────

    private void begin(Player player, PlayerSession session, Location dest, MessageKey warmingKey,
                       MessageKey doneKey, MessageKey failedKey, String warpName, double cost, boolean charged) {
        if (session == null) {
            // Untracked player (no live session): teleport immediately, best-effort refund on failure.
            teleport(player, dest, doneKey, failedKey, charged, cost);
            return;
        }
        // Defensive re-check: the warp path already rejected these before charging (so a charge is
        // never live here); the home path (never charged) relies on them. Refund on any charged abort
        // upholds the invariant that money is destroyed on no path.
        if (session.warmupTask() != null) {
            refundIfCharged(player, charged, cost);
            messages.to(player, Text.ALREADY_WARMING);
            return;
        }
        long now = System.currentTimeMillis();
        if (combatTags.inCombat(session, now)) {
            refundIfCharged(player, charged, cost);
            messages.to(player, Text.COMBAT_BLOCKED);
            return;
        }
        messages.to(player, warmingKey, warpName == null ? "" : warpName);
        Warmup warmup = new Warmup(player, session, dest, doneKey, failedKey, cost, charged);
        // A scheduler retire (entity gone mid-countdown) fires onRetired; a session teardown
        // (logout/kick) runs the armed abort. Both funnel into Warmup.refundAbort, which returns a
        // charged warp's cost exactly once (findings #27/#36/#59).
        TaskHandle handle = scheduling.repeatOn(player, CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS,
                warmup, warmup::onRetired);
        session.armWarmup(handle, warmup::refundAbort);
    }

    /** One in-flight warmup, confined to the player's region thread (repeatOn re-enters there). */
    private final class Warmup implements Runnable {

        private final Player player;
        private final PlayerSession session;
        private final Location dest;
        private final MessageKey doneKey;
        private final MessageKey failedKey;
        private final double cost;
        private final boolean charged;
        private final World startWorld;
        private final int startX;
        private final int startY;
        private final int startZ;
        private int elapsedTicks;
        private boolean settled;   // single-shot: the pre-teleport money outcome has been decided

        Warmup(Player player, PlayerSession session, Location dest, MessageKey doneKey,
               MessageKey failedKey, double cost, boolean charged) {
            this.player = player;
            this.session = session;
            this.dest = dest;
            this.doneKey = doneKey;
            this.failedKey = failedKey;
            this.cost = cost;
            this.charged = charged;
            Location origin = player.getLocation();
            this.startWorld = origin.getWorld();
            this.startX = origin.getBlockX();
            this.startY = origin.getBlockY();
            this.startZ = origin.getBlockZ();
        }

        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (combatTags.inCombat(session, now) || moved()) {
                settled = true;             // this path owns the refund (online-aware) below
                session.completeWarmup();
                refundIfCharged(player, charged, cost);
                messages.to(player, Text.WARMUP_CANCELLED);
                return;
            }
            elapsedTicks += CHECK_PERIOD_TICKS;
            if (elapsedTicks >= WARMUP_TICKS) {
                settled = true;             // teleport takes ownership; finish() refunds only on failure
                session.completeWarmup();
                teleport(player, dest, doneKey, failedKey, charged, cost);
            }
        }

        /** The scheduler retired the entity mid-countdown: drop the session task and refund (once). */
        void onRetired() {
            session.completeWarmup();
            refundAbort();
        }

        /**
         * Idempotent offline-safe refund of a charged warp — the abort hook armed on the session and
         * the {@link #onRetired} path both call it, so a retire and a teardown firing together refund
         * at most once. A no-op once the money outcome has already been settled (teleport, move/combat
         * cancel, or a prior abort).
         */
        void refundAbort() {
            if (settled) {
                return;
            }
            settled = true;
            if (charged) {
                vault.depositOffline(player.getUniqueId(), cost);
            }
        }

        private boolean moved() {
            Location cur = player.getLocation();
            return cur.getWorld() != startWorld || cur.getBlockX() != startX
                    || cur.getBlockY() != startY || cur.getBlockZ() != startZ;
        }
    }

    // ── teleport + refund ──────────────────────────────────────────────────────────────────────

    private void teleport(Player player, Location dest, MessageKey doneKey, MessageKey failedKey,
                          boolean charged, double cost) {
        if (caps.asyncTeleport() && TELEPORT_ASYNC != null) {
            try {
                Object future = TELEPORT_ASYNC.invoke(player, dest);
                if (future instanceof CompletableFuture<?> completable) {
                    completable.whenComplete((ok, err) ->
                            finish(player, Boolean.TRUE.equals(ok) && err == null, doneKey, failedKey, charged, cost));
                    return;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                // fall through to the synchronous teleport on the owning thread
            }
        }
        finish(player, player.teleport(dest), doneKey, failedKey, charged, cost);
    }

    private void finish(Player player, boolean success, MessageKey doneKey, MessageKey failedKey,
                        boolean charged, double cost) {
        if (success) {
            messages.toPlayer(player.getUniqueId(), doneKey);
            return;
        }
        messages.toPlayer(player.getUniqueId(), failedKey);
        refundIfCharged(player, charged, cost);
    }

    private void refundIfCharged(Player player, boolean charged, double cost) {
        if (!charged) {
            return;
        }
        UUID id = player.getUniqueId();
        scheduling.runOn(player, () -> vault.deposit(player, cost), () -> vault.depositOffline(id, cost));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

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

    private World worldOf(int worldIdx) {
        UUID uid = worlds.uid(worldIdx);
        return uid == null ? null : Bukkit.getWorld(uid);
    }

    private static boolean hasPassword(Warp warp) {
        return warp.password() != null && !warp.password().isEmpty();
    }

    private static String money(double amount) {
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    private static Method resolveTeleportAsync() {
        try {
            return Player.class.getMethod("teleportAsync", Location.class);
        } catch (NoSuchMethodException | LinkageError | RuntimeException absent) {
            return null;
        }
    }

    /** Interned travel message keys (house style §8c — interned once, never per-call). */
    private static final class Text {
        static final MessageKey HOME_NO_HOME = MessageKey.of("home.no-home");
        static final MessageKey HOME_TELEPORTING = MessageKey.of("home.teleporting");
        static final MessageKey HOME_TELEPORTED = MessageKey.of("home.teleported");
        static final MessageKey HOME_TELEPORT_FAILED = MessageKey.of("home.teleport-failed");
        static final MessageKey HOME_JAILED = MessageKey.of("home.jailed");

        static final MessageKey WARP_NOT_FOUND = MessageKey.of("warp.not-found");
        static final MessageKey WARP_JAILED = MessageKey.of("warp.jailed");
        static final MessageKey WARP_TELEPORTING = MessageKey.of("warp.teleporting");
        static final MessageKey WARP_TELEPORTED = MessageKey.of("warp.teleported");
        static final MessageKey WARP_TELEPORT_FAILED = MessageKey.of("warp.teleport-failed");
        static final MessageKey WARP_PASSWORD_REQUIRED = MessageKey.of("warp.password-required");
        static final MessageKey WARP_COST_NO_ECONOMY = MessageKey.of("custom.warp.cost-no-economy");
        static final MessageKey WARP_COST_INSUFFICIENT = MessageKey.of("custom.warp.cost-insufficient");
        static final MessageKey WARP_COST_CHARGED = MessageKey.of("custom.warp.cost-charged");

        static final MessageKey WORLD_NOT_LOADED = MessageKey.of("custom.warp.world-not-loaded");
        static final MessageKey COMBAT_BLOCKED = MessageKey.of("custom.travel.combat-blocked");
        static final MessageKey WARMUP_CANCELLED = MessageKey.of("custom.travel.warmup-cancelled");
        static final MessageKey ALREADY_WARMING = MessageKey.of("custom.travel.already-warming");

        private Text() {
        }
    }
}
