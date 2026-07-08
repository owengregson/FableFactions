# FableFactions — Implementation Contracts

**Status: NORMATIVE for all implementation agents.** This pins (1) the module/package/file
tree with ownership boundaries, (2) every type signature that crosses an agent boundary,
(3) conventions. `docs/ARCHITECTURE.md` governs design intent; `docs/design/proposal-C.md`
supplies detail where both are silent; `docs/research/pvp-*.md` are the behavior parity
specs. When you implement a file, its behavior spec citation is in your work order.

Java level: language 17 (records, sealed, switch patterns OK; **no** Java 18+ APIs — the jar
is jvmdg-downgraded to Java 8, and `verifyJdk8Api` will fail exotic stdlib usage; stick to
long-stable `java.util`/`java.util.concurrent`/`java.nio` APIs on all paths; when in doubt
prefer Java-8-era stdlib methods). No Lombok. No streams/iterators/varargs/boxing on hot
paths (event handlers, Verdicts, atlas/ledger probes). UTF-8, LF, 4-space indent, ≤120 cols.
Every class javadoc states: owning thread(s), mutability class (immutable value / confined /
COW), and for kernel types the reducer-only-mutation rule.

---

## 1. Repository layout (top level)

```
settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml
support-matrix.json
kernel/   api/   platform/   core/   compat/folia/   compat/modern/   probe/
scripts/tools/Jdk8ApiGate.java  scripts/tools/FloorSymbolDump.java
scripts/floor-symbols.txt        (curated manifest, AM-13)
docs/  (this documentation)
```

Gradle module ids: `:kernel :api :platform :core :compat-folia :compat-modern :probe`
(compat projectDirs remapped as in Mental). `group = "dev.fablemc"`,
`version` in gradle.properties (`1.0.0-beta.1`). Root subprojects block: toolchain 25,
`options.release = 17`, `-parameters`, UTF-8, JUnit platform.

---

## 2. `:kernel` — package `dev.fablemc.factions.kernel`

```
kernel/src/main/java/dev/fablemc/factions/kernel/
  ids/        ChunkKeys.java  FactionHandle.java  WorldRef.java
  state/      KernelState.java  KernelSnapshot.java  FactionArena.java  Faction.java
              Rank.java  Home.java  Warp.java  ChestRef.java
              PlayerLedger.java  MemberDirectory.java  MemberView.java
              ClaimAtlas.java  RegionTable.java  FactionClaimList.java
              RelationEdges.java  NameIndex.java
              InviteTable.java  MergeTable.java  InboxTable.java  EscrowTable.java
              WarpTable.java  ChestTable.java  ZoneStats.java  OnlineSet.java
  intent/     Intent.java (sealed) + one record per intent (proposal-C §4.2 vocabulary,
              plus paged continuations per AM-5: DisbandPage, UnclaimAllPage, MergePage,
              TaxSweepPage, ZonePage, RetagPage)  IntentEnvelope.java  Origin.java
  effect/     Effect.java (sealed) + one record per effect (proposal-C §4.3 vocabulary)
              EffectList.java (reused append buffer)
  reduce/     Reducer.java  Outcome.java
  rules/      ClaimRules.java  PowerMath.java  EconomyRules.java  RelationRules.java
              RoleRules.java  MergeRules.java  InviteRules.java  ShieldWindow.java
              TaxMath.java  NameRules.java  MoneyMath.java  Verdicts.java
              DisbandRules.java  ChestRules.java  TravelRules.java  PrefRules.java
  config/     ConfigImage.java (+ one nested/record per section: PowerConfig, LandConfig,
              EconomyConfig, FlyConfig, ChatConfig, RelationConfig, RoleConfig, ZoneConfig,
              NotificationRouting, GuiModelConfig, PredefinedConfig, StorageConfigView)
              BakedTables.java (AM-14: zone verdict tables, material bitsets, multipliers)
  msg/        MessageKey.java  ReasonCode.java
  audit/      FactionAuditAction.java (18 ids, exact strings from research)
```

### Pinned kernel signatures (cross-boundary)

```java
// ids
public final class ChunkKeys {                    // static-only
    public static long key(int chunkX, int chunkZ);        // ((long)x<<32)|(z&0xFFFFFFFFL)
    public static int x(long key); public static int z(long key);
    public static long regionKey(long chunkKey);           // (x>>5, z>>5) packed same way
    public static long fromBlock(int blockX, int blockZ);  // >>4 then key()
}
public final class FactionHandle {                // static-only int codec (AM-6)
    public static int handle(int generation, int ordinal); // (gen<<20)|ord
    public static int ordinal(int handle); public static int generation(int handle);
    public static final int WILDERNESS = -1;                // atlas empty slot / no faction
    // ordinals 0 = SAFEZONE, 1 = WARZONE (sentinel factions, isNormal()==false), >=2 normal
}

// state — readers' surface (KernelSnapshot is the ONLY type handlers touch)
public record KernelSnapshot(KernelState state) {
    // delegating typed query methods; NO arrays escape. The load-bearing reads:
    public int  claimOwnerAt(int worldIdx, long chunkKey);          // handle or WILDERNESS
    public Faction faction(int handle);                              // null if stale/absent
    public Faction factionByName(String nameFolded);
    public int  memberOrdinal(java.util.UUID player);                // -1 if unknown
    public MemberView member(int memberOrdinal);                     // flyweight accessor
    public byte relationBetween(int handleA, int handleB);           // RelationKind byte
    public double powerAt(int memberOrdinal, int tick);              // lazy accrual (§4.5)
    public ConfigImage config();
    public long version(); public int tick();
}
public interface MemberView {   // flyweight over PlayerLedger shard (never stored)
    java.util.UUID uuid(); String nameLast(); int factionHandle(); int rankIdx();
    double powerBase(); long powerAsOfTick(); boolean powerFrozen();
    long lastActivity(); long lastDeathAt(); int deathStreak(); int prefsBits();
    byte localeIdx(); long joinedAt();
}

// relation kinds — byte constants (NOT an enum on hot paths)
public final class RelationKind {
    public static final byte MEMBER=0, ALLY=1, TRUCE=2, NEUTRAL=3, ENEMY=4;
}

// verdict engine — THE protection entry point
public final class Verdicts {                     // static, pure
    public static int decide(KernelSnapshot snap, long actorBits, int worldIdx,
                             long chunkKey, int action);   // returns Verdict codes
}
public final class Verdict {  // int codes: ALLOW=0, DENY_* > 0 (each maps to a MessageKey)
    public static final int ALLOW=0, DENY_WILDERNESS=1, DENY_SAFEZONE=2, DENY_WARZONE=3,
        DENY_ENEMY=4, DENY_NEUTRAL=5, DENY_ALLY=6, DENY_TRUCE=7, DENY_PVP_FLAG=8,
        DENY_FRIENDLY_FIRE=9, DENY_EXPLOSIONS=10, DENY_FIRE=11, DENY_INTERNAL=12;
}
public final class Action {   // int codes fed to Verdicts.decide
    public static final int BUILD=0, INTERACT=1, CONTAINER=2, PVP=3, EXPLOSION=4,
        FIRE_SPREAD=5, LIQUID=6, PISTON=7, ENTITY_GRIEF=8, TRAMPLE=9;
}
public final class ActorBits { // packs pre-resolved actor facts into one long
    public static long of(int memberOrdinal, int factionHandle, boolean bypass,
                          boolean overriding, boolean player);
    // + static extractors
}

// pipeline
public sealed interface Intent permits /* all intent records */ {}
public record IntentEnvelope(long seq, long epochMillis, int tick, long rngSeed,
                             Origin origin, Intent intent) {}
public record Origin(java.util.UUID actor /*nullable=console/system*/, byte channel) {
    public static final byte PLAYER=0, ADMIN=1, CONSOLE=2, SYSTEM=3, API=4;
}
public sealed interface Effect permits /* all effect records */ {}   // each carries long seq
public final class Reducer {
    public static Outcome apply(KernelState s, IntentEnvelope e);    // PURE
    public record Outcome(KernelState next, java.util.List<Effect> effects) {}
}
```

`ConfigImage` sections carry every key from `pvp-resources.md` §config inventory as typed
fields with reference defaults; aliased keys collapse to one canonical field at parse
(parser lives in `:core`; `ConfigImage` is pure data + `BakedTables`).

`MessageKey`: interned handle — `public record MessageKey(String key)` with a static
`of(String)` intern table; kernel effects carry `MessageKey` + `String[] args` only (no text).

---

## 3. `:platform` — package `dev.fablemc.factions.platform`

```
platform/src/main/java/dev/fablemc/factions/platform/
  sched/    Scheduling.java  BukkitScheduling.java  SchedulingFactory.java  TaskHandle.java
  probe/    Capabilities.java  PlatformProfile.java  Probes.java (classPresent/methodPresent)
  resolve/  Players.java  LegacyMaterials.java  Views.java  Feedback.java  Hands.java
            Nametags.java  Constants.java  ItemCodec.java  Worlds.java (AM-15 registry)
  text/     TextPort.java
  gui/      MenuModel.java  MenuHolder.java  MenuItemModel.java
  life/     Scope.java  ListenerGate.java
  actor/    DamageAttribution.java
```

### Pinned platform signatures

```java
public interface Scheduling {   // Mental verbatim (mental-seam.md §4) — DO NOT ALTER
    void runGlobal(Runnable task);
    void runAt(org.bukkit.Location location, Runnable task);
    void runOn(org.bukkit.entity.Entity entity, Runnable task, Runnable retired);
    void runOnLater(org.bukkit.entity.Entity entity, long delayTicks, Runnable task, Runnable retired);
    boolean isOwnedByCurrentRegion(org.bukkit.entity.Entity entity);
    void runAsync(Runnable task);
    TaskHandle repeatGlobal(long initialTicks, long periodTicks, Runnable task);
    TaskHandle repeatOn(org.bukkit.entity.Entity entity, long initialTicks, long periodTicks,
                        Runnable task, Runnable retired);
    TaskHandle repeatAsync(java.time.Duration initial, java.time.Duration period, Runnable task);
    String describe();
    default void ensureOn(org.bukkit.entity.Entity e, Runnable task, Runnable retired) { ... }
}
public interface TaskHandle { void cancel(); boolean cancelled(); }

public record Capabilities(boolean folia, boolean foliaSchedulers, boolean bungeeChat,
        boolean onlineCollection, boolean flattened, boolean asyncTeleport,
        boolean asyncChunkGet, boolean modernChatEvent, boolean blockExplode,
        boolean entityPickup, boolean armorStands, boolean raids, boolean mountBukkit,
        boolean mountSpigot, boolean toggleGlide, boolean lingering, boolean pdc,
        boolean brigadier, boolean serializeAsBytes, boolean hexColors, boolean minHeight,
        boolean hidePlayerPlugin, boolean clickedInventory) {
    public static Capabilities detect();
    public String describe();
}
// Selector rule (AM-12): SchedulingFactory keys on caps.folia() ONLY.

public final class TextPort {    // the ONLY text→Bukkit boundary (AM-1)
    public static String legacy(Component c);                    // §-encoded, hex-downsampled if !hexColors
    public static void send(org.bukkit.command.CommandSender to, Component msg);   // bungee spigot() if bungeeChat, else String
    public static void title(org.bukkit.entity.Player p, Component title, Component sub,
                             int in, int stay, int out);          // Feedback chain, chat fallback on 1.7
    public static void actionBar(org.bukkit.entity.Player p, Component msg);
    public static org.bukkit.inventory.Inventory createInventory(
            org.bukkit.inventory.InventoryHolder h, int size, Component title);
}
// `Component` above = the SHADED relocated Adventure type. Never appears in :kernel.

public final class Players {
    public static Iterable<org.bukkit.entity.Player> online();   // MethodHandle dual-descriptor
    public static org.bukkit.entity.Player get(java.util.UUID id);
}
public final class Scope implements AutoCloseable {   // Mental pattern
    public void listen(org.bukkit.event.Listener l);             // registers + tracks
    public void task(TaskHandle h);
    public void closeable(AutoCloseable c);
    @Override public void close();                                // reverse order, isolated
}
public final class ListenerGate {
    public static void register(Scope scope, boolean capability, String fqnOrLocal,
                                java.util.function.Supplier<org.bukkit.event.Listener> maker);
    // supplier invoked only when capability true; registration recorded in the scope
}
@java.lang.annotation.Retention(RetentionPolicy.CLASS)
public @interface ProbeGated { String capability(); }             // AM-13 cross-check
```

`:compat-folia` = `dev.fablemc.factions.compat.folia.FoliaScheduling` (constructor
`(org.bukkit.plugin.Plugin)`), Mental quirk fixes included.
`:compat-modern` = `dev.fablemc.factions.compat.modern.{ModernItemCodec, BrigadierInstaller,
AsyncChunks}` — loaded by FQN string only.

---

## 4. `:core` — package `dev.fablemc.factions.core`

```
core/src/main/java/dev/fablemc/factions/core/
  boot/       FableFactionsPlugin.java  BootReport.java  FeatureReconciler.java
  pipeline/   IntentBus.java  SubmitResult.java  WriterThread.java  SnapshotHub.java
              EffectSink.java  EffectFanout.java
  journal/    EffectJournal.java  JournalCodec.java  JournalReplay.java
  storage/    StorageProjector.java  Schema.java  SchemaMigrator.java  SqlDialect.java
              H2Dialect.java  MySqlDialect.java  BaselineLoader.java  AdvisoryLock.java
              PvpIndexImporter.java  Blobs.java
  text/       Messages.java            (render MessageKey+args → Component → TextPort)
  messages/   MessageCatalog.java  LocaleTables.java
  config/     ConfigParser.java  ConfigFiles.java  OverlayStore.java
  listen/     BuildProtectionListener  CombatProtectionListener  ExplosionListener
              BlockExplodeListener  GriefListener  InteractProtectionListener
              MoveListener  ChatListener  SessionListener  DeathListener
              AllyUnlockListener  ArmorStandListener  RaidListener  MountListenerBukkit
              MountListenerSpigot  GlideListener  LingeringListener  ListenerLoadout.java
  command/    CommandNode.java  CommandContext.java  CommandGuards.java  ArgParsers.java
              FCommandExecutor.java  FaCommandExecutor.java  Completions.java
    member/   (create disband rename desc motd leader leave kick promote demote join
               invite* show list top help language notify …)
    claim/    (claim* unclaim* map autoclaim …)  ShapeCollectors.java
    travel/   (home sethome unsethome warp* fly)
    bank/     (balance deposit withdraw transfer history)
    power/    (power powerhistory)
    relation/ (relation* )
    role/     (role*)
    flagcmd/  (flag*)
    chestcmd/ (chest*)
    admin/    (bypass claim unclaim disband reload safezone warzone shield power* flag
               audit import …)
    merge/    (merge send/accept)
    predefined/ (predefined*)
  session/    SessionRegistry.java  PlayerSession.java  TeleportSaga.java  CombatTags.java
  chest/      ChestSessions.java
  gui/        MenuManager.java  MenuSession.java  GuiListener.java
  power/      PowerTicker.java     (repeatAsync → PowerTick intents)
  economy/    VaultAdapter.java  EscrowExecutor.java  TaxScheduler.java
  integration/ IntegrationsBootstrap.java + vault/ worldguard/ placeholderapi/ essentials/
               dynmap/ lwc/ discordsrv/ ezcountdown/ teamsapi/  (façade+Noop+factory each)
  metrics/    MetricsInit.java   update/ UpdateChecker.java
core/src/main/resources/
  plugin.yml  config.yml  database.yml  gui.yml  roles.yml  pre-defined.yml
  notifications.yml  messages/messages_{en,de,es,fr,ja,pt-BR,ru,zh}.yml
```

### Pinned core signatures

```java
public final class IntentBus {
    public SubmitResult submit(Intent i, Origin o);   // player lane, bounded, may reject
    public void submitSystem(Intent i);               // unbounded system lane
}
public enum SubmitResult { ACCEPTED, REJECTED_BUSY, REJECTED_SHUTDOWN }
public final class SnapshotHub { public KernelSnapshot current(); }
public interface EffectSink { void accept(java.util.List<Effect> batch, long lastSeq); }
// WriterThread: drains ≤1024, per-intent try/catch (AM-9), publishes snapshot, then fans
// out ONE immutable batch to: journal (same thread) → SPSC queues for storage + subscribers.

public final class Messages {   // the ONLY consumer of MessageCatalog at runtime
    public void to(org.bukkit.command.CommandSender s, MessageKey key, String... args);
    public void toPlayer(java.util.UUID id, MessageKey key, String... args); // via Scheduling.runOn
    public Component render(byte localeIdx, MessageKey key, String... args);
}

// Command framework contract (parity with reference framework, pvp-commands-core.md):
public abstract class CommandNode {
    protected CommandNode(String name, String... aliases);
    // permission(), requiredArgs, optionalArgs, requiresPlayer, children — builder style
    public final void execute(CommandContext ctx);   // pipeline: perm → player → child →
                                                      // arg count → perform(ctx)
    protected abstract void perform(CommandContext ctx);
}
public final class CommandContext {
    public String arg(int i);                 // never throws; null when absent
    public java.util.Map<String,String> options();   // parsed --key=value long options
    public org.bukkit.command.CommandSender sender();
    public org.bukkit.entity.Player player();        // guarded non-null when requiresPlayer
    public KernelSnapshot snap();                    // taken ONCE at dispatch
}
```

Thread rules (enforced by review + ArchUnit where possible): listener bodies and command
`perform` run on the calling region/main thread — snapshot reads + `IntentBus.submit` +
`Messages` only; no JDBC, no journal, no kernel-state construction. `PlayerSession` fields
are owned by the player's region thread (AM-14). Storage package runs only on
`fable-storage`. `Component` (shaded) appears only in `core.text`/`core.messages`/`platform.text`.

---

## 5. `:api` — package `dev.fablemc.factions.api`

```java
public interface FableFactionsApi {
    FactionsView view();                                  // wraps current snapshot
    java.util.concurrent.CompletionStage<RequestResult> request(FactionRequest r);
    AutoCloseable subscribe(FactionsEffectListener l);
}
```
Bukkit events (fired per proposal-C §10.2 timing rules):
`FactionCreateEvent, FactionDisbandEvent, FactionJoinEvent, FactionLeaveEvent,
FactionChunkClaimEvent, FactionChunkUnclaimEvent, FactionBankTransactionEvent`.
All extend `org.bukkit.event.Event`; cancellable ones implement `Cancellable`; **no
post-1.13.2 Bukkit type and no kernel type in any public signature** (primitives, String,
UUID, org.bukkit.Location, enums defined in `:api`).

---

## 6. Conventions that keep 8 agents compatible

1. **Message keys**: exact reference key set (`pvp-resources.md` inventory). New keys (D-9)
   use the same section naming (`general.*`, `commands.<cmd>.*`, `power.*`, `territory.*`,
   `bank.*`, `relations.*`, `roles.*`, `chest.*`, `warp.*`, `merge.*`, `admin.*`,
   `predefined.*`, `gui.*`, `update.*`, `custom.*`).
2. **Permissions**: reference tree (`factions.cmd.<name>[.<sub>]`, `factions.admin`,
   `factions.bypass`, defaults per `pvp-resources.md` §plugin.yml).
3. **Config keys**: reference tree exactly; canonical-alias rule per AM-14/proposal-C §9.1.
4. **Errors**: kernel rejections are `ReasonCode`s mapping 1:1 to message keys; command
   layer never invents text.
5. **Time**: kernel sees time ONLY via `IntentEnvelope` (epochMillis/tick). Platform/core
   use `System.currentTimeMillis` freely.
6. **Nullability**: `@org.jetbrains.annotations.Nullable` on every nullable public return;
   absent = non-null.
7. **Logging**: `plugin.getLogger()` only; boot report single-line-per-subsystem (B10);
   never log-and-swallow — either handle or rethrow to the AM-9 boundary.
8. **No new deps** beyond the version catalog without updating the scaffold work order:
   adventure (api, minimessage, legacy serializer), HikariCP 4.0.3, h2 1.4.200,
   mysql-connector-j 8.0.33, bstats 3.2.1, annotations; test: junit5, jqwik, archunit,
   testcontainers (mysql), jmh (kept out of `check`-critical path where slow).
9. **File ownership is exclusive.** Your work order lists your files; do not create or edit
   outside them. Shared-looking needs (a helper another agent owns) → use the contract
   surface above; if impossible, note it in your final report instead of editing.

---

## 7. Addenda (learned during implementation — NORMATIVE)

1. **No `byte` or `short` record components, ever.** jvmdg 1.3.6's downgrade of the record
   toString bootstrap emits `StringBuilder.append(B)`/`(S)` descriptors that exist in NO JDK
   → `NoSuchMethodError` on the Java-8 base tier (caught by verifyJdk8Api on W1 code).
   Use `int` components; `byte[]`/`short[]` fields and byte-returning methods are fine.
2. **Intent/Effect records are NESTED** in their sealed interfaces: reference as
   `Intent.CreateFaction`, `Effect.FactionCreated`. Every Effect record's leading fields are
   `(long seq, Origin origin, …)`.
3. `RelationKind` lives in `dev.fablemc.factions.kernel.state` (not msg/). Relation edge
   arrays (`relOut`/`relEff`) are keyed by the other faction's **ordinal** (not handle);
   AM-6 disband scrubbing removes edges before ordinal reuse.
4. `Origin.channel` and all pinned "byte code" record fields are `int` (see addendum 1);
   the named constants keep their values.
5. `Verdict`, `Action`, `ActorBits`, `Verdicts` live in `kernel/rules/` (Wave 2a creates
   them). A placeholder `reduce/Reducer.java` + `Outcome` exists so `:core` compiles —
   Wave 2a REPLACES it (documented ownership handoff).
6. The scaffold's `core/build.gradle.kts` gate wiring, relocations, and ignore lists are
   final — do not touch them without a work order saying so.
```
