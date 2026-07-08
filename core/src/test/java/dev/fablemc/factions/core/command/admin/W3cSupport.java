package dev.fablemc.factions.core.command.admin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;

import dev.fablemc.factions.core.chest.Chests;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.predefined.Predefined;
import dev.fablemc.factions.core.config.Reloads;
import dev.fablemc.factions.core.messages.MessageCatalog;
import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.ChestTable;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.MemberDirectory;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * Shared headless harness for the W3c command-tree tests: a two-faction snapshot ("Wolves" + a
 * relation target "Foxes"), proxy-backed player/console senders that capture rendered messages
 * (through the Bungee-component sink when the probe enables it), a real {@link IntentBus} to drain
 * submitted intents, and simple recording seams. Kept in the {@code admin} test package and reused
 * by every tree's dispatch test.
 */
final class W3cSupport {

    static final int WOLVES_ORDINAL = 2;   // FactionHandle.FIRST_NORMAL_ORDINAL
    static final int FOXES_ORDINAL = 3;

    static final UUID OWNER = new UUID(0L, 100L);    // Wolves owner (rank idx 2)
    static final UUID OFFICER = new UUID(0L, 101L);  // Wolves officer (rank idx 1)
    static final UUID MEMBER = new UUID(0L, 102L);   // Wolves member (rank idx 0)
    static final UUID OUTSIDER = new UUID(0L, 200L); // factionless

    private W3cSupport() {
    }

    // ── snapshot ─────────────────────────────────────────────────────────────────────────────

    static KernelSnapshot snapshot() {
        Rank[] ranks = {
                new Rank("member", "member", null, Rank.PRIORITY_MEMBER),
                new Rank("officer", "officer", null, Rank.PRIORITY_OFFICER),
                new Rank("owner", "owner", null, Rank.PRIORITY_OWNER),
        };
        Faction wolves = faction(WOLVES_ORDINAL, "Wolves", OWNER, ranks);
        Faction foxes = faction(FOXES_ORDINAL, "Foxes", new UUID(0L, 300L), ranks.clone());

        FactionArena arena = FactionArena.empty()
                .withFaction(WOLVES_ORDINAL, wolves)
                .withFaction(FOXES_ORDINAL, foxes);
        int wolvesHandle = arena.handleOf(WOLVES_ORDINAL);

        NameIndex names = NameIndex.empty()
                .with(wolves.nameFolded(), WOLVES_ORDINAL)
                .with(foxes.nameFolded(), FOXES_ORDINAL);

        PlayerLedger ledger = PlayerLedger.empty();
        MemberDirectory dir = MemberDirectory.empty();
        int o0 = ledger.nextOrdinal();
        ledger = ledger.withNewMember(o0, OWNER, "Alpha").withFactionHandle(o0, wolvesHandle).withRankIdx(o0, 2);
        dir = dir.withMapping(OWNER, o0);
        int o1 = ledger.nextOrdinal();
        ledger = ledger.withNewMember(o1, OFFICER, "Bravo").withFactionHandle(o1, wolvesHandle).withRankIdx(o1, 1);
        dir = dir.withMapping(OFFICER, o1);
        int o2 = ledger.nextOrdinal();
        ledger = ledger.withNewMember(o2, MEMBER, "Charlie").withFactionHandle(o2, wolvesHandle).withRankIdx(o2, 0);
        dir = dir.withMapping(MEMBER, o2);
        int o3 = ledger.nextOrdinal();
        ledger = ledger.withNewMember(o3, OUTSIDER, "Delta");
        dir = dir.withMapping(OUTSIDER, o3);

        ChestTable chests = ChestTable.empty()
                .set(WOLVES_ORDINAL, new ChestRef("vault", ChestRef.EMPTY_BLOB, 0L));

        KernelState state = KernelState.empty()
                .withFactions(arena)
                .withFactionNames(names)
                .withLedger(ledger)
                .withMembers(dir)
                .withChests(chests);
        return new KernelSnapshot(state);
    }

    private static Faction faction(int ord, String name, UUID owner, Rank[] ranks) {
        return new Faction(ord, new UUID(1L, ord), name, NameIndex.fold(name), owner, "", "", 0L,
                0.0, 0.0, 0L, RelationEdges.NO_ORDINALS, RelationEdges.NO_KINDS,
                RelationEdges.NO_ORDINALS, RelationEdges.NO_KINDS, null, Faction.NO_SHIELD, 0, false,
                ranks, 0, 0.0, 0L, FactionClaimList.empty(), name, name);
    }

    // ── context / services ───────────────────────────────────────────────────────────────────

    static IntentBus newBus() {
        return new IntentBus(() -> 0L, () -> 0, () -> { });
    }

    static Messages messages(KernelSnapshot snapshot) {
        return new Messages(new EchoCatalog(), new SnapshotHub(snapshot), new InlineScheduling());
    }

    static CommandContext.Services services(IntentBus bus, KernelSnapshot snapshot,
                                            Chests chests, Reloads reloads) {
        return new CommandContext.Services(null, bus, messages(snapshot), new InlineScheduling(),
                null, chests, null, reloads);
    }

    static CommandContext ctx(CommandContext.Services services, CommandSender sender,
                              KernelSnapshot snapshot, String... args) {
        Player player = sender instanceof Player casted ? casted : null;
        return new CommandContext(services, sender, player, List.of(args), snapshot);
    }

    static List<Intent> drain(IntentBus bus) {
        List<IntentEnvelope> out = new ArrayList<>();
        bus.drain(out, 64);
        List<Intent> intents = new ArrayList<>(out.size());
        for (IntentEnvelope envelope : out) {
            intents.add(envelope.intent());
        }
        return intents;
    }

    // ── senders ──────────────────────────────────────────────────────────────────────────────

    static Actor player(UUID id, String name, String... permissions) {
        return new Actor(id, name, permissions);
    }

    static Actor console(String... permissions) {
        return new Actor(null, "Console", permissions);
    }

    /** A proxy-backed {@link CommandSender}/{@link Player} that records rendered messages. */
    static final class Actor implements InvocationHandler {

        final List<String> captured = new ArrayList<>();
        private final UUID uuid;
        private final String name;
        private final Set<String> permissions = new HashSet<>();
        private final CapturingSpigot spigot = new CapturingSpigot();

        Actor(UUID uuid, String name, String... permissions) {
            this.uuid = uuid;
            this.name = name;
            for (String permission : permissions) {
                this.permissions.add(permission);
            }
        }

        CommandSender asSender() {
            return (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {CommandSender.class}, this);
        }

        Player asPlayer() {
            return (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Player.class}, this);
        }

        String sole() {
            if (captured.size() != 1) {
                throw new AssertionError("expected exactly one message, got " + captured);
            }
            return captured.get(0);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "sendMessage":
                    if (args != null && args.length > 0 && args[0] instanceof String s) {
                        captured.add(s);
                    } else if (args != null && args.length > 0 && args[0] instanceof String[] arr) {
                        for (String line : arr) {
                            captured.add(line);
                        }
                    }
                    return null;
                case "hasPermission":
                case "isPermissionSet":
                    return permitted(args[0]);
                case "getUniqueId":
                    return uuid;
                case "getName":
                    return name;
                case "spigot":
                    return spigot;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return name;
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private boolean permitted(Object node) {
            if (node instanceof String s) {
                return permissions.contains(s);
            }
            return node instanceof Permission perm && permissions.contains(perm.getName());
        }

        private final class CapturingSpigot extends Player.Spigot {
            @Override
            public void sendMessage(BaseComponent... components) {
                StringBuilder sb = new StringBuilder();
                for (BaseComponent component : components) {
                    sb.append(component.toPlainText());
                }
                captured.add(sb.toString());
            }

            @Override
            public void sendMessage(BaseComponent component) {
                captured.add(component.toPlainText());
            }
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == short.class || type == byte.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    // ── message catalog + scheduler doubles ──────────────────────────────────────────────────

    /** Echoes {@code key|arg,arg} so the delivered legacy string is assertable. */
    static final class EchoCatalog implements MessageCatalog {
        @Override
        public Component render(int localeIdx, MessageKey key, String... args) {
            return Component.text(key.key() + "|" + String.join(",", args));
        }

        @Override
        public int localeIndex(String bcp47Tag) {
            return 0;
        }

        @Override
        public int defaultLocale() {
            return 0;
        }
    }

    /** Runs every scheduled task inline on the calling thread. */
    static final class InlineScheduling implements Scheduling {
        private static final TaskHandle NOOP = new TaskHandle() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean cancelled() {
                return true;
            }
        };

        @Override
        public void runGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void runAt(Location location, Runnable task) {
            task.run();
        }

        @Override
        public void runOn(Entity entity, Runnable task, Runnable retired) {
            task.run();
        }

        @Override
        public void runOnLater(Entity entity, long delayTicks, Runnable task, Runnable retired) {
            task.run();
        }

        @Override
        public boolean isOwnedByCurrentRegion(Entity entity) {
            return true;
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public TaskHandle repeatGlobal(long initialTicks, long periodTicks, Runnable task) {
            return NOOP;
        }

        @Override
        public TaskHandle repeatOn(Entity entity, long initialTicks, long periodTicks, Runnable task,
                                   Runnable retired) {
            return NOOP;
        }

        @Override
        public TaskHandle repeatAsync(Duration initial, Duration period, Runnable task) {
            return NOOP;
        }

        @Override
        public String describe() {
            return "inline-test";
        }
    }

    // ── recording seams ──────────────────────────────────────────────────────────────────────

    /** Records {@code open} calls; never blocks. */
    static final class RecordingChests implements Chests {
        final List<String> opened = new ArrayList<>();

        @Override
        public void open(Player player, String chestName) {
            opened.add(chestName);
        }

        @Override
        public void forceCommitAll() {
        }
    }

    /** Completes reload immediately with a fixed issue list. */
    static final class ImmediateReloads implements Reloads {
        private final List<String> issues;
        int calls;

        ImmediateReloads(List<String> issues) {
            this.issues = issues;
        }

        @Override
        public CompletionStage<List<String>> reload() {
            calls++;
            return CompletableFuture.completedFuture(issues);
        }
    }

    /** Records predefined authoring calls. */
    static final class RecordingPredefined implements Predefined {
        final List<String> saves = new ArrayList<>();
        boolean available = true;
        int reloads;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public void saveClaim(String factionName, org.bukkit.World world, int chunkX, int chunkZ) {
            saves.add("claim:" + factionName);
        }

        @Override
        public void saveHome(String factionName, Location location) {
            saves.add("home:" + factionName);
        }

        @Override
        public void reload() {
            reloads++;
        }
    }

    /** Serves fixed audit / power-history pages immediately. */
    static final class FakeQueries implements AdminQueries {
        List<AuditRow> auditRows = List.of();
        List<PowerRow> powerRows = List.of();

        @Override
        public CompletionStage<List<AuditRow>> auditPage(UUID factionId, String actionId, int limit, int offset) {
            return CompletableFuture.completedFuture(auditRows);
        }

        @Override
        public CompletionStage<List<PowerRow>> powerHistoryPage(UUID playerId, int limit, int offset) {
            return CompletableFuture.completedFuture(powerRows);
        }
    }
}
