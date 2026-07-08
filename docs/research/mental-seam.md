# Mental (StrikeSync) platform seam — reusable multi-version Bukkit patterns

Extraction of the architecture patterns that let the **Mental** combat plugin
(`~/Documents/StrikeSync`) ship **one universal jar** across Paper 1.9.4 → 26.x
with no server flags and no version-specific builds. Written to be mined for a
**factions plugin** (claims / chat / GUI / teleports). Each section flags what
transfers directly and what does not.

Source of truth read for this doc: `platform/src`, `core/src`, `kernel/src`, the
`paper-cross-version` and `mental-conventions` skills, and
`docs/superpowers/plans/2026-07-03-mental-full-range.md`.

---

## 0. The module shape (the frame every pattern hangs on)

Four Gradle modules with a strict, build-*enforced* dependency direction:

| Module | Sees Bukkit? | Role |
|--------|--------------|------|
| `kernel` | **No** — not even `compileOnly` | Pure-JDK domain logic: math, state machines, immutable records. Version-blind by construction. |
| `platform` | Yes | The **only** Bukkit-facing seam: `Scheduling`, `Capabilities`, NMS/API resolvers, `ServerEnvironment`, boot report inputs. Compiles against the **floor API (1.17.1)**. |
| `core` | Yes | The plugin. Wires `platform` + `kernel`, owns boot ordering, the config snapshot, the feature reconciler, the boot report. Shades PacketEvents + bStats + Adventure; **folds in `compat-folia` by name**. |
| `compat-folia` | Yes (newer API) | Compiled against a **newer** Paper/Folia API than the floor. Never loaded unless the Folia capability is detected. |

**The compilation model** (from `paper-cross-version`):
- `core` compiles against the **floor API = 1.17.1** so the common path is
  binary-safe everywhere. Anything newer lives in a `compat-*` module loaded
  behind a runtime capability check.
- Class-file compile target is Java 17; the *shipped* jar is a **Multi-Release
  mega-jar** downgraded to class v52 (Java 8) as the base tree, with the original
  v61 kept under `META-INF/versions/17`. It classloads on any JVM from Java 8 up.
- **Runtime floor is Paper 1.9.4**, *below* the 1.17.1 compile floor — so any
  sub-floor API absence surfaces at **runtime** (`NoSuchMethodError`,
  `NoClassDefFoundError`), decided **once at boot** by resolvers.

Kernel purity is a **build-time assertion**, not a convention (`kernel/build.gradle.kts`):
```kotlin
configurations.all {
    resolutionStrategy.eachDependency {
        require(!requested.group.startsWith("io.papermc")) { "kernel must stay Bukkit-free" }
        require(!requested.group.startsWith("com.github.retrooper")) { "kernel must stay PacketEvents-free" }
    }
}
```
plus a runtime test (`KernelClasspathTest`) asserting `Class.forName("org.bukkit.Bukkit")` **throws** on the kernel test classpath.

**Transfers directly to factions:** the whole module layout. A factions plugin
gets a `kernel` (claim geometry, relation graph, chunk-key math, cost/power
formulas, chat-format templating — all pure JDK, unit-testable with no server),
a `platform` seam (scheduling, capabilities, GUI/text resolvers), a `core`
(commands, listeners, storage wiring), and `compat-folia`. The build-enforced
"kernel never imports Bukkit" edge is the single highest-leverage discipline to
copy — it is what keeps claim/relation logic testable and version-blind.

---

## 1. Boot-time capability probing (not scattered version conditionals)

### The rule
> Feature-detect at runtime; **never parse version strings for behavior** when a
> capability probe works. New version-dependent behavior is decided **once at
> module enable**, not per packet/event.

A class/method/field either exists on this server or it does not. Version numbers
are reserved for the boot report and protocol decisions only.

### How probes are structured — two tiers

**Tier 1: `Capabilities` — a boot-computed record of coarse booleans** (`platform/Capabilities.java`):
```java
public record Capabilities(
        boolean folia, boolean modernSchedulers, boolean brigadierCommands,
        boolean registryAttributes, boolean knockbackEvent, boolean currentTick) {

    public static Capabilities detect() {
        boolean folia = classPresent("io.papermc.paper.threadedregions.RegionizedServer");
        boolean modernSchedulers = folia
                || classPresent("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
        boolean brigadier = classPresent("io.papermc.paper.command.brigadier.Commands");
        boolean registryAttributes = !Attribute.class.isEnum();   // 1.21.3 enum→registry flip
        boolean knockbackEvent = classPresent("io.papermc.paper.event.entity.EntityKnockbackEvent");
        boolean currentTick = methodPresent(org.bukkit.Bukkit.class, "getCurrentTick");
        return new Capabilities(folia, modernSchedulers, brigadier, registryAttributes, knockbackEvent, currentTick);
    }
    // classPresent = Class.forName in try/catch; methodPresent = getMethod in try/catch
    public String describe() { /* "folia=… modernSchedulers=… …" for the boot log */ }
}
```
Note the *techniques*: `Class.forName` (class presence), `getMethod` (method
presence), and behavioral probes like `!Attribute.class.isEnum()` (detects the
1.21.3 enum→registry break by asking the type itself, never by version compare).

**Tier 2: `PlatformProfile` — one manifest of typed resolutions** (`core/.../platform/PlatformProfile.java`).
This is the richer pattern. Every version-gated probe Mental performs
(attribute/enchantment handles, protocol flags, item-component adapters) is folded
into **one immutable value** built once at boot and read on the hot path as plain
field access. Entries are one of two typed kinds:

- **`Required.owned(name, Feature, resolver)`** — the handle is expected on *every*
  supported version. A miss **disables its owning feature** (one loud log line) or,
  if engine-critical, **fails boot**.
- **`OptionalSince.resolve(name, sinceVersion, fallback, fallbackNote, resolver)`** —
  the handle is *expected absent* below a declared version. A miss is a **typed,
  quiet fallback declared right at the entry** — never a mapping break, never a
  bare `@Nullable` buried at a call site.

```java
entries.add(Required.owned("attribute:attack_damage", Feature.HIT_REGISTRATION, Attributes::attackDamage));
entries.add(OptionalSince.resolve("attribute:entity_interaction_range", "1.20.5", null,
        "the classic 3.0 attack reach", Attributes::entityInteractionRange));
entries.add(OptionalSince.resolve("capability:sweep_cause", "1.11.0", Boolean.FALSE,
        "sweep arrives as plain ENTITY_ATTACK — suppression is a documented no-op",
        () -> SweepCauses.present() ? Boolean.TRUE : null));
```

`resolveDisabled(entries, log)` scans the resolved manifest: a `Required` miss
disables its owner or throws; the resulting `Set<Feature> disabledFeatures` is
handed to the reconciler so those features never converge. `OptionalSince#orFallback()`
returns the resolved value or the declared fallback.

### How probes are registered & reported
- Everything is resolved **once** in `onEnable` before any feature registers:
  `Capabilities.detect()`, `ServerEnvironment.parse(...)`, then
  `PlatformProfile.resolve(env, caps, log)`.
- The profile emits **one boot-report line** summarising `present/total` handles
  plus the salient adapter tiers and the disabled-feature set (see §8).
- The `PlatformProfile` is stored on the plugin and read for the rest of its life;
  hot paths never re-probe.

**Transfers directly to factions:** the entire two-tier pattern. Faction analogues:
- `Capabilities`: `foliaPresent`, `adventureNative` (Paper `sendMessage(Component)`,
  1.16.5+), `brigadierCommands` (for tab-complete richness), `pdcPresent`
  (PersistentDataContainer, 1.14+, for storing claim metadata on chunks/blocks),
  `hexColors` (1.16 chat colors).
- `PlatformProfile`: a manifest of the GUI/chat/teleport handles a faction plugin
  needs (e.g. `OptionalSince("capability:async_teleport", "1.14", ...)` for Paper's
  `Entity#teleportAsync`; `OptionalSince("component:custom_model_data", ...)` for GUI icons).

**Does NOT transfer:** the packet/protocol probes (`knockbackEvent`,
`modernHurtProtocol`, `hurt_animation_bundle`) — those are combat/netty concerns a
factions plugin has no equivalent of.

---

## 2. The resolver pattern for absent APIs (era-correct fallbacks)

Each resolver is a small final class in `platform/` that resolves a
class/method/field/enum-constant **once at class load** and caches the handle;
lookups after boot are plain static reads. The uniform shape is
**"try modern, fall back to legacy, degrade loud"**.

### 2a. Constant renames in both directions — `Attributes`
The `Attribute` type changed from an enum with `GENERIC_*` constants (≤1.21.1) to a
registry-backed interface with unprefixed constants (1.21.3) — a binary break in
*both* directions. Resolve by name, modern spelling first:
```java
private static Attribute resolve(String modernName, String legacyName) {
    Attribute modern = staticField(modernName);          // reflective Attribute.class.getField
    return modern != null ? modern : staticField(legacyName);
}
private static final Attribute ATTACK_DAMAGE = resolve("ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE");
// ...
public static double valueOr(LivingEntity e, Attribute attr, double fallback) {
    if (attr == null) return fallback;
    AttributeInstance i = e.getAttribute(attr);
    return i != null ? i.getValue() : fallback;          // era-default when the attribute is absent
}
```
Same pattern in `platform/Enchantments` for the 1.20.5 enchantment renames.
Absent handles are `@Nullable` and callers pass an **era-correct numeric fallback**
(`entityInteractionRange()` absent ⇒ classic `3.0` reach; `gravity()` absent ⇒
vanilla `0.08`).

### 2b. Sticky-`NoSuchFieldError` enum constants — `SweepCauses`
A direct `getstatic` of a sub-floor enum constant (`DamageCause.ENTITY_SWEEP_ATTACK`,
lands 1.11) throws a **sticky** `NoSuchFieldError` that rethrows on *every* subsequent
execution. Resolve once via `Enum.valueOf` in try/catch, cache, and where absent
**do not register the listener at all**:
```java
private static final DamageCause SWEEP = resolve();  // valueOf in try/catch, null below 1.11
public static boolean present() { return SWEEP != null; }
private static DamageCause resolve() {
    try { return DamageCause.valueOf("ENTITY_SWEEP_ATTACK"); }
    catch (IllegalArgumentException absent) { return null; }
}
```

### 2c. Method-presence probes — `Cooldowns`, `Recipes`
`HumanEntity#setCooldown(Material,int)` lands 1.11.2; the whole feature that clears
it is a *documented, loud no-op* below that (the mechanic it fixes is era-native
there). `Recipes` shows **three distinct floors probed independently** —
`NamespacedKey` class (1.12), the keyed `ShapedRecipe` ctor (1.12), and the keyed
get/remove *lifecycle* (1.16.5) — each by name, never a class literal:
```java
private static boolean methodPresent(Class<?> owner, String name, String paramClassName) {
    try { owner.getMethod(name, Class.forName(paramClassName)); return true; }
    catch (ClassNotFoundException | NoSuchMethodException | LinkageError absent) { return false; }
}
```

### 2d. Reflect-by-name to dodge missing descriptor types — `PlatformProfile.probeDamageable`
The `Damageable` meta interface is absent below 1.13, so even a `Damageable.class`
*literal* is a `NoClassDefFoundError` at an always-on boot probe. Resolve the owner
class **by name**, catch `LinkageError` too:
```java
Class<?> damageable = Class.forName("org.bukkit.inventory.meta.Damageable");
return damageable.getMethod(name);   // ClassNotFound | NoSuchMethod | LinkageError → null
```

### 2e. Two structural hazards these resolvers exist to prevent (from `paper-cross-version`)
- **The listener-descriptor hazard.** No sub-floor Bukkit type may appear in *any*
  method/field **descriptor** of a class handed to `registerEvents` — Bukkit reflects
  over every declared method, and a descriptor whose type is absent throws
  `NoClassDefFoundError`, which Bukkit swallows into one SEVERE line and registers
  **zero** handlers for the whole class. Remedy: hoist every sub-floor-typed symbol
  into a small **non-Listener helper** instantiated only behind the resolver
  (constant-pool entries resolve lazily, so merely *loading* the helper links
  nothing). Body-only references (guarded calls, caught `X.class` literals) are safe;
  descriptors are not.
- **The single translation seam.** `LegacyMaterialNames.modernize` is the ONE place
  pre-flattening material names are mapped (`WOOD_*→WOODEN_*`, `*_SPADE→*_SHOVEL`),
  guarded by a single boot flattening check (identity on 1.13+). The kernel vocabulary
  stays modern; never scatter name maps.

**Transfers directly to factions:**
- 2a/2c/2d wholesale. Faction analogues: `PersistentDataContainer` (1.14) for storing
  claim owner UUIDs on chunks — probe by method presence, fall back to a YAML/DB side
  table below 1.14. `Player#getLocale` shape changes; async chat event
  (`AsyncChatEvent` vs `AsyncPlayerChatEvent`); `Chunk#getPersistentDataContainer`.
- 2b/2e (the descriptor/sticky-constant hazards) apply **any** time a factions listener
  references a version-gated Bukkit enum/type — e.g. a newer `Material`, a newer
  `EntityType`, or a `TeleportCause` constant. The "hoist sub-floor-typed symbols into
  a non-Listener helper behind a resolver" rule is directly load-bearing.

**Does NOT transfer:** the versioned-NMS resolution below 1.17 (`net.minecraft.server.<rev>`
FakePlayer / tooltip-strip) — that is combat/packet archaeology. A factions plugin
should stay on the Bukkit/Paper API surface and never need NMS.

---

## 3. TextPort — keeping Adventure Components off Bukkit boundaries

**The problem:** Mental builds every user-facing string as an Adventure `Component`
internally, but the Paper-native `Component`-taking sinks (`ItemMeta#displayName`,
`#lore`, `Bukkit.createInventory(holder,int,Component)`,
`CommandSender#sendMessage(Component)`) only exist from Paper **1.16.5**; below that
they — and `net.kyori` itself — are absent.

**The seam** (`core/.../text/TextPort.java`): Adventure is **shaded into the jar,
relocated** to `me.vexmc.mental.lib.adventure`, so `LegacyComponentSerializer` is
present and self-contained on every version. Every Component is serialized to a
`§`-encoded **legacy string** here and handed to the **universal String overloads**
(`setDisplayName`, `setLore`, `createInventory(holder,int,String)`,
`sendMessage(String)`) which have existed since 1.9:
```java
private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();
public static String legacy(Component c) { return SECTION.serialize(c); }

@SuppressWarnings("deprecation")
public static void displayName(ItemMeta meta, Component name) { meta.setDisplayName(legacy(name)); }
@SuppressWarnings("deprecation")
public static void send(CommandSender to, Component msg) { to.sendMessage(legacy(msg)); }
public static Inventory createInventory(InventoryHolder h, int size, Component title) {
    return Bukkit.createInventory(h, size, legacy(title));
}
```

**The invariant:** *nothing else in core/platform passes a Component across a Bukkit
boundary* — pinned by an **architecture grep** (a test/CI check). Consequences:
- On modern servers the relocated Adventure copy is **inert** — it never touches
  Paper's native Adventure, so no dual-Adventure conflicts.
- On legacy servers the sinks never reference a class that isn't there (they take
  `String`), and the relocated serializer is always present.
- The String overloads are `@Deprecated` on modern Paper but are the **only** ones
  present across the whole range — that is the entire point of the seam.

**Transfers directly to factions — this is one of the highest-value patterns.** A
factions plugin is *saturated* with user-facing text: claim messages, `/f` command
output, faction chat, GUI titles/lore, scoreboard/BossBar, tab-list. Build all of it
as Components internally, funnel every emission through a `TextPort` that downgrades
to `§`-legacy strings for the universal sinks, shade+relocate Adventure, and pin the
"no Component crosses a boundary except through TextPort" grep. This buys native-feeling
rich text (hex colors via the serializer where supported) on modern servers while
still running on 1.9.4. Add faction-specific sinks: `title()`/`actionBar()` (both have
legacy `Player#sendTitle`/`spigot().sendMessage` paths), scoreboard team prefixes.

---

## 4. The scheduling seam (Folia / Bukkit / main-thread)

### The interface — `platform/Scheduling.java`
One interface every module touches; two implementations (`BukkitScheduling`,
`compat-folia/FoliaScheduling`). Module code never knows the platform and is
**region-correct by construction**.

```java
public interface Scheduling {
    void runGlobal(Runnable task);                              // global tick / main thread
    void runAt(Location location, Runnable task);              // owning region of a location
    void runOn(Entity entity, Runnable task, Runnable retired);// thread that owns the entity
    void runOnLater(Entity entity, long delayTicks, Runnable task, Runnable retired);
    boolean isOwnedByCurrentRegion(Entity entity);            // gate for live-entity reads
    void runAsync(Runnable task);
    TaskHandle repeatGlobal(long initialTicks, long periodTicks, Runnable task);
    TaskHandle repeatOn(Entity entity, long initialTicks, long periodTicks, Runnable task, Runnable retired);
    TaskHandle repeatAsync(Duration initial, Duration period, Runnable task);
    String describe();

    // default: run inline if we already own the thread, else defer like runOn
    default void ensureOn(Entity entity, Runnable task, Runnable retired) {
        if (entity.isValid() && isOwnedByCurrentRegion(entity)) task.run();
        else runOn(entity, task, retired);
    }
}
```

### How work is routed
- **Per-entity work → `runOn`/`repeatOn`/`runOnLater`** — follows the entity to its
  owning thread. On Paper that is the main thread; on Folia the entity's region thread.
- **Location work → `runAt`** — the region owning that location.
- **Global work → `runGlobal`/`repeatGlobal`** — the global tick (Folia's global region
  scheduler) or the single main thread (Paper).
- **`ensureOn`** removes the one-tick hop when the caller already owns the thread
  (a damage handler on the victim's own region) — runs inline, else defers.
- **`isOwnedByCurrentRegion`** gates live-entity reads: always `true` on Paper (one
  region owns everything); the real ownership check on Folia (prevents `ensureTickThread`
  throws at region boundaries).

### The retired-callback contract (the hard part of unifying the two backends)
Entity-scoped methods take a `retired` callback that fires when the entity is gone
before the work runs. The two backends diverge on *when/where* it fires, so the
contract is the **common denominator**, pinned by a Scheduling TCK:
- **Either thread** — may run on owning/main thread *or* inline on the caller. Callers
  must assume **no thread affinity** for it.
- **Exactly once** — for a submission, exactly one of `task`/`retired` runs, once. A
  repeating task that retires cancels itself and fires `retired` a single time.
- **May be immediate** — may run before the submitting call returns (Folia inline) or
  on a later tick (Bukkit).

The implementations paper over a real Folia quirk: `entity.getScheduler().run(...)`
returns `null` **without** invoking the retired hook if the entity was already retired
at schedule time, so `FoliaScheduling` fires `retired` itself in that branch to match
`BukkitScheduling`'s contract:
```java
// FoliaScheduling.runOn
ScheduledTask scheduled = entity.getScheduler().run(plugin, ignored -> task.run(), retired);
if (scheduled == null) retired.run();   // already-retired: Folia skips the hook; we honor the contract
```
```java
// BukkitScheduling.runOn — one region: everything collapses to the main thread
Bukkit.getScheduler().runTask(plugin, () -> {
    if (entity.isValid()) task.run(); else retired.run();
});
```
Folia clamps sub-1-tick delays to 1 (`clampTicks`); `describe()` returns `"folia"`/`"bukkit"`
for the boot log and bStats.

`TaskHandle` is a tiny `{ cancel(); cancelled(); }` abstraction over `BukkitTask` /
Folia `ScheduledTask` (plus a `RetiredHandle` no-op singleton for the already-retired
repeat case).

**Transfers directly to factions — essential.** Folia support is impossible without
exactly this seam. Faction routing:
- Claim checks / power regen / faction-wide tasks → `repeatGlobal`.
- **Teleports** → run on the *player's* owning region (`runOn`/`runOnLater`), and on
  Paper prefer the `teleportAsync` capability. Warmup countdown tasks are `repeatOn` the
  player with a `retired` that cancels on logout.
- Anything reading/writing a specific chunk or block (claim particle borders, auto-claim)
  → `runAt(location, ...)`.
- Storage flush / DB writes → `runAsync` / `repeatAsync`.

The `retired` contract matters just as much: a warmup teleport whose player logs out
must fire cleanup exactly once, on no assumed thread.

---

## 5. Folding `compat-folia` into the jar by name behind a capability check

The Folia implementation is compiled against a **newer** API than the floor, so it must
never be *loaded* on servers lacking the region-scheduler types. It is referenced **by
name only**:

`SchedulingFactory.create` picks the backend:
```java
private static final String FOLIA_IMPL = "me.vexmc.mental.compat.folia.FoliaScheduling";
public static Scheduling create(Plugin plugin, Capabilities caps) {
    if (!caps.folia()) return new BukkitScheduling(plugin);
    try {
        return (Scheduling) Class.forName(FOLIA_IMPL)
                .getDeclaredConstructor(Plugin.class).newInstance(plugin);
    } catch (ReflectiveOperationException failure) {
        throw new IllegalStateException("Folia detected but the Folia scheduling backend failed to load", failure);
    }
}
```
No hard dependency: `compat-folia` `compileOnly`s the platform interface and the Folia
API; nothing in `core` imports `FoliaScheduling` — only the string FQN. On regular Paper
the class is **never touched**, so its newer-API references never link.

**How it lands in the one jar** (`core/build.gradle.kts`):
- `settings.gradle.kts` includes `:compat-folia` as a module (dir `compat/folia`).
- `shadowJar` `dependsOn(":compat-folia:classes")` and `from(project(":compat-folia").sourceSets.main.get().output)`
  — the compiled Folia classes are **folded into core's shadow output**, not published as
  a separate artifact.
- The downgrade step must resolve Folia's supertypes, so its classpath is the **union**
  of core's compile classpath and `compat-folia`'s compile classpath (the Folia scheduler
  API is absent from the 1.17.1 floor):
  ```kotlin
  classpath.from(project(":compat-folia").sourceSets["main"].compileClasspath)
  ```

**Transfers directly to factions.** Any capability that needs a newer-than-floor API —
Folia scheduling above all, but also e.g. a Brigadier command tree, or a modern GUI API —
goes in its own `compat-*` module, is folded into the jar via `shadowJar { from(...) }`,
and is instantiated by FQN string behind a `Capabilities` check. The **rule** is:
newer-API code is *reachable* only through a reflective FQN gated on a boot probe, so it
never links on servers that lack it.

---

## 6. Single-writer / immutable-snapshot state discipline

Mental runs three **single-writer domains** and communicates between them **only** with
immutable values (from `mental-conventions`). The combat specifics don't transfer, but
the *discipline* does:

- **D1 connection** (netty read thread): reads only its own inbound packets, the
  `TickClock`, and *published* immutable `PlayerView`s — never a live entity, never
  `getWorld()`, never another player's state.
- **D2 session** (region thread): one `CombatSession` per player owns its mutable state,
  mutated only by its owning thread, fed a per-player inbox of immutable signals. A 1-tick
  task **publishes** one `PlayerView` via `AtomicReference.set` = end-of-previous-tick
  state.
- **D3 global**: config swap, reconciler, `TickClock`, `entityId→UUID` index.

Core rules:
- **Cross-domain communication is exclusively immutable values** (records).
- **All entity work goes through `Scheduling.runOn/repeatOn/runGlobal`** — region-correct
  on Folia, main-thread on Paper, identical code.
- **Publish, don't share**: state a reader on another thread needs is snapshotted into an
  immutable record and swapped by `AtomicReference`.
- Domain quantities are **value types** (`TickStamp`, `HitId`) not raw ints; **records
  over classes for data**.

**Transfers to factions, adapted.** A factions plugin is not netty-heavy, but the
single-writer + immutable-snapshot idea maps cleanly onto **Folia region threading** and
**async storage**:
- Own each faction's mutable in-memory state on **one** thread (e.g. the main/global
  region), and hand async storage workers **immutable snapshots** to persist — never let
  a DB thread read live faction objects.
- Publish a `ClaimsView` / `RelationView` immutable snapshot by `AtomicReference` that
  read-heavy paths (movement listeners checking "who owns this chunk") consult without
  locking. This is exactly the `PlayerView` publish pattern, and it is what makes claim
  lookups safe on Folia where a `PlayerMoveEvent` fires on the player's region thread while
  another region edits a faction.
- Use immutable `record`s for claim keys (`record ChunkKey(String world, int x, int z)`),
  relations, and cross-thread messages.

---

## 7. Config Snapshot atomic-swap pattern

**The invariant** (`mental-conventions`): *one immutable `Snapshot` swapped by reference —
no code path may read a torn mix mid-operation.*

### The Snapshot (`core/.../config/Snapshot.java`)
An immutable object built whole by a parser-owned `Builder`; **no positional
mega-constructor, no setter surface**. Reads are **typed**:
```java
public final class Snapshot {
    private final Map<Feature, Boolean> enabled;           // module toggles
    private final Map<SettingsKey<?>, Object> settings;    // per-feature settings by key identity
    private final Map<String, KnockbackProfile> profiles;  // ...
    // constructed via private Snapshot(Builder) — defensive copies (EnumMap, Map.copyOf, IdentityHashMap)

    public boolean enabled(Feature f) { … feature.defaultEnabled() when absent … }
    public <S> S settings(SettingsKey<S> key) { … typed, never null … }
}
```
- **Settings are flat immutable records** each with a `DEFAULTS` constant, parsed via a
  **warn-and-fallback** `ConfigReader` (a bad/absent value logs an issue and uses the
  default; parsing an *empty* section must equal the era-exact baseline — pinned by
  parse-equality unit tests).
- Per-feature settings are keyed by **`SettingsKey` identity** (`IdentityHashMap`), so a
  feature reads its own settings by a typed key with no casting at the call site.

### The atomic swap (`MentalPluginV5`)
```java
private volatile Snapshot snapshot;                 // the one reference readers consult
public Snapshot snapshot() { return snapshot; }     // rim/session/features read through this

public List<String> reloadAll() {
    configStore.ensureDefaultFiles();
    this.overlay = new Overlay(configStore.overridesFile());
    this.snapshot = parseSnapshot();                // parse a fresh whole snapshot…
    applyDebug(snapshot.debug());
    reconciler.converge(snapshot);                  // …then converge features onto it
    return parseIssues;
}
```
A reload parses a **complete** new `Snapshot` off to the side, then swaps `this.snapshot`
by one `volatile` reference. Readers holding the old reference finish coherently; the next
read sees the whole new snapshot. **Hit-relevant values are frozen into per-operation
context objects** (`HitContext`/`PlayerView`) at the start of an operation so even a
mid-operation swap can't tear a single hit.

### The two-layer config (human file + machine overlay)
- The **human YAML is exhaustively commented and never re-serialized** by code.
- The GUI/API write a **machine overlay** (`state/overrides.yml`); effective value =
  `overlay ?? file ?? default`. `overlaySet(key, value)` + reload is the only write path,
  so hand edits and comments always survive.
- `ConfigStore` extracts bundled defaults only when missing, runs a versioned **migration
  chain**, and loads all files into typed `Configuration` roots the overlay is applied over
  in memory.

**Transfers directly to factions — wholesale and high-value.** A factions plugin has
substantial config (claim limits, power settings, world flags, per-faction toggles, GUI
layouts). Adopt: (1) an immutable `Snapshot` with typed reads and per-section `DEFAULTS`
records; (2) `volatile` reference atomic-swap on `/f admin reload` so a live server picks
up config without a restart and no listener reads a half-applied config; (3) warn-and-fallback
parsing; (4) the **overlay split** so the GUI ("toggle faction PvP", "set claim limit")
writes a machine overlay and never clobbers the admin's commented YAML. Freeze the config
values a single command/teleport needs into a small context at entry so a concurrent reload
can't tear it.

---

## 8. How the boot report communicates fallbacks

**Mandate B10: no silent degradation.** Every boot-selected fallback prints **one loud
line**; the boot report / manifest is the ground truth of what resolved present/absent per
version. Aggregation over spam: one line per subsystem, not one per probe.

From `MentalPluginV5.onEnable`:
```java
this.platformProfile = PlatformProfile.resolve(environment, capabilities, msg -> getLogger().warning(msg));
getLogger().info(platformProfile.bootReport());
getLogger().info("bytecode tier: " + describeBytecodeTier(loadedBytecodeMajor()));
if (!PersistentData.supported()) getLogger().warning("persistent-data-container ABSENT (server < 1.14) — …");
getLogger().info(() -> "Mental v5 enabled — server " + environment.describe()
        + ", scheduling=" + scheduling.describe() + ", ping=" + Pings.describe()
        + ", " + capabilities.describe());
getLogger().info(() -> "rule-feature accessors — absorption=" + Absorptions.describe()
        + ", potion-effect=" + PotionEffects.describe() + ", " + Cooldowns.describe()
        + ", crit-posture[" + CritPosture.describe() + "]" + ", hand-raised=" + HandStates.describe()
        + ", recipe-keys=" + Recipes.describe() + ", sweep-cause=" + SweepCauses.describe());
```
Each resolver owns a `describe()` returning an **era-truthful** sentence, e.g.:
> `no item-cooldown API (era-native pre-1.11 — pearl cooldown feature is a no-op)`

The `PlatformProfile.bootReport()` line rolls the manifest up:
```java
return "platform profile — " + present + "/" + entries.size() + " handles resolved; "
     + "sword-block=" + swordBlock.tier() + " attack-range=" + …
     + "; features disabled: " + (disabledFeatures.isEmpty() ? "none" : disabledFeatures);
```
A `Required` miss that disables a feature logs *"required handle … did not resolve —
disabling <Feature> on this version (a mapping break)"*, and that feature is dropped from
the reconciler. There is also a **CI/gate discipline**: the integration matrix **scans the
captured console log** for the swallowed-error signatures (Bukkit's "failed to register
events" SEVERE line, the per-event "Could not pass event" line, any framed linkage error) —
a green run with any of those signatures is structurally a **FAIL**, so a silent-degradation
trap can't ride a passing matrix.

**Transfers directly to factions.** On enable, print: server version + scheme,
`capabilities.describe()`, scheduling backend, and one line per resolver
(`pdc=…`, `adventure=native|legacy`, `async-teleport=…`). If a `Required` faction handle
misses, disable that sub-feature loudly and say so. Adopt the log-scanning CI check if you
run an integration matrix — it is what turns "a listener silently registered zero handlers"
from a field bug into a build failure.

---

## Feature lifecycle (the reconciler) — supporting cast

Features are `FeatureUnit`s (descriptor + `assemble(Scope, Snapshot)`), reconciled against
the snapshot. A `Scope` (`core/.../feature/Scope.java`) is an `AutoCloseable` bag of every
resource a feature acquires — `scope.listen(bukkitListener)`, `scope.packets(...)`,
`scope.task(...)`. Enabling runs `assemble`; disabling **closes the scope whole** (reverse
order, each close isolated, failures collected as suppressed). A throw mid-assemble closes
the partial scope and leaves the feature **OFF** (zero-touch on failure). There is no
`active` flag — the reconciler's map of open scopes is the truth. `disabledFeatures` from
the `PlatformProfile` are simply never converged.

**Transfers directly to factions.** Model each optional faction subsystem (claims, faction
chat, power/decay, warps, dynmap hook, the GUI) as a scoped feature: `/f admin reload`
converges to the new snapshot, cleanly closing listeners/tasks for a disabled subsystem and
opening them for an enabled one, with zero leaked handlers — the same "disabled feature does
nothing" invariant.

---

## Quick transfer scorecard for a factions plugin

| Pattern | Transfers? | Notes |
|---|---|---|
| Module split + build-enforced kernel purity | **Direct** | Kernel = claim geometry/relations/formulas, pure JDK, unit-tested. |
| `Capabilities` coarse boot probes | **Direct** | pdc, adventure-native, brigadier, folia, hex-colors, async-teleport. |
| `PlatformProfile` typed manifest (Required/OptionalSince) | **Direct** | One boot-resolved value; disable a sub-feature on a Required miss. |
| Resolver pattern (try-modern/fallback/degrade-loud) | **Direct** | pdc, async chat event, teleport causes, newer Material/EntityType. |
| Listener-descriptor & sticky-constant hazards | **Direct** | Any version-gated type in a listener descriptor; hoist behind a resolver. |
| `TextPort` (Component → §-legacy, shaded+relocated Adventure, boundary grep) | **Direct, high value** | Faction plugins are text-saturated; add title/actionbar/scoreboard sinks. |
| Scheduling seam (`runOn/runGlobal/runAt/ensureOn` + retired contract) | **Direct, essential** | Required for Folia; teleports/warmups/claim-border tasks route through it. |
| `compat-folia` folded by FQN behind a capability | **Direct** | Newer-than-floor code reachable only via reflective FQN gated on a probe. |
| Single-writer / immutable-snapshot state | **Adapt** | Map onto Folia regions + async storage; publish `ClaimsView` via AtomicReference. |
| Config `Snapshot` atomic-swap + overlay split | **Direct, high value** | Live `/f reload`; GUI writes overlay, never re-serializes commented YAML. |
| Boot report / no-silent-degradation + log-scan CI | **Direct** | One line per subsystem; CI fails on swallowed-error signatures. |
| Multi-Release mega-jar (jvmdg v52 base + versions/17) | **Direct if sub-1.17 wanted** | Purely artifact-level; only needed to run below the compile floor on native-era Java. |
| Versioned NMS below 1.17 (`net.minecraft.server.<rev>`) | **Avoid** | Combat archaeology; a factions plugin should stay on the API surface. |
| PacketEvents / netty rim, protocol probes, combat domains | **Does NOT transfer** | Packet-level concerns with no factions analogue. |
| `DeliveryDesk` / velocity-arbitration / knockback pipeline | **Does NOT transfer** | Combat-specific. |
