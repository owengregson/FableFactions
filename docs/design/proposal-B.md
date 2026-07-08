# FableFactions — Proposal B: The Frozen-Seam Architecture

**Stance: cross-version seam minimalist.** The 1.7.10 → 26.1.2 range is won or lost at the
seam. This design resolves every version divergence exactly **once, at boot**, into a frozen
manifest of **monomorphic ports** — single-implementation interfaces the JIT devirtualizes —
with era adapters as leaf classes that are never classloaded unless their probe passes.
Business logic is **provably era-blind**: it lives once, in modules that cannot see Bukkit or
can only see the 1.13 floor API, and architecture tests + build gates make that un-rottable.

Package root: `dev.fablemc.factions`. Plugin name `FableFactions`. Root command `/f`
(aliases `faction`, `factions`), admin `/fa` (alias `factionadmin`).

---

## 0. Reading map

| Section | Contents |
|---|---|
| §1 | One-jar contract, support matrix, bytecode tiers |
| §2 | Module / Gradle graph + build-time enforcement |
| §3 | **The platform seam** (probe inventory, port inventory, era adapters, descriptor discipline, boot ceremony) — the differentiator |
| §4 | Threading & state model (the Realm: single writer, wait-free read plane) |
| §5 | The five hot paths, data structures, allocation stories |
| §6 | Persistence (projection model, schema, write/load/migration, H2/MySQL parity) |
| §7 | Protection engine (event-complete, probe-gated listener loadout) |
| §8 | Config system |
| §9 | Messages / i18n / text pipeline |
| §10 | Public API + integration adapters |
| §11 | Build pipeline (Mental recipe: kept / changed / why) |
| §12 | Feature → module mapping (requirement-1 proof) |
| §13 | Testing strategy |
| §14 | How each catalogued bug class is made impossible |
| §15 | What is novel here — testable claims |
| §16 | Risks & consciously deferred items |

---

## 1. The one-jar contract

One shipped artifact: `FableFactions-<version>.jar`, a **Multi-Release mega-jar**:

- **Base tree = class-file major 52 (Java 8)**, produced by JVMDowngrader from the v61
  compile output. Loads on every JVM Java 8+ — this is what closes the 1.13.2/1.14.4
  (hard Java-13 cap), 1.15.2 (14), 1.16.5 (16) holes, and hosts 1.7.10/1.8.x on their
  historical Java 8 runtime.
- **`META-INF/versions/17` = the original v61 classes, byte-identical** to the compile
  output (`multiReleaseOriginal=true`, no `versions/16` tier — jvmdg 1.3.6 treats `-mro`
  and `-mr` as mutually exclusive; requesting a 16 tier silently drops the original v61).
- All third-party libs shaded + relocated under `dev/fablemc/factions/lib/…`; jvmdg's own
  runtime helpers under `dev/fablemc/factions/lib/jvmdg/…` (plugin-unique prefix — the D-8
  cross-plugin isolation rule).
- `plugin.yml` (never paper-plugin.yml): `api-version: '1.13'` (ignored below 1.13; accepted
  1.13→26.x; avoids the Commodore legacy-material shim that would fight our modern-name
  kernel), `folia-supported: true`, `main: dev.fablemc.factions.boot.FableFactionsPlugin`.
- `softdepend: [TeamsAPI, Vault, WorldGuard, WorldEdit, PlaceholderAPI, Essentials,
  EssentialsX, dynmap, DiscordSRV, EzEconomy, EzCountdown, LWC, LWCX]`.

**`support-matrix.json` is the single source of truth** — no Minecraft version or JDK literal
lives anywhere else in the build. Shape (extends Mental's, down to 1.7.10):

```json
{ "floorApi": "1.13",
  "entries": [
    { "version": "1.7.10", "jdk": 8,  "platform": "paperspigot", "suites": "full", "ci": "release", "bytecodeTier": 52 },
    { "version": "1.8.8",  "jdk": 8,  "platform": "paper",       "suites": "full", "ci": "release", "bytecodeTier": 52 },
    { "version": "1.9.4",  "jdk": 21, "platform": "paper",       "suites": "full", "ci": "pr",      "bytecodeTier": 61 },
    { "version": "1.12.2", "jdk": 21, "platform": "paper",       "suites": "full", "ci": "release", "bytecodeTier": 61 },
    { "version": "1.13.2", "jdk": 13, "platform": "paper",       "suites": "full", "ci": "release", "bytecodeTier": 52 },
    { "version": "1.14.4", "jdk": 13, "platform": "paper",       "suites": "full", "ci": "release", "bytecodeTier": 52 },
    { "version": "1.15.2", "jdk": 14, "platform": "paper",       "suites": "full", "ci": "release", "bytecodeTier": 52 },
    { "version": "1.16.5", "jdk": 16, "platform": "paper",       "suites": "full", "ci": "release", "bytecodeTier": 52 },
    { "version": "1.17.1", "jdk": 17, "platform": "paper",       "suites": "full", "ci": "pr",      "bytecodeTier": 61 },
    { "version": "1.20.6", "jdk": 21, "platform": "paper",       "suites": "full", "ci": "release", "bytecodeTier": 61 },
    { "version": "1.21.11","jdk": 25, "platform": "paper",       "suites": "full", "ci": "release", "bytecodeTier": 61 },
    { "version": "26.1.2", "jdk": 25, "platform": "paper",       "suites": "full", "ci": "pr",      "bytecodeTier": 61 },
    { "version": "26.1.2", "jdk": 25, "platform": "folia",       "suites": "claims-smoke", "ci": "release", "bytecodeTier": 61 }
  ]}
```

`bytecodeTier` is the tier that entry's loader × JVM *actually reads*, asserted live by the
tester (`-Dfable.tester.tier`). 1.7.10/1.8 on Java 8 read the v52 base; their Java 9+/17
flagless-boot behavior is one of the UNCERTAINs from `version-deltas.md` Part 4 and is
ladder-probed once in CI before any claim is widened.

**Compile floor decision: `spigot-api 1.13.2-R0.1-SNAPSHOT`** (not Mental's 1.17.1).
Rationale (version-deltas §3.17): a factions plugin's protection listeners live almost
entirely in ancient, stable APIs; compiling against 1.13 makes the whole 1.13–1.16 band a
zero-probe zone and confines runtime resolution to the 1.7–1.12 band plus modern extras.
This shrinks the probe surface — the seam-minimalist move — at zero correctness cost (the
MRJAR shape is unchanged; `options.release=17` still emits v61 which jvmdg lowers).

---

## 2. Module / Gradle graph

```
settings.gradle.kts:
include(":kernel", ":ports", ":platform", ":api", ":core",
        ":compat-modern", ":compat-folia", ":tester")
project(":compat-modern").projectDir = file("compat/modern")
project(":compat-folia").projectDir  = file("compat/folia")
```

| Module | Compile classpath | Sees Bukkit? | Role |
|---|---|---|---|
| `:kernel` | pure JDK (release 17 source, no deps but JUnit) | **No — build-enforced** | All domain logic: claim geometry & packed chunk keys, relation algebra, power/land/tax/streak/kill-scale math, money parsing/rounding, shield-window math, invite TTL, name validation, the protection *decision* function, the Realm state machine and its command/mutation types, message-template model. 100% unit-testable headless. |
| `:ports` | `:kernel` + `compileOnly(spigot-api 1.13.2)` | Types only in signatures | The **seam interface inventory** (§3.3): ~14 small interfaces + the frozen `Ports` record + `Capabilities` record. Zero implementations, zero logic. |
| `:platform` | `:ports`, `:kernel`, `compileOnly(spigot-api 1.13.2)` | Yes | Probe engine (`Probes`, `CapabilityManifest`, `PortResolver`, `BootReport`), and the **baseline (floor) implementation of every port** using only universal APIs, plus the legacy-band resolvers (MethodHandles, `Enum.valueOf` constants, reflection-by-name). |
| `:compat-modern` | `compileOnly(:ports, :platform, paper-api 1.20.6)` | Yes (newer than floor) | Leaf era adapters that need post-floor **types in descriptors**: `PaperChatRendererListener` (AsyncChatEvent), `NativeAudienceTextPort`, `PdcItemMarkPort`, `BytesItemCodec` (`serializeAsBytes`), `AsyncTeleportPort`, `BrigadierDecorator` (1.20.6 lifecycle), `ModernExplodeListener` variants, `RaidListener`, `SignSideListener`. Each class individually probe-gated. |
| `:compat-folia` | `compileOnly(:ports, :platform, paper-api 1.20.6)` | Yes | `FoliaSchedulePort` only (Global/Region/Entity/Async schedulers, retired-callback contract). Loaded only behind the `RegionizedServer` probe. |
| `:api` | `compileOnly(spigot-api 1.13.2)`, `:kernel` value types | Yes (events) | Public surface `dev.fablemc.factions.api.*`: custom Bukkit events, read-only service interfaces, `FableFactionsApi` entry point. Guarded by the japicmp only-grow gate. |
| `:core` | `:api`, `:ports`, `:platform`, `:kernel`; `compileOnly(spigot-api 1.13.2)` + `compileOnly` integration APIs (Vault, WorldGuard, dynmap, PlaceholderAPI, Essentials, LWC via reflection-only, DiscordSRV via reflection-only, TeamsAPI, EzCountdown) | Yes | The plugin: boot ordering, Realm wiring, persistence, listeners, commands, GUI, integrations, config, i18n. Runs the mega-jar pipeline; folds compat modules in by name. |
| `:tester` | all modules `compileOnly` | Yes | Second plugin jar that boots on every matrix server, runs suites in-process, asserts the loaded bytecode tier, echoes a per-run nonce. Distinct jvmdg shade prefix `dev/fablemc/factions/tester/lib/jvmdg/`. |

**Build-enforced dependency rules** (all fail the build, not code review):

1. Kernel purity — `kernel/build.gradle.kts`:
   ```kotlin
   configurations.all {
     resolutionStrategy.eachDependency {
       require(!requested.group.startsWith("io.papermc"))    { "kernel must stay Bukkit-free" }
       require(!requested.group.startsWith("org.spigotmc"))  { "kernel must stay Bukkit-free" }
       require(!requested.group.startsWith("net.kyori"))     { "kernel must stay Adventure-free" }
     }
   }
   ```
   plus `KernelClasspathTest` asserting `Class.forName("org.bukkit.Bukkit")` throws on the
   kernel test classpath.
2. `:ports` may contain **no method bodies** beyond `default` one-liners and records — an
   ArchUnit test caps class count and bans field state (interfaces + records only).
3. `:compat-*` may not depend on `:core` (Gradle project graph) and may not import
   `dev.fablemc.factions.realm.*` or persistence packages (ArchUnit) — compat modules are
   **adapters only**; zero business logic can exist there, which is what makes "zero repeated
   logic across eras" structural rather than aspirational.
4. `java.sql`/`javax.sql`/Hikari imports are legal **only** in `dev.fablemc.factions.persist`
   (ArchUnit) — see §14 BUG-9.
5. The descriptor-floor gate (§3.5) runs over `:core` + `:platform` output.

---

## 3. The platform seam

### 3.1 Design laws

1. **Probe, never parse.** No version-string comparison decides behavior, ever. A
   class/method/field/enum-constant either resolves on this server or it does not.
   `ServerFingerprint` (the parsed version) exists solely for the boot report and bStats.
2. **Resolve once, freeze, read forever.** All probes run in `onEnable`, before any listener
   registers, and produce two immutable values: `Capabilities` (coarse booleans) and `Ports`
   (the resolved backends). Hot paths read plain final fields; nothing re-probes.
3. **One loaded implementation per port per JVM.** Era backends are selected by
   `PortResolver`; the losing backends are **never classloaded** (compat classes are
   referenced by FQN string only). Consequence: every `ports.x().y()` call site is
   monomorphic — CHA lets the JIT devirtualize and inline it. This is the performance story
   of the seam: the abstraction costs nothing after warmup.
4. **Era adapters are leaves.** A compat class implements one port or one listener and calls
   back only into `:ports`/`:kernel` types. It contains no decisions, no config reads, no
   persistence — those all live once, era-blind, in `:core`/`:kernel`.
5. **No sub-floor symbol in a baseline descriptor** (§3.5), **no direct `getstatic` of a
   post-1.7.10 enum constant** anywhere outside the constants resolver, **no `net.kyori` type
   across a Bukkit boundary** outside `TextPort`, **no `InventoryView` virtual call** in
   shared code. Each rule has a matching build gate.

### 3.2 Capability probe inventory

`Capabilities.detect()` — coarse booleans, each one `Class.forName`/`getMethod`/behavioral
probe in try/catch. This is the *complete* list (one row per probe; the boot report prints
them grouped):

| Capability | Probe | Introduced | Consumers |
|---|---|---|---|
| `folia` | `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` | Folia | SchedulePort selection, chest viewer policy |
| `foliaSchedulers` | `Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler")` | ~1.20 Paper | FoliaSchedulePort |
| `collectionOnlinePlayers` | MethodHandle lookup of `getOnlinePlayers()Ljava/util/Collection;` then `()[Lorg/bukkit/entity/Player;` | 1.7.9→1.8 window (per-build on 1.7.10!) | PlayersPort |
| `playerByUuid` | `getMethod(Server, "getPlayer", UUID.class)` | 1.7.9-era | PlayersPort (fallback: linear scan) |
| `flattened` | `Material.getMaterial("WHITE_WOOL") != null` | 1.13 | MaterialPort |
| `namespacedKey` | `Class.forName("org.bukkit.NamespacedKey")` | 1.12 | ItemMarkPort, recipes |
| `pdc` | `Class.forName("org.bukkit.persistence.PersistentDataContainer")` | 1.14 | PdcItemMarkPort |
| `nativeAudience` | `getMethod(CommandSender, "sendMessage", net.kyori…Component)` by name string | Paper 1.16.5 | NativeAudienceTextPort |
| `bungeeChat` | `Class.forName("net.md_5.bungee.api.chat.BaseComponent")` + `Player.Spigot#sendMessage` | Spigot 1.8 (1.7.10 UNCERTAIN → probed) | BungeeTextPort |
| `hexChat` | server fingerprint ≥ 1.16 clients only — *the one place a version number is consulted*, and only to pick the legacy serializer's downsampling mode (a rendering constant, not behavior) | 1.16 | TextRenderer |
| `asyncChatEvent` | `Class.forName("io.papermc.paper.event.player.AsyncChatEvent")` | Paper 1.16.5 | ChatModel selection |
| `offhand` | `Enum.valueOf(EquipmentSlot,"OFF_HAND")` | 1.9 | GUI click filter (slot-40 swaps) |
| `swapHandEvent` | `Class.forName(PlayerSwapHandItemsEvent)` | 1.9 | GuiListenerLoadout |
| `entityPickupEvent` | `Class.forName(EntityPickupItemEvent)` | 1.12 | protection loadout (dual-register + dedupe) |
| `blockExplodeEvent` | `Class.forName(BlockExplodeEvent)` | 1.8.3 | protection loadout |
| `interactAtEntity` | `Class.forName(PlayerInteractAtEntityEvent)` | 1.8 | armor-stand protection |
| `armorStand` | `Class.forName(org.bukkit.entity.ArmorStand)` | 1.8 | protection loadout |
| `mountEventBukkit` / `mountEventSpigot` | dual `Class.forName` (`org.bukkit.event.entity.EntityMountEvent` / `org.spigotmc…`) | 1.20.3 move | mount protection (register whichever exists) |
| `pistonRetractBlocks` | `getMethod(BlockPistonRetractEvent, "getBlocks")` | 1.8 | piston protection (fallback `getRetractLocation`) |
| `sweepAttackCause` | `Enum.valueOf(DamageCause,"ENTITY_SWEEP_ATTACK")` | 1.11 | PvP engine constants |
| `spectator` | `Enum.valueOf(GameMode,"SPECTATOR")` | 1.8 | fly/bypass logic |
| `glideEvent` | `Class.forName(EntityToggleGlideEvent)` | ~1.10 | fly-in-territory rules |
| `raidEvent` | `Class.forName(org.bukkit.event.raid.RaidTriggerEvent)` | ~1.15 | no-raids-in-claims listener |
| `teleportAsync` | `getMethod(Entity, "teleportAsync", Location.class)` | Paper 1.13 | TeleportPort (mandatory on Folia) |
| `chunkAtAsync` | `getMethod(World, "getChunkAtAsync", int.class, int.class)` | Paper ~1.13 | ChunkPort (home-safety checks) |
| `forceLoaded` | `getMethod(World, "setChunkForceLoaded", …)` | 1.13.2 | ChunkPort |
| `minHeight` | `getMethod(World, "getMinHeight")` | ~1.17 | WorldPort (fallback 0) |
| `sendTitleTimed` / `sendTitle2` | `getMethod(Player,"sendTitle",5-arg / 2-arg)` | ~1.11.2 / ~1.11 | FeedbackPort |
| `actionBarSpigot` | `getMethod(Player.Spigot, "sendMessage", ChatMessageType, BaseComponent[])` | 1.12 | FeedbackPort |
| `bossBar` | `getMethod(Server,"createBossBar",…)` | 1.9 | FeedbackPort |
| `scoreboard64` | = `flattened` (limits raised at 1.13) | 1.13 | NametagPort budgeter (16 vs 64) |
| `teamAddEntry` | `getMethod(Team,"addEntry",String.class)` | ~1.8.x | NametagPort |
| `teamSetColor` | `getMethod(Team,"setColor",ChatColor.class)` | ~1.12 | NametagPort |
| `clickedInventory` | `getMethod(InventoryClickEvent,"getClickedInventory")` | UNCERTAIN at 1.7.10 → probed | GuiPort (fallback rawSlot math) |
| `itemBytesCodec` | `getMethod(ItemStack,"serializeAsBytes")` | Paper ~1.16.5 | chest blob codec |
| `hidePlayerPlugin` | `getMethod(Player,"hidePlayer",Plugin.class,Player.class)` | 1.12.2 | PlayersPort |
| `commandMap` | `getMethod(Server,"getCommandMap")` else `CraftServer.commandMap` field reflection | Paper ~1.16.5 / always | CommandPort dynamic aliases |
| `asyncTabComplete` | `Class.forName(com.destroystokyo.paper.event.server.AsyncTabCompleteEvent)` | Paper ~1.12 | CommandPort |
| `brigadier` | `Class.forName("io.papermc.paper.command.brigadier.Commands")` | Paper 1.20.6 | BrigadierDecorator |
| `signSides` | `Class.forName(org.bukkit.block.sign.SignSide)` | 1.20 | sign protection nicety |
| `inventoryViewShape` | none — **never probed as a boolean**; all view access is MethodHandle `findVirtual`, which resolves correctly against class *or* interface | 1.21 flip | GuiPort |

Second tier — `PortProfile` (Mental's `PlatformProfile` pattern): every probe above is folded
into typed entries, `Required.owned(name, Feature, resolver)` (miss ⇒ disable owning feature
loudly, or fail boot if engine-critical) or `OptionalSince.resolve(name, since, fallback,
fallbackNote, resolver)` (miss below `since` ⇒ quiet typed fallback declared at the entry).
The resolved profile emits one boot-report line: `port profile — 38/41 handles; disabled: none`.

### 3.3 The port inventory

The `Ports` record — built once, stored in a `final` field on the plugin, passed by
constructor injection (never a static singleton, so tests can fabricate it):

```java
public record Ports(
    SchedulePort scheduler, TextPort text, PlayersPort players, MaterialPort materials,
    GuiPort gui, ChunkPort chunks, WorldPort worlds, ItemMarkPort itemMarks,
    ItemCodecPort itemCodec, TeleportPort teleport, FeedbackPort feedback,
    NametagPort nametags, CommandPort commands, ChatModelPort chatModel) {}
```

| Port | Methods (abridged) | Backends (only one loads) |
|---|---|---|
| **SchedulePort** | `runGlobal(Runnable)`, `runAt(Location,Runnable)`, `runOn(Entity,Runnable,Runnable retired)`, `runOnLater(Entity,long,Runnable,Runnable)`, `repeatGlobal/repeatOn/repeatAsync(...) → TaskHandle`, `runAsync(Runnable)`, `isOwnedByCurrentRegion(Entity)`, `ensureOn(...)` default, `describe()` | `BukkitSchedulePort` (:platform; everything collapses to the main thread) / `FoliaSchedulePort` (:compat-folia). **Retired contract** pinned by a TCK: exactly-once, either-thread, may-be-immediate; Folia's null-return-on-retired quirk papered over (`if (scheduled==null) retired.run()`); delays clamped ≥ 1 tick (kills the reference's BUG-20). |
| **TextPort** | `send(CommandSender, RenderedMessage)`, `title/actionBar(Player, RenderedMessage, times)`, `broadcast(...)` | `NativeAudienceTextPort` (:compat-modern — bridges pre-rendered JSON to the server's own Adventure via MethodHandles whose lookup strings are built with `String.valueOf` to defeat relocation) / `BungeeTextPort` (:platform, probe-gated leaf — JSON → `ComponentSerializer.parse` → `player.spigot().sendMessage`) / `FlatTextPort` (:platform, § string, universal floor incl. 1.7.10). See §9 for the render-once pipeline. |
| **PlayersPort** | `forEachOnline(Consumer<Player>)`, `onlineCount()`, `byId(UUID)`, `byExactName(String)`, `hide/show(Player,Player)` | One class in :platform. `getOnlinePlayers` resolved at boot as a MethodHandle against **both** descriptors (`Collection` first, `Player[]` wrapped); `byId` via probed `getPlayer(UUID)` or linear scan. Every online-player iteration in the entire plugin goes through this port (lint-gated). |
| **MaterialPort** | `icon(ModernMaterial, int amount) → ItemStack`, `matchConfigured(String) → ModernMaterial` | `FlatMaterialPort` (identity, 1.13+) / `LegacyMaterialPort` (:platform — one static table modern-name → (legacy enum name, data byte); the data-byte `ItemStack` constructor appears **only** here). Kernel vocabulary is modern-only. |
| **GuiPort** | `create(MenuHolder,int size,RenderedMessage title) → Inventory`, `topSize(InventoryEvent)`, `isTopSlot(InventoryClickEvent)`, `clickedIsTop(...)` | One class in :platform. `createInventory(holder,int,String)` universal. **Zero direct `InventoryView` calls anywhere**: the two genuinely needed view reads (`getTopInventory` size, cursor) resolve via `MethodHandles.lookup().findVirtual(InventoryView.class, …)` at boot — MethodHandles dispatch correctly whether `InventoryView` is the pre-1.21 class or the 1.21 interface, defusing the ICCE landmine on both halves of the range. Everything else is rawSlot arithmetic on stable classes. |
| **ChunkPort** | `getChunkAsync(World,int,int, Consumer<Chunk>)`, `setForceLoaded(World,int,int,boolean)` | Probe-selected async (Paper 1.13+) vs sync-on-owning-thread fallback. Claims never call it — claim membership is pure math (§5a). Used only for `/f home`/warp landing safety. |
| **WorldPort** | `minHeight(World)`, `maxHeight(World)` | Probed `getMinHeight` else 0. No hardcoded Y anywhere (homes clamp against this). |
| **ItemMarkPort** | `mark(ItemStack, key, value)`, `read(ItemStack, key)` | `PdcItemMarkPort` (:compat-modern, 1.14+) / `LoreStegoItemMarkPort` (:platform — §-encoded invisible lore line 0 + scoreboard tags for entities, validated on read). |
| **ItemCodecPort** | `encode(ItemStack[]) → VersionedBlob`, `decode(VersionedBlob) → ItemStack[]` | `BytesItemCodec` (:compat-modern — `serializeAsBytes`, DataVersion-aware, upgrades on read) / `YamlItemCodec` (:platform — Bukkit YAML + our modern-name translation table). Every stored blob is tagged `{codec, dataVersion, payload}`; cross-flattening migration is an explicit `/fa chest migrate` step, never an implicit load (version-deltas Risk #9). |
| **TeleportPort** | `teleport(Player, Location, TeleportCause, Consumer<Boolean>)` | `AsyncTeleportPort` (:compat-modern, `teleportAsync` — mandatory on Folia) / `SyncTeleportPort` (:platform — `runOn(player)` then `teleport`). All teleports (home, warp, stuck) route here after the first-party warmup pipeline (§12). |
| **FeedbackPort** | `countdown(Player, RenderedMessage, seconds)`, `title(...)`, `actionBar(...)`, `bossBar(...) → Handle` | One :platform class with probe chains resolved at boot into three `MethodHandle` slots: actionbar = Adventure → Spigot ChatMessageType → chat line; title = Adventure/5-arg → 2-arg → chat line; bossbar = API (1.9+) → actionbar fallback. **No NMS anywhere** — on 1.7.10/1.8-era the fallback is a chat line, declared in the boot report. |
| **NametagPort** | `setTeamText(Team, RenderedMessage prefix, suffix, color)` | One :platform class: budget-truncates to 16 chars **after** color-code accounting pre-1.13, 64 after; name color via prefix-tail pre-1.13, `Team#setColor` when probed; `addEntry` vs `addPlayer(OfflinePlayer)` fallback. Consumers: the optional relation-color nametag feature and any scoreboard sidebar (off by default; the port exists so no future feature re-solves the 16-char trap). |
| **CommandPort** | `registerRoot(name, aliases, executor, completer)`, `decorate(CommandTreeSpec)` | plugin.yml `PluginCommand` + `TabCompleter` = the universal core (present 1.7.10). Dynamic alias registration via probed `getCommandMap()` else `CraftServer.commandMap` field reflection. `BrigadierDecorator` (:compat-modern, 1.20.6+) walks the same declarative `CommandTreeSpec` (§12) to add typed argument suggestions — the tree is the single source of truth; Brigadier is cosmetic. `AsyncTabCompleteEvent` listener probe-gated for off-thread member-name completion. |
| **ChatModelPort** | *(listener selection, not calls)* | Exactly **one** of `LegacyChatListener` (`AsyncPlayerChatEvent`, HIGHEST, universal — sets format with pre-rendered legacy tag) or `PaperChatRendererListener` (:compat-modern — `ChatRenderer.ViewerUnaware` on `AsyncChatEvent`) is registered per boot. Never both (no double-tag, no legacy/modern bridge ordering hazards). |

### 3.4 Era adapter layout & the loading rule

The rule, verbatim from Mental and enforced here by gates: **newer-than-floor code is
reachable only through a reflective FQN string, gated on a boot probe.** Nothing in `:core`
or `:platform` imports a compat class; `PortResolver` does:

```java
private static final String FOLIA_IMPL = "dev.fablemc.factions.compat.folia.FoliaSchedulePort";
static SchedulePort scheduler(Plugin plugin, Capabilities caps) {
    if (!caps.folia() && !caps.foliaSchedulers()) return new BukkitSchedulePort(plugin);
    try { return (SchedulePort) Class.forName(FOLIA_IMPL)
            .getDeclaredConstructor(Plugin.class).newInstance(plugin); }
    catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Folia detected but backend failed to load", e); }
}
```

Compat classes land in the one jar via shadowJar `from(project(":compat-modern")…output)` +
`from(project(":compat-folia")…output)`; the jvmdg downgrade classpath is the **union** of
all three compile classpaths (or jvmdg emits supertype warnings, which fail the build).

**Adapter census** (the seam-minimalism scoreboard, asserted by a build report task
`reportCompatFootprint` that fails if the count grows without a matrix-file entry):

- `:compat-folia`: 1 class.
- `:compat-modern`: ≤ 12 classes (chat renderer, native text, PDC marks, bytes codec, async
  teleport, brigadier decorator, raid listener, sign-side listener, modern pickup listener,
  mount-event(bukkit-pkg) listener, async tab-complete listener, chunk-async impl).
- `:platform` probe-gated leaf classes (compiled at floor but registered conditionally):
  ~10 listener classes + ~8 resolvers.

Everything else — every command, every rule, every formula, every message — exists exactly
once and is era-blind.

### 3.5 Descriptor-hazard discipline (and the gates that pin it)

The two live-reproduced hazards from Mental (GAP 1/2), which for a factions plugin are
existential (its value proposition IS its listeners):

1. **Listener-descriptor hazard.** Bukkit's `createRegisteredListeners` reflects over every
   declared method of a listener class; any method/field descriptor naming a type absent on
   this server throws `NoClassDefFoundError`, which Bukkit swallows into one SEVERE line and
   registers **zero handlers for the whole class**. Rule: any listener that mentions a
   post-1.7.10 type in any descriptor lives in its **own class**, registered only behind its
   probe (`ListenerLoadout`, §7.3). Sub-floor-typed helpers are hoisted into non-Listener
   classes instantiated behind resolvers (constant-pool entries resolve lazily; loading the
   helper links nothing).
2. **Sticky-`getstatic` hazard.** A direct read of a post-1.7.10 enum constant
   (`GameMode.SPECTATOR`, `DamageCause.ENTITY_SWEEP_ATTACK`, newer `TeleportCause`/
   `SpawnReason`/`ClickType.SWAP_OFFHAND`, …) compiles fine against the 1.13 floor and throws
   a **sticky** `NoSuchFieldError` on 1.7.10, rethrown on every event. Rule: every enum
   constant newer than 1.7.10 is resolved once at boot via `Enum.valueOf` in try/catch into
   nullable fields on `dev.fablemc.factions.platform.Constants` — the only class allowed to
   perform such resolution.
3. **MethodHandle signature splits.** Both-way binary breaks (`getOnlinePlayers` array vs
   Collection; `InventoryView` class vs interface) are resolved by boot-time
   `MethodHandles.lookup()` against explicit descriptors; call sites hold `static final
   MethodHandle` fields (JIT-constant-foldable) inside the owning port.

**The gates** (new relative to Mental, this proposal's signature contribution):

- **`verifyDescriptorFloor`** (build gate, ASM): a checked-in symbol table
  `gradle/floor-symbols/bukkit-1.7.10.txt` (classes + members extracted once from the
  PaperSpigot 1.7.10 API jar by `scripts/tools/FloorSymbolDump.java`, sha-pinned). The gate
  scans every class in `:core` + `:platform` output that is (a) a `Listener`, or (b)
  reachable from the baseline `ListenerLoadout` list, and fails if any **field or method
  descriptor** references an `org.bukkit`/`org.spigotmc`/`net.md_5` type absent from the
  table. Probe-gated classes are exempted by an explicit annotation
  `@ProbeGated("capabilityName")` that the gate cross-checks against the `ListenerLoadout`
  registration table — an exempt class that is *also* baseline-registered fails the build.
- **`verifyNoStickyGetstatic`** (build gate, ASM): scans `:core` + `:platform` for
  `GETSTATIC` instructions whose owner is a Bukkit enum and whose field is absent from the
  floor symbol table, outside `platform.Constants`. Fails on any hit.
- **`verifyTextBoundary`** (build gate, byte scan): no class outside
  `dev/fablemc/factions/text/` and `lib/adventure` may contain the token `net/kyori` (type
  ref or reflection string) — Mental's `verifyRelocation` generalized into the
  Component-boundary invariant.
- **D-9 console-swallow CI gate**: the integration matrix greps captured server logs for
  `has failed to register events for class dev\.fablemc\.factions\.`, `Could not pass event
  .* to FableFactions`, and `NoSuchFieldError|NoSuchMethodError|NoClassDefFoundError` with
  following frames naming `dev.fablemc.factions` — any hit turns a PASS into FAIL.

With these four, the entire "silently dead protection" bug class is a build/CI failure, not
a field incident.

### 3.6 Boot ceremony & boot report

Deterministic order in `onEnable` (total budget target < 2s + data load):

```
1. ServerFingerprint.parse()                      // report-only
2. Capabilities caps = Capabilities.detect()      // §3.2 coarse probes
3. Constants.resolve(caps)                        // Enum.valueOf table
4. PortProfile profile = PortProfile.resolve(caps)// typed Required/OptionalSince manifest
5. Ports ports = PortResolver.resolve(plugin, caps, profile)
6. ConfigStore.loadAll() → ConfigSnapshot (atomic ref set)   // §8
7. MessageCatalog.load(locales)                   // §9
8. Persist.open() → schema migrate → stream-load → RealmState  // §6
9. Realm.publish(initial read plane)              // §4
10. ListenerLoadout.register(caps)                // §7.3 — baseline + probe-gated classes
11. CommandPort.registerRoots(); BrigadierDecorator if probed
12. Integrations.init(order: vault → essentials → worldguard → lwc → bstats → papi → dynmap → ezcountdown → discordsrv → teamsapi)
13. BootReport.print()
```

Boot report (mandate: **no silent degradation** — every fallback is one loud line, aggregated
per subsystem, and the report is the ground truth the tester asserts against):

```
[FableFactions] server 1.8.8 (paper), scheduling=bukkit, bytecode tier=52(base)
[FableFactions] port profile — 33/41 handles; text=bungee-json, items=lore-stego, codec=yaml,
                teleport=sync, feedback=title:nms-absent→chat, gui.clickedInventory=rawslot-fallback
[FableFactions] listener loadout — 19 baseline, 6 probe-gated registered, 7 skipped (absent era)
[FableFactions] realm loaded: 214 factions, 5,412 players, 48,113 claims in 412 ms; storage=h2 flush=100ms
[FableFactions] integrations: Vault=on WorldGuard=off PAPI=on dynmap=off LWC=off DiscordSRV=off EzCountdown=off TeamsAPI=off
```

---

## 4. Threading & state model — the Realm

### 4.1 Ownership map

| Domain | Owner thread | State it owns |
|---|---|---|
| **D1 — Realm (write plane)** | the **global tick thread** via `SchedulePort.runGlobal` (Paper: main thread; Folia: global region thread) | The authoritative `RealmState`: `Faction` aggregates, memberships, ranks, relations (directed wishes), claims, banks, power, invites, merge requests, warps, chest directory, flags. **The only code that mutates it is `Realm.execute(RealmCommand)` running on this thread.** |
| **D2 — Read plane** | written by D1, read by everyone, lock-free | `ClaimTable` (per-world long→int), `RelationTable` (packed pair → byte, *effective* relations only), `PlayerAffinity` map (UUID → volatile factionId/overriding/power), `FactionDirectory` (int-indexed arrays: flag bitsets, raidable bit, pre-rendered chat tags, name, profile snapshot refs). |
| **D3 — StorageActor** | one dedicated thread (`fable-storage`) | The JDBC pool, the mutation journal consumer, history-query executor. The **only** code that touches `java.sql`. |
| **D4 — Async utility** | `runAsync` pool | Update checker, dynmap batch redraws, bStats, name→UUID warm-ups, tab-complete candidate building. Reads snapshots only. |
| **D5 — Region/event threads** | per-event | Listeners: read D2, never mutate. Mutating intents become `RealmCommand`s submitted via `Realm.submit(...)` (which `ensureOn`-inlines when already on the global thread — zero hop on Paper). |

### 4.2 Why the writer is the global thread (not a private executor)

- Mutations are pure in-memory map operations (microseconds); there is **no IO on the writer,
  ever** (persistence is an async projection, §6). Even a 200-chunk `/f claim square` is
  ~200 hash-table writes.
- Cancellable Bukkit events (`FactionChunkClaimEvent`, bank events) must fire synchronously
  where plugins expect them; the global thread is legal on both Paper and Folia for
  plugin-defined events, and on Paper it is exactly the historical behavior.
- Single-writer becomes *structural*: `Realm.execute` asserts
  `scheduler.isGlobalThread()` and every state-mutating method is package-private to
  `dev.fablemc.factions.realm` (ArchUnit-pinned).

On Folia, a command typed on a player's region thread hops once to global (≤ 1 tick) and
replies via `runOn(player)`; protection checks — the latency-critical path — never hop.

### 4.3 Mutation flow (one deterministic pipeline)

```
Player/event intent
  → RealmCommand (immutable record, kernel type: ClaimChunk, Deposit, SetRelation, …)
  → Realm.submit(cmd)                    // ensureOn(global)
  → Realm.execute(cmd):
       1. decision = Kernel.decide(cmd, state, configSnapshot)   // PURE, unit-tested
       2. if decision.needsBukkitEvent → fire cancellable event; abort if cancelled
       3. state.apply(decision.mutations)                        // writer-only
       4. readPlane.apply(decision.mutations)                    // publish (release stores)
       5. journal.append(decision.mutations)                     // SPSC ring → StorageActor
       6. effects.dispatch(decision.effects, ports)              // messages, teleports,
                                                                 // integration fan-out
```

Properties this buys: **atomicity** (caps checked and applied in one step on one thread —
no TOCTOU), **determinism** (commands are a replayable log; property tests replay random
command sequences and assert invariants), **no read-modify-write races** (there is exactly
one copy of the truth and one mutator), **no dirty-cache divergence** (the DB is a projection
of memory, not a peer).

External-money operations (Vault) use **reserve → commit → settle**: the command pipeline
debits Vault only *after* the kernel decision confirms the full effect will apply, and any
storage/journal failure path issues the compensating Vault credit (§14, BUG-11/12).

### 4.4 Read-plane structures (single-writer, wait-free readers)

- **`ClaimTable`** — per-world open-addressed hash map, `long` key = `(chunkZ << 32) |
  (chunkX & 0xFFFFFFFFL)` (computed in kernel; never Paper's `getChunkKey`), `int` value =
  faction ordinal (0 = wilderness, 1 = SAFEZONE, 2 = WARZONE, ≥ 16 = normal). Layout: paired
  `long[] keys` / `int[] values`, power-of-two capacity, linear probing, load factor 0.5,
  no tombstone rot (deletes use backward-shift). Writer mutates in place with
  `VarHandle.setRelease` element stores; readers use `getAcquire`; resize builds a fresh
  table and swaps the table reference (readers racing a resize see either table — both
  complete). Semantics are a single-writer restriction of the well-understood non-blocking
  hash map family; the jcstress suite (§13) pins them. **Memory: 24 B/claim at LF 0.5 →
  1 M claims ≈ 24 MB, 5 M ≈ 120 MB** — millions of claims are a RAM line item, not an
  architecture problem.
- **`RelationTable`** — same family, `long` key = `pack(min(fa,fb), max(fa,fb))`, `byte`
  value = effective `Relation`. Only *effective* (symmetric) relations live here: ENEMY and
  NEUTRAL mirror by rule; ALLY/TRUCE appear only when mutual — which makes the reference's
  one-sided-ALLY build exploit (logic BUG-21) unrepresentable in the hot path.
- **`PlayerAffinity`** — `ConcurrentHashMap<UUID, PlayerAffinity>`; fields written by the
  writer only: `volatile int factionId`, `volatile int flags` (overriding, autoclaim mode,
  fly, combat-tagged bit), `volatile long powerBits`. Protection reads touch single volatile
  fields (no torn multi-field invariants on the hot path).
- **`FactionDirectory`** — int-indexed parallel arrays (`AtomicIntegerArray flagBits`,
  `AtomicIntegerArray raidable`, `AtomicReferenceArray<FactionProfile>` for cold snapshots:
  name, pre-rendered tags, home, member list, counts). Faction UUID ↔ int id interning is
  writer-owned; ids are never reused within a run.

### 4.5 Folia mapping summary

| Work | Route |
|---|---|
| Protection/PvP/move checks | inline on the event's region thread, read plane only |
| All state mutations | `runGlobal` (the Realm) |
| Player messages, titles, fly changes, teleport initiation | `runOn(player)` with `retired` cleanup |
| Warmup countdowns | `repeatOn(player)`; `retired` cancels exactly once |
| Claim-border particles / block effects (if enabled) | `runAt(location)` |
| Power tick, tax tick, raidable sweep | Realm commands on `repeatGlobal` |
| Storage | `fable-storage` thread (never a Bukkit scheduler) |
| Update check, dynmap redraw, bStats | `runAsync` |

`plugin.yml folia-supported: true`; `BukkitSchedulePort` is never touched on Folia.

---

## 5. The hot paths

### (a) Chunk → claim lookup on movement/block events

```java
// FableMoveListener (MONITOR, ignoreCancelled=true)
Location to = e.getTo(); if (to == null) return;                    // logic BUG-19 killed
int fx = fastFloor(e.getFrom().getX()) >> 4, fz = fastFloor(e.getFrom().getZ()) >> 4;
int tx = fastFloor(to.getX()) >> 4,        tz = fastFloor(to.getZ()) >> 4;
if (fx == tx && fz == tz) return;                                   // ~99.6% of moves
int worldId = worlds.id(to.getWorld());                             // identity CHM, no alloc
int owner  = claims.owner(worldId, ChunkKey.pack(tx, tz));          // 1–2 probes, 0 alloc
```

No `getChunk()` (which can allocate/load), no string keys, no boxing. **Allocation story:
zero bytes on the same-chunk path; zero on the crossing path** (territory-notice rendering
only allocates when a notice actually fires, and pulls pre-rendered `FactionProfile` text).
One consolidated move listener serves territory notices, auto-claim/unclaim, and fly
re-evaluation (the reference ran two separate MONITOR move handlers each doing DB work).

### (b) Relation lookup player ↔ player

```java
PlayerAffinity a = affinity.get(attacker.getUniqueId());  // CHM.get, 0 alloc
PlayerAffinity v = affinity.get(victim.getUniqueId());
int fa = a.factionId, fv = v.factionId;
byte rel = (fa == fv) ? MEMBER : relations.effective(fa, fv);  // packed-pair probe, 0 alloc
```

O(1), two volatile reads + one open-address probe. True-attacker resolution (projectile
shooter, TNT source, AreaEffectCloud, tamed-pet owner) runs *before* this and is a chain of
`instanceof` checks — no allocation, no reflection (types are all floor-present; the one
post-floor case, `AreaEffectCloud` on 1.9+, sits in a probe-gated helper).

### (c) Protection decision pipeline

One pure kernel function, primitive-in / enum-out — the single `canModify` the bug catalog
demands, used by *every* protection vector:

```java
// kernel — zero Bukkit, zero allocation, exhaustively unit-tested
public static Verdict decide(byte action,        // BUILD, INTERACT, CONTAINER, PVP, EXPLODE, BURN, …
                             int actorFaction,   // 0 = factionless
                             boolean actorBypass, boolean actorOverriding,
                             int ownerFaction,   // 0/1/2/n
                             byte relation, int ownerFlagBits, int zoneEnabledBits)
```

Returns `ALLOW`, or a deny verdict that maps 1:1 to a message key. Listener shape:

```java
verdict = AccessEngine.decide(BUILD, aff.factionId, bypassPerm, aff.overriding,
                              owner, rel, dir.flagBits(owner), cfg.zoneBits);
if (verdict != ALLOW) { e.setCancelled(true); text.send(p, msgs.deny(verdict, locale)); }
```

Deny messages are pre-rendered per (verdict, locale) — sending one is a lookup + one port
call. **Allocation story: 0 bytes for ALLOW; 0 bytes for deny** (message objects are cached
`RenderedMessage` instances). Explosions: `blockList().removeIf` consults a per-thread
reusable `LongByteScratch` memo (chunk-key → verdict) so a 300-block blast costs
O(distinct chunks) table probes — typically 1–9 — instead of the reference's ~600 blocking
JDBC calls (concurrency BUG-9).

### (d) Chat tag rendering

`FactionProfile` holds the tag **pre-rendered at faction create/rename/config-reload** in
all three delivery forms: `tagLegacy` (§ string), `tagJson`, and (modern boot only) a
lazily-bridged native `Component` cached as `Object`. Chat listener work per message:

- Legacy path: `event.setFormat(profile.tagLegacy + "%s: %s")` — one string concat, done.
- Paper renderer path: viewer-unaware renderer composes `cachedNativeTag + displayName +
  separator + message` — zero MiniMessage parsing per message, zero serialization per
  message.

Faction names are validated `[A-Za-z0-9_]{3,16}` at create/rename and inserted into
templates as **literal text nodes** (TagResolver placeholder), so the tag cache can never
contain attacker-controlled markup (logic BUG-7).

### (e) Power tick

Every `max(1, tick-interval-seconds) × 20` ticks, a `PowerTick` Realm command on
`repeatGlobal`:

1. Build the online set once: `players.forEachOnline` into a reusable `LongOpenHashSet`-style
   scratch of UUID halves (no per-player allocation).
2. Iterate the writer-owned dense member list (`PlayerRecord[]`); for each below max and not
   excluded: `PowerMath.apply(REGEN_ONLINE/OFFLINE, …)` — the same one pipeline as every
   other power change (freeze gate → source amount → multipliers (DEATH/KILL only) → event
   clamp (automatic sources only) → min/max clamp → effective delta).
3. Batch: mutations appended to the journal as one coalesced batch; raidable sweep
   (`currentLand > maxLand`, strict, vs claim-block's `>=` — both semantics preserved from
   the reference and pinned by tests) runs over `FactionDirectory` in the same pass.
4. Effects (member notices, server broadcasts, `[Power]` staff debug lines) dispatched via
   ports; offline recipients go to the inbox writer.

5 000 players ≈ 5 000 arithmetic ops + one journal batch: sub-millisecond on the global
thread; no Bukkit API is touched off-thread (concurrency BUG-19 dead).

---

## 6. Persistence — storage as a projection

### 6.1 Model

**Memory is authoritative; the database is a write-behind projection plus append-only
histories.** After boot, the Realm never reads the DB to answer anything. The only queries
after boot are the append-log histories (bank history, power history, audit pages) — always
on the StorageActor, delivered back via `runOn(sender)`.

### 6.2 Write path

- The writer appends `Mutation` records (small immutable kernel types:
  `PowerSet(playerId, newValue)`, `ClaimPut(world,x,z,factionId)`, `BankSet(factionId,
  newBalance, txRecord)`, …) to an SPSC ring buffer (`MutationJournal`, 65 536 slots,
  backpressure = writer-side flush-now signal; overflow is architecturally unreachable
  because mutations are bounded by command throughput).
- The StorageActor drains in batches every **100 ms** (`storage.flush-interval-ms`),
  **coalescing by key** (last-value-wins for scalar projections — legal because values are
  absolute states computed by the single authority, *never* SQL-side deltas; there is no
  `UPDATE x = x + ?` anywhere because there is no second writer to merge with).
- Each batch = one transaction: projection upserts + history inserts + tombstone deletes.
  Batch cap 512 statements; JDBC batch API.
- **Criticality classes**: `CRITICAL` mutations (bank ops, claims/unclaims, membership,
  disband) trigger an immediate flush of the pending batch; `LAZY` (power regen,
  last_activity) ride the interval. Crash-loss window ≤ interval for LAZY, ≈ 0 for CRITICAL.
- **Shutdown barrier** (`onDisable`): 1) stop command intake (submit rejects with a logged
  notice), 2) run remaining queued Realm commands, 3) force-persist open chest sessions,
  4) journal `drainAndFlush(timeout=10s)`, 5) close pool. Order pinned by a test.

### 6.3 Schema (v1 — clean-room, import-compatible)

Dialect module `SqlDialect` with two impls (`H2Dialect`, `MysqlDialect`) — the **only**
place SQL text differs. H2: `MERGE INTO … KEY(…)` rewrite, URL
`jdbc:h2:file:<path>;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE`, pool size 1. MySQL:
`INSERT … ON DUPLICATE KEY UPDATE`, pool from config (default 10, minIdle 2). Drivers shaded
+ relocated (`lib/h2`, `lib/mysql`), `driverClassName` set explicitly (Bukkit classloader
isolation; SPI service file excluded).

Key deltas from the reference schema (each fixing a catalogued defect):

| Table | Change vs reference | Why |
|---|---|---|
| `factions` | split: `factions` (identity, home, shield, flags_json→**`faction_flags` table**), `faction_balances(faction_id PK, money)`, `faction_state(faction_id PK, raidable)` | hot counters never share a row image with cold config (C-BUG-1 hygiene at the projection layer, even though memory-authority already kills the race) |
| `factions.name_key` | `VARCHAR(64) NOT NULL UNIQUE` (casefolded) | name uniqueness in the schema (C-BUG-17), belt to the in-memory check |
| `relations` | proper join table `(source_id, target_id, relation, PRIMARY KEY(source,target))` | kills relations_json RMW (C-BUG-16); wishes are rows, effective is derived |
| `board` | `PRIMARY KEY (world, x, z)` (VARCHAR(64), INT, INT) + `faction_id` index | no string parsing, no `world:x:z` split hazards |
| `merge_requests` | **created** in the migration (the reference registered but never created it) | documented reference bug |
| `players` | adds `fly_enabled TINYINT`, `combat_tag_until BIGINT`; `last_activity` written on join+quit+periodic | fixes C-BUG-23 (dead inactivity feature) and C-BUG-14 (fly state lost) |
| `schema_version` | single-row version table + ordered migration list | replaces version-less `ensureColumn` guessing; `ensureColumn`-style idempotence retained inside each migration for safety |
| `team_chests.contents` | `{codec, data_version, payload}` envelope | version-tagged blobs (§3.3 ItemCodecPort) |

All other tables (warps incl. password/use_cost, invitations, ranks, bank_transactions,
power_history, faction_inbox, audit_logs, team_chests) carry the reference's columns and
semantics (sorted-desc pagination contract preserved, but pushed to SQL
`ORDER BY created_at DESC LIMIT ? OFFSET ?` on the StorageActor).

**Importer**: `MigrationV0Import` detects the reference layout (`board` with `world:x:z`
string PK, `relations_json`, …) and performs a one-shot import to v1 at first boot,
including relation-JSON → rows and chunk-key parsing. Import is idempotent (guarded by
`schema_version`).

### 6.4 Load path

Boot streams each table with `fetchSize=1000` directly into Realm structures **before any
listener registers** (so no event can observe a half-loaded realm). 1 M claims at a
conservative 250 k rows/s ≈ 4 s; the boot report prints exact counts + ms. Memory-mapped
alternative deliberately rejected: H2/MySQL streaming is fast enough and keeps one storage
story.

---

## 7. Protection engine

### 7.1 Event-complete vector matrix (fixes logic BUG-5/6 wholesale)

All vectors funnel into `AccessEngine.decide` (§5c). Priorities mirror the reference's
proven interplay (WG NORMAL → ours HIGH ignoreCancelled → ally-unlock HIGHEST → observers
MONITOR).

| Vector | Event(s) | Listener class | Loadout |
|---|---|---|---|
| Break/place | BlockBreak/BlockPlace | `BuildListener` | baseline |
| Containers/doors/buttons/levers/plates/anvils | PlayerInteract (physical + right-click, material class table in kernel) | `InteractListener` | baseline |
| Buckets | PlayerBucketEmpty/Fill | `BucketListener` | baseline |
| Ignite | BlockIgnite (flint&steel, lava, spread causes) | `IgniteListener` | baseline |
| Pistons | BlockPistonExtend + Retract — **both source and destination chunks validated** | `PistonListener` (uses `getBlocks` when probed, `getRetractLocation` fallback on 1.7.10) | baseline |
| Liquid flow | BlockFromTo across a border with differing owners | `FlowListener` | baseline |
| Hanging (frames/paintings) | HangingBreakByEntity / HangingPlace | `HangingListener` | baseline |
| Armor stands | PlayerInteractAtEntity + manipulate | `ArmorStandListener` | probe-gated (1.8+) |
| PvP incl. ranged | EntityDamageByEntity with true-attacker resolution (projectile → shooter, TNTPrimed → source, AEC → owner, pets → owner) | `PvpListener` | baseline |
| Potions | PotionSplash (universal); Lingering/AEC apply | `PotionListener` baseline + `LingeringListener` probe-gated (1.9+) |
| Explosions (blocks) | EntityExplode (baseline) + BlockExplode | `ExplodeListener` + `BlockExplodeListener` | baseline + probe-gated (1.8.3+) |
| Explosion damage to entities | EntityDamageByEntity BLOCK_EXPLOSION/ENTITY_EXPLOSION causes | inside `PvpListener` | baseline |
| Fire spread | BlockSpread(source=FIRE) + BlockBurn | `FireListener` | baseline |
| Mob grief | EntityChangeBlock (enderman/wither/etc.) | `MobGriefListener` | baseline |
| Trample | PlayerInteract PHYSICAL soil / EntityInteract | `InteractListener` / `MobGriefListener` | baseline |
| Vehicles | VehicleDamage/VehicleDestroy | `VehicleListener` | baseline |
| Item pickup guards (chest key items) | PlayerPickupItem (baseline) / EntityPickupItem (1.12+, dedup rule: modern listener suppresses if both fire) | `PickupListener` / `ModernPickupListener` | baseline + probe-gated |
| Raids in claims | RaidTriggerEvent | `RaidListener` (:compat-modern) | probe-gated (≈1.15+) |
| Mounts | EntityMount (dual-package) | `MountListenerBukkit` / `MountListenerSpigot` | probe-gated pair — whichever class resolves |
| Signs | SignChange (baseline); side-aware editing | `SignListener` baseline; `SignSideListener` (:compat-modern) probe-gated (1.20+) |

Zone semantics preserved exactly (safezone: no build/no pvp when enabled; warzone: pvp
always, no build; wilderness build allowed; ally build allowed; fail-safe deny on internal
error). WG-sync mode preserved: fast-path allow when `TerritoryGuard.isFactionRegion`,
ally-unlock un-cancel at HIGHEST (mutual-ALLY only — the effective RelationTable makes
one-sided unlock impossible).

### 7.2 Decision inputs are read-plane only

A protection listener may reference: `Ports`, `ReadPlane` (claims/relations/affinity/
directory), `ConfigSnapshot`, `MessageCatalog`. ArchUnit denies it everything else
(specifically `realm.state`, `persist`, `java.sql`). That is what makes "no main-thread IO"
structural.

### 7.3 `ListenerLoadout`

One table in `:core` mapping each listener class → `BASELINE` or `@ProbeGated(capability)`.
`register(caps)` iterates it; the boot report prints registered/skipped counts; the
descriptor-floor gate (§3.5) validates the same table at build time. Registering a listener
any other way fails an ArchUnit rule (`HandlerList` references only inside the loadout and
`Scope`).

---

## 8. Config system

- **Typed immutable `ConfigSnapshot`**, built whole by a parser from `config.yml`,
  `roles.yml`, `database.yml` (boot-only), `notifications.yml`, `gui.yml`,
  `pre-defined.yml`. No live `FileConfiguration` reads at runtime. Sections are flat records
  with `DEFAULTS` constants (`PowerSettings`, `LandSettings`, `BankSettings`, `FlySettings`,
  `ChatSettings`, `ZoneSettings`, `OverclaimSettings`, `ShieldSettings`, `MergeSettings`,
  `RoleSettings`, `LanguageSettings`, `IntegrationSettings`, `NotificationSettings`,
  `GuiModel`, `PredefinedModel`, `UpdateSettings`, `MetricsSettings`).
- **Warn-and-fallback parsing**: bad/absent value → one boot/reload warning + default;
  parsing an empty file must equal `DEFAULTS` — pinned by parse-equality unit tests.
- **Atomic swap**: `/fa reload` parses a complete new snapshot off-thread, then a
  `ReloadCommand` on the Realm swaps the `volatile ConfigSnapshot` reference, rebuilds
  derived read-plane values (pre-rendered tags, zone bits), and converges scoped features
  (§14 BUG-3). Every command/teleport freezes the values it needs into its context record at
  entry — a mid-operation reload can never tear one operation.
- **One canonical key per tunable** (kills logic BUG-1's dual keys and BUG-22's divergent
  max-power): the parser reads the reference's full key tree (every key in
  `pvp-resources.md` §1 preserved, including `power.sources.*` amounts defaulting to their
  legacy aliases exactly as documented) but folds aliases into one canonical field with a
  deprecation warning when both are set and disagree.
- **Human file + machine overlay**: GUI/API writes go to `state/overrides.yml`
  (`overlaySet(key, value)` + reload); the commented YAML is never re-serialized by code.
- **Previously dead knobs are implemented or explicitly no-op'd with a boot warning**:
  `land.buffer-zone` (now enforced on *all* claim paths: reject claims within N chunks of a
  different faction's land), `economy.cost-create`/`cost-claim` (now charged via the
  reserve→settle pipeline when > 0), `fly.disable-on-threat` (now a real enemy-proximity
  revoke on the move listener). Feature-parity default behavior is unchanged (defaults keep
  them off/0).

---

## 9. Messages / i18n / text pipeline

- **MessageCatalog**: `messages/messages_<locale>.yml` for shipped locales `en, es, de, fr,
  pt-BR, zh, ru, ja` (reference key inventory preserved — every key in `pvp-resources.md` §9
  exists with the same default text). Waterfall: player locale → default → en → inline
  fallback. Locale normalization identical to reference (`pt-br → pt-BR` special case).
- **Render-once pipeline**: MiniMessage template (shaded, relocated Adventure) → parsed
  `Component` → `RenderedMessage { legacy §-string (hex downsampled below 1.16), json }` —
  computed **once per (key, locale)** for static messages and cached; parameterized messages
  render per call but reuse the parsed template AST. User-supplied strings (names, MOTD,
  descriptions, titles, warp names) enter only as `Placeholder.unparsed()` literal nodes —
  never string-concatenated into a template (logic BUG-7 structurally dead), plus the strict
  name allowlist and §/& stripping on intake.
- **Delivery** is `TextPort.send(sender, rendered)` — the only Component boundary
  (`verifyTextBoundary` gate). On modern the JSON bridges to the native Adventure once per
  rendered message; on Spigot 1.8+ hover/click survive via Bungee serializer; on 1.7.10 the
  flat § form delivers (rich text degrades, boot-reported).
- MiniMessage rich builders preserved: help entries with hover+suggest, invite Accept/Deny
  clickables, faction-info hovers, `/f map` clickable glyph grid (chat-based, no material
  dependency — cached row builders, `StringBuilder` reuse).

---

## 10. Public API + integration adapters

### 10.1 `dev.fablemc.factions.api`

- Custom events (all fired on the Realm thread, cancellable where the reference's were):
  `FactionCreateEvent`, `FactionDisbandEvent`, `FactionJoinEvent`, `FactionLeaveEvent`,
  `FactionChunkClaimEvent` (with `Optional` overclaim victim), `FactionChunkUnclaimEvent`,
  `FactionBankTransactionEvent` (mutable amount, **fired pre-commit for DEPOSIT, WITHDRAW,
  and TRANSFER** — fixing C-BUG-22's post-commit transfer event), `FactionRelationChangeEvent`,
  `FactionPowerChangeEvent`.
- Read services (`FactionsApi.get()` via Bukkit `ServicesManager`): `FactionLookup`,
  `ClaimLookup` (O(1), read plane), `PowerLookup`, `RelationLookup`. Mutations only via
  `RealmCommands` facade returning `CompletableFuture<Result>` — third parties get the same
  single pipeline, not setters.
- japicmp `apiCompat` gate: `:api` may only grow (baseline jar committed under
  `gradle/api-baseline/`, sha-pinned; real classpath, never `--ignore-missing-classes`).

### 10.2 Integration adapters (two isolation strategies, preserved)

| Integration | Strategy | Adapter | Behavior parity notes |
|---|---|---|---|
| **Vault** | typed, guard-class (`Class.forName("net.milkbowl.vault.economy.Economy")` before touching) | `VaultEconomy` behind `EconomyBridge` iface + Noop | OfflinePlayer overloads only; all charge flows via reserve→settle (§4.3) |
| **PlaceholderAPI** | typed, presence-gated | `FablePlaceholders` (`%fable_…%` + legacy `%pvpindex_…%` alias set) | serves from read plane — zero DB, zero blocking; same param list & fallbacks as reference |
| **WorldGuard** | typed façade `TerritoryGuard` + Noop + factory | `WorldGuardTerritoryGuard`, `WorldGuardRegionSync` (claim events → region mirror, `syncAll` on enable) | WG-sync build fast path + HIGHEST ally-unlock preserved |
| **dynmap** | typed, presence-gated | `DynmapLayer` — marker set `fable_factions`, 8-color palette by `abs(id.hashCode())%8`, 16×16 area markers, id `factionId~world~x~z`, MONITOR handlers on claim/unclaim/disband, `loadAllClaims` +1 tick | driven from API events + read plane; batch redraw on `runAsync` |
| **DiscordSRV** | **reflection-only** (JDA 4 lookups) | `DiscordSrvNotifier` + `DiscordSrvFactionListener` | same event→template config keys |
| **Essentials/EssentialsX** | typed façade + Noop + factory | `EssentialsInterop` (jail check, vanish check, teleport delegation) | **but** warmup/cooldown/combat-tag are first-party (§12 row "home/warp") — Essentials is an optional enhancer, never a security dependency (logic BUG-13) |
| **LWC/LWCX** | **reflection-only** | `LwcInterop`: creation gating, stale-protection removal on interact, bulk cleanup on claim change (3 config toggles preserved) | consults `AccessEngine` for build rights |
| **TeamsAPI** | registrar interface + reflective impl (`Class.forName("…api.TeamsApiRegistrarImpl")`), optional providers (chest 2.3 / relation 1.6 / notification 1.7 / power-history 1.8) registered reflectively | full provider set from the reference §10, incl. role mapping + `RoleChangeNotifierHolder` | documented reference quirks (adapter power totals without inactive-exclusion) preserved behind `compat.teamsapi.legacy-power-totals: true` |
| **EzCountdown** | façade (no imports) + reflectively loaded typed impl | relation announcements with duration/display-types; chat-broadcast fallback | notifications.yml keys preserved |
| **bStats** | shaded/relocated | charts: created/total factions, relation drilldown, database type | metrics read snapshots only |
| **Update checker** | own module, async | Modrinth primary (paper/folia/spigot loaders) + GitHub fallback; op join notice | |

---

## 11. Build pipeline — the Mental recipe, adapted

**Kept verbatim** (it is proven): Gradle wrapper 9.5.1 + foojay resolver 1.0.0; shadow
`com.gradleup.shadow` 9.4.2; jvmdowngrader 1.3.6; run-paper 3.0.2; toolchain JDK 25 (derived
`max(jdk)` from the matrix), `options.release = 17`, `-parameters`, UTF-8; version only in
`gradle.properties`; shadowJar staged as `-modern` into `build/jvmdg-stage` (never
`build/libs`), **no `minimize()`**; `DowngradeJar` with `downgradeTo=VERSION_1_8`,
`multiReleaseOriginal=true`, **never** `multiReleaseVersions` (1.3.6 mutual-exclusion
gotcha); downgrade classpath = union of core + compat-modern + compat-folia compile
classpaths; `ShadeJar` with `shadePath = "dev/fablemc/factions/lib/jvmdg/"` emitting the
canonical jar into `build/libs`; `failOnJvmdgWarnings` capture with the known mitigations
(`mustRunAfter(":api:apiCompat")`, Unsafe-note filter); tester jar with distinct prefix
`dev/fablemc/factions/tester/lib/jvmdg/`; verification gates `verifyDowngrade` (MR shape:
base ≤ 52, versions/17 == 61 subset, sentinel class `boot/FableFactionsPlugin` forked,
no-jvmdg-ref-in-base-only rule, H4 record-reflection scan), `verifyJdk8Api` (ASM gate vs a
real foojay-provisioned JDK 8 `rt.jar`, **empty allowlist policy**), `verifyRelocation`,
`verifyTesterIsolation` (incl. the stub-in-descriptor scan); plugin.yml `${version}` /
`${apiVersion}` expanded from `support-matrix.json`'s `floorApi`; integration matrix with
per-entry JDK launchers, freshness nonce, disabled watchdog, and the D-9 log scan; japicmp
`apiCompat` with a real classpath.

**Changed, and why:**

| Change | Why |
|---|---|
| Compile floor artifact 1.17.1 → **spigot-api 1.13.2** | Factions' listener surface is ancient/stable; a 1.13 floor makes 1.13–1.16 a zero-probe band and confines probing to 1.7–1.12 + modern extras (version-deltas §3.17 recommendation). The MRJAR shape is unaffected. |
| Relocation set: drop PacketEvents; add **H2, MySQL-connector, HikariCP** under `lib/` (+ SPI service files excluded, explicit `driverClassName`) | This plugin ships its own storage stack; unrelocated drivers collide with other plugins'. `verifyRelocation` scans `net/kyori`, `com/zaxxer`, `org/h2`, `com/mysql` tokens. |
| **New gate: `verifyDescriptorFloor`** + `verifyNoStickyGetstatic` (§3.5) | Mental's floor == compile floor, so javac caught sub-floor symbols; ours runs **below** the compile floor (1.7.10 < 1.13), so the compiler cannot catch them — the gate restores compile-time safety against the runtime floor. This is the load-bearing new invention of this build. |
| **New gate: `verifyTextBoundary`** | Generalizes verifyRelocation into the Component-boundary architecture rule. |
| **New task: `reportCompatFootprint`** | Counts classes per compat module against a checked-in budget file; growth without a budget bump fails — keeps the seam minimal by force. |
| Matrix extended to 1.7.10/1.8.8 (Java 8 lanes) | The commission's floor. 1.7.10 runs the v52 base natively on Java 8 — no flags, no separate build. The two 1.7.10 UNCERTAINs (terminal-build `getOnlinePlayers` descriptor; MR-loader behavior on newer JVMs) are resolved by the matrix itself before release. |
| Adventure version pinned to the newest 4.x whose MiniMessage we ship (shaded), not to a Paper floor BOM | We never hand our Components to Paper (JSON bridge only), so BOM identity with the server is unnecessary; the shaded copy is fully private. |

Task chain: `processResources → shadowJar(-modern, folds compat) → DowngradeJar(-downgraded)
→ ShadeJar(canonical) → build`; `check` depends on `verifyDowngrade, verifyJdk8Api,
verifyRelocation, verifyTesterIsolation, verifyDescriptorFloor, verifyNoStickyGetstatic,
verifyTextBoundary, apiCompat, reportCompatFootprint` + all unit/arch tests.

---

## 12. Feature → module mapping (requirement-1 proof)

Every behavior documented in the `pvp-*.md` specs has a home. (K = `:kernel` pure logic,
R = Realm command/state in `:core.realm`, L = listener in `:core.listen`, S = StorageActor,
P = port/platform, I = `:core.integration`, G = `:core.gui`.)

| Reference feature (doc) | FableFactions home |
|---|---|
| Faction create/disband/rename/desc/motd (`commands-core` 7.1–7.10) | `CreateFaction`/`DisbandFaction`/… RealmCommands (R) + `NamePolicy` (K); create/disband fire API events; predefined seeding hook |
| Membership: invite/join/leave/kick/promote/demote/leader (`commands-core`, `commands-misc` §3) | `MembershipCommands` (R); rank stepping **sorted by priority** (K, fixes L-18); kick resolves offline UUIDs via own name cache (fixes L-20); invite TTL 72 h pruning (K) with accept = one atomic command re-checking the invite (fixes C-21) |
| Roles: list/create/rename/setpriority/setprefix/delete/assign; roles.yml gates; builtin protection; TeamsAPI mirroring (`commands-admin` §5) | `RoleCommands` (R) + `RankAuthority` (K) + `RoleChangeNotifierHolder` (I) |
| Relations: set/list/wishes; mutual promotion; ENEMY/NEUTRAL mirroring; ally/truce caps; announcements (`commands-admin` §6) | `SetRelation` command (R) over directed-wish state; effective `RelationTable` (§4.4); `RelationAnnouncer` → EzCountdown/chat + DiscordSRV (I) |
| Claims: single/auto/square/circle/fill/nearby/at; unclaim variants incl. all; `land.max-per-command`; adjacency; overclaim + require-enemy + raidable check + F5 offline protection + F6 war shield; orphan-claim cleanup (`engines` §3.2, `services` §6) | `ClaimChunks`/`UnclaimChunks` batched RealmCommands (R); `ClaimRules` (K): border validity (4-neighbor rule), `computeMaxLand` with F1 inactivity exclusion (integer truncation preserved), buffer-zone now enforced, overclaim policy configurable adjacency (default preserves reference; `overclaim.require-border: false`) |
| Power: regen on/off-line, death loss, kill gain, F2 streak, F3 kill scaling, F1 inactivity, F4 raidable transitions + broadcasts, zones/world multipliers, freeze, min/max/event clamp, grace period, buy, admin set/add/remove/reset/freeze, history (`engines` §3.7/3.11, `commands-admin` §3) | **One** `PowerMath.apply` pipeline (K) — caller-computed effective deltas flow through (fixes L-1); event clamp gated to automatic sources (fixes L-2); tick = `PowerTick` Realm command (§5e); history appends via journal (S); `/f power buy` = reserve→apply→settle (fixes L-9/C-11) |
| Bank: deposit/withdraw/transfer/history; tax scheduler with min-balance/min-charge/rounding; transaction records; member notifications + inbox (`engines` §3.8) | `BankCommands` + `TaxTick` (R); `MoneyMath.round2` (K); Vault compensation on failure (fixes C-12/L-3); withdraw rank-gated by configurable min role, default officer (fixes L-15); events pre-commit (fixes C-22) |
| Team chests: create/delete/list/open, default chest auto-create, 54 slots, cap (`commands-misc` §2) | `ChestDirectory` (R): **one live shared Inventory per (faction, chest)** with viewer refcount; persist on mutation-close through the current engine; Folia single-viewer policy; version-tagged blobs via ItemCodecPort (fixes C-7/C-15/L-8) |
| Warps: set/delete/list/password/cost, cap, teleport (`commands-misc` §5) | `WarpCommands` (R); placement restricted to own claims + revalidated at use (fixes L-12); cost charged in the success callback (fixes L-14) |
| Home/sethome/unsethome + `/f home` (`commands-core` 7.12–7.14) | `HomeCommands` (R); **first-party warmup (cancel on move/damage), cooldown, combat-tag** in `TeleportPipeline` (K timer math + `repeatOn` with retired cleanup) — Essentials optional (fixes L-13); Y clamped to WorldPort heights |
| Fly (`commands-core` 7.15) | persisted fly state; enable check + continuous move-driven re-evaluation with threat radius (`fly.disable-on-threat` now real); fall-damage immunity grant on disable (fixes L-16/C-14) |
| Map `/f map on/off/once --size` chat grid (`commands-core` 7.28) | `MapRenderer` (K glyph grid over read plane) + clickable cells (G/text) |
| Info/list/top + pagination + detail pages (`commands-core` 7.25/7.26/7.29) | `FactionProfile` snapshots (D2) + async sorting on D4 |
| Notify prefs (invites/territory/tax/motd) (`commands-core` 7.27) | PlayerAffinity flags + persisted columns |
| Language `/f language` + per-player locale + GUI (`commands-core` 7.31) | MessageCatalog + `LanguageCommands` (R) + language menu (G) |
| Help (3 pages, sections, perm-gated) (`commands-core` 7.32) | `HelpRenderer` over the declarative `CommandTreeSpec` |
| GUI: gui.yml menus, items, placeholders, 7 actions, locale visibility, main+language menus (`commands-misc` §7) | `GuiModel` (config) + `MenuEngine` (G) over GuiPort/MaterialPort; clicks matched by rawSlot, always cancelled; MenuHolder identification |
| Audit log `/f audit`, `/fa audit`, all 18 action ids (`services` §1.3/7.6) | `AuditWriter` via journal (S, async — fixes audit-on-caller-thread) |
| Inbox / join notifications / MOTD / invite summaries (`engines` §3.9) | `InboxService`: deliver-then-delete **by id set**, cap by reading (fixes C-10); `FactionMemberNotifier` split online/inbox preserved |
| Admin: bypass, claim/unclaim shapes, safezone/warzone shapes+remove, disband, reload, shield (UTC wrap window math), flag, power group, help (`commands-admin` §2) | `AdminCommands` (R); `ShieldMath.isActive` (K, pinned); reload = snapshot swap + feature convergence |
| Predefined factions: pre-defined.yml, create/claim/sethome/list/reload, seeding, disband block (`commands-misc` §6) | `PredefinedModel` (config) + `PredefinedCommands` (R) |
| Merge: send/accept, full migration semantics (`commands-misc` §4) | `MergeFaction` RealmCommand (R) — **now enforcing member/land caps** with configurable `merge.enforce-caps: true` (fixes L-11; reference behavior available via toggle) |
| Chat tag (Paper renderer / legacy format) (`engines` §3.6) | ChatModelPort (§3.3) + tag cache (§5d) |
| Territory-enter titles/hover info (`engines` §3.5) | consolidated move listener (§5a) + profile snapshots |
| Flags (pvp/friendly-fire/explosions/fire-spread/open) + `/f flag`, `/fa flag`, defaults + player-editable config (`commands-admin` §4) | flag bitsets in FactionDirectory; `FlagCommands` (R) |
| Zones: safezone/warzone semantics everywhere (`engines` §3.1) | reserved faction ids 1/2 in ClaimTable; zone bits in AccessEngine |
| All integrations (`integrations` §1–8) + bStats + update checker | §10.2 table |
| All config keys (`resources` §1–5) & message keys (§9) & permissions (§6.2) | ConfigSnapshot sections / MessageCatalog / plugin.yml permission tree (verbatim node set) |
| Scheduler semantics (`engines` §1) | SchedulePort (§3.3) — same contract, retired-callback fixed |
| Data-layer semantics (`pvp-data`) | §6 (projection model; import path for reference DBs) |

*(Redesigned HOW, never dropped WHAT: rows above marked "fixes L-x/C-x" change behavior only
where the bug catalogs mandate it; each has a config escape hatch when the old behavior was
plausibly load-bearing for server balance.)*

---

## 13. Testing strategy

1. **Kernel pins** (plain JUnit, no Bukkit): every formula in the appendices —
   power clamp pipeline (all 9 sources × freeze × multipliers × clamps), death-streak
   escalation (`loss·mult^streak`, window edges), kill scaling clamp, `computeMaxLand`
   truncation + inactivity, border validity truth table (all 16 neighbor combinations),
   raidable `>` vs claim-block `>=`, tax rounding/min-balance/min-charge, shield UTC
   midnight wrap, invite TTL, MoneyParser suffixes (`k/m/b/t`, negatives), name allowlist,
   rank stepping by priority, buffer-zone geometry, effective-relation promotion/mirroring
   state machine, `AccessEngine.decide` exhaustive matrix.
2. **Parse-equality tests**: empty config == `DEFAULTS` for every section record.
3. **Architecture tests** (ArchUnit + bytecode scans in `check`): kernel classpath purity;
   `java.sql` confined to `persist`; listeners depend on read-plane only; no
   `Bukkit.getScheduler` outside `BukkitSchedulePort`; no `HandlerList` outside
   Loadout/Scope; compat modules import no domain packages; ports module is interfaces-only;
   `net/kyori` token confined (verifyTextBoundary); descriptor-floor + sticky-getstatic
   gates (§3.5).
4. **Concurrency**: jcstress suites for `ClaimTable`/`RelationTable` (single-writer publish,
   reader acquire, resize race); deterministic Realm replay tests (same command log ⇒ same
   state hash); property tests generating random command interleavings asserting invariants
   (land ≤ maxLand unless raidable-transition, member count ≤ cap, bank ≥ 0, no orphan
   claims, relation symmetry of effective table).
5. **Scheduling TCK**: retired-callback contract (exactly-once, either-thread,
   may-be-immediate) run against `BukkitSchedulePort` in a fake server and against
   `FoliaSchedulePort` in the Folia matrix lane.
6. **StorageActor tests**: coalescing correctness (last-value-wins per key), criticality
   flush, drain barrier under injected slow JDBC, H2↔MySQL dialect parity via testcontainers
   (MySQL) + embedded H2 running the identical suite.
7. **Reload torture**: 100× `/fa reload` in the tester ⇒ handler-list counts and task counts
   unchanged; event handled exactly once (nonce-counted).
8. **Integration matrix** (run-paper, per support-matrix entry): boots the real server on the
   entry's JDK, installs the mega jar + tester, runs suites (`full` = commands + protection
   vectors + GUI clicks + chat + power tick fast-forward; `claims-smoke` on Folia), asserts
   `-Dfable.tester.tier` == loaded bytecode tier, echoes the per-run nonce, and applies the
   **D-9 console-swallow scan** — a green run containing a swallowed listener-registration
   failure, a per-event handler throw, or a linkage error framed by `dev.fablemc.factions`
   is a FAIL.
9. **Allocation budget test**: JFR/allocation-instrumented run of 10 k synthetic move events
   + 10 k protection decisions asserting 0 bytes allocated on the ALLOW paths (epsilon for
   JIT warmup).

---

## 14. How each catalogued bug class is made impossible

**Concurrency catalog (`pvp-bugs-concurrency.md`):**

| Bug(s) | Structural kill |
|---|---|
| C-1 full-row clobber, C-2 power RMW, C-4 bank RMW, C-8 two-copy streak, C-16 relations JSON RMW | **There is no read-modify-write anywhere.** One writer thread owns the only copy of the truth; storage receives absolute values that writer computed; "two independently loaded copies of a row" cannot exist because rows are never loaded after boot. The streak update and the power delta are one kernel decision applied in one `state.apply`. |
| C-5 lock removed in finally, C-6 land-cap TOCTOU, C-17/L-17 lock striping, C-18 member-cap TOCTOU, C-21 invite TOCTOU, L-11 merge caps | **No locks exist to misuse.** All check-then-act pairs (cap check + insert, invite check + join, overclaim checks + claim) are one deterministic `Realm.execute` step on one thread. Aggregate invariants are property-tested over random command interleavings. |
| C-7/L-8 chest dupe | One live shared `Inventory` per (faction, chest) with viewer refcounting; there is no per-player snapshot copy to diverge. Persist-on-close routes through the *current* ChestDirectory (scoped, so never a leaked pre-reload instance). Folia policy: single viewer per chest (regions can't safely share a live inventory). |
| C-3 reload listener leak, C-15 stale chest engine | **Scoped features**: every listener/task/resource is acquired through a `Scope` (AutoCloseable bag); reload = reconciler converges scopes (close removed, open added, reverse-order close, failures suppressed-collected). Registering a listener outside a Scope fails an ArchUnit rule; the reload-torture test (§13.7) pins it. |
| C-9 main-thread JDBC (incl. per-block explosion queries) | **Unreachable by construction**: `java.sql` is import-legal only in `persist`; listeners may only depend on the read plane (ArchUnit); explosion handling is O(distinct chunks) against `ClaimTable` (§5c). |
| C-10 inbox delete-before-deliver | InboxService reads ≤ cap rows, delivers, then deletes exactly the delivered id set. |
| C-11 charge-then-ignore, C-12 deposit money vanish, L-9 buy shortfall, L-14 warp fee | **Reserve → apply → settle** is the only external-money shape: the kernel decision computes the exact applicable effect first; Vault is debited only for `result.effectiveDelta`; any post-debit failure path runs the declared compensation. There is no code path that debits before the decision because commands cannot call Vault directly (EconomyBridge is only reachable from the settlement stage of the pipeline). |
| C-13 in-flight writes vs pool close | The shutdown barrier (§6.2) is one ordered function: intake stop → realm drain → chest flush → journal `drainAndFlush` → pool close; a test kills the server mid-combat load and asserts zero lost CRITICAL mutations. |
| C-14 fly map leak/wipe | Fly is a persisted PlayerAffinity field; per-player runtime state lives in one `PlayerLocal` evicted by the join/quit pair inside the same Scope. |
| C-19 async Bukkit API touches | Bukkit API is reachable only through ports; `PlayersPort.forEachOnline` and message sends assert/route to correct threads (`runOn`/global). Async domains (D3/D4) receive snapshots and port-mediated effect handles only. |
| C-20 Folia timer delay 0 / wrong-region fallback | `SchedulePort` clamps delays ≥ 1 tick; there is no "fallback to global" for entity work — `runOn` honors the retired contract instead. |
| C-22 transfer event after commit | All bank events fire pre-commit on the Realm thread and honor cancellation uniformly. |
| C-23 dead `last_activity` | Written on join, quit, and a 5-minute heartbeat batch; a kernel test asserts the inactivity exclusion actually excludes. |

**Logic catalog (`pvp-bugs-logic.md`):**

| Bug(s) | Structural kill |
|---|---|
| L-1 DEATH/KILL delta overridden | `PowerMath` computes the final effective delta in exactly one function; event-driven sources pass the computed value through — there is no second derivation site to disagree. Pinned: a test enables streak + scaling and asserts escalated losses land. |
| L-2 clamp hits ADMIN/BUY | Event clamp applies iff `source.isAutomatic()` (enum property); admin/buy bypass. Test per source. |
| L-5 projectile PvP bypass | True-attacker resolution (§5b) feeds the same decide() as melee; matrix test shoots arrows/potions/tridents into safezones. |
| L-6 missing protection vectors | Event-complete matrix (§7.1) — every vector routes through one `decide`; the tester's protection suite exercises each vector per matrix version. |
| L-7 MiniMessage injection | User text enters templates only as literal placeholder nodes + intake allowlist (§9); a test creates `<click:run_command:'/f leave'>x` and asserts inert rendering. |
| L-10 overclaim adjacency + dead buffer zone | Border and buffer rules applied uniformly in `ClaimRules` for **all** paths (single/shape/auto/overclaim/admin); buffer implemented; overclaim adjacency configurable, default documented. |
| L-11 merge bypass | Merge routes through the same `ClaimRules`/membership invariants (toggleable strictness). |
| L-12 arbitrary warp/home coords | Placement requires standing inside own claim (coordinate form admin-only); use-time revalidation relocates/denies on ownership change. |
| L-13 no warmup/combat gate | First-party `TeleportPipeline`: warmup (cancel on move > 1 block / damage), per-command cooldowns, combat tag (blocks `/f home`, `/f warp`, fly) — defaults on. |
| L-15 member drains bank | Withdraw/transfer gated by configurable minimum role (default officer), deposit open. |
| L-16 fly fall damage / stale eligibility | Fall-immunity grant on any fly revoke (one-shot damage-cancel token); move-driven re-eval + enemy-proximity radius. |
| L-18 rank order | Priority-sorted stepping in kernel. |
| L-19 null getTo | Guarded (§5a). |
| L-20 offline kick | Own name↔UUID cache (join-fed) + UUID-based service path. |
| L-21 one-sided ALLY unlock | Effective `RelationTable` stores mutual relations only — a one-sided ALLY cannot exist where protection reads. |
| L-22 divergent max-power keys | One canonical tunable + alias fold with warning (§8). |

---

## 15. What is novel here — stated as testable properties

1. **The frozen monomorphic seam.** Every port has *at most one* implementation class loaded
   per JVM; era selection happens once, by FQN string, behind probes. *Test:* boot self-check
   walks the loaded-class list per port interface and fails if ≥ 2 implementors are loaded;
   the compat-footprint report caps adapter class counts (`:compat-folia` = 1,
   `:compat-modern` ≤ 12).
2. **Descriptor-floor build gate.** Because the runtime floor (1.7.10) is *below* the compile
   floor (1.13), javac cannot catch sub-floor symbols — `verifyDescriptorFloor` +
   `verifyNoStickyGetstatic` re-create compiler-grade safety against a checked-in 1.7.10
   symbol table, turning the "listener silently registers zero handlers" class into a build
   failure. *Test:* the gates run in `check`; a seeded violation fixture fails CI; the D-9
   log scan backstops at runtime.
3. **Era-blind business logic, provable.** Zero domain/persistence/config code in compat
   modules; kernel is Bukkit-free by dependency-resolution failure; ports are
   interfaces-only. *Test:* the ArchUnit suite of §13.3 — the claim is exactly the set of
   rules that fail the build when violated.
4. **Storage as a projection of a single-writer realm.** No read-modify-write exists in the
   program: one thread computes all state transitions; SQL receives absolute values; the DB
   is never read for authority after boot. *Test:* deterministic replay (same command log ⇒
   same state hash), random-interleaving property tests, kill-mid-combat persistence test,
   and the `java.sql`-reachability architecture rule.
5. **Wait-free O(1) hot paths with a zero-allocation budget.** Claim lookup = 1–2 array
   probes on a packed-long open-addressed table; relation = one packed-pair probe; protection
   decision = one static primitive-arg function; chat tag = pre-rendered cache hit. *Test:*
   the §13.9 allocation-budget run asserts 0 bytes/event on allow paths; jcstress pins the
   single-writer/many-reader memory semantics; capacity math (24 B/claim) makes "millions of
   claimed chunks" a stated RAM budget, not a hope.
6. **One deterministic mutation pipeline as the bug-class eraser.** Sections §14's two tables
   each reduce to one of five mechanisms (single writer, scoped lifecycles, reserve→settle,
   read-plane-only listeners, canonical tunables) — every catalogued bug maps to a mechanism
   with a named test, not to a code-review promise.
7. **The seam's cost is measured, not asserted.** The boot report prints every resolved
   handle and fallback; the tester asserts the loaded bytecode tier per matrix entry; the
   compat census is a build artifact. A reviewer can diff "what varies per era" as a number.

---

## 16. Risks & consciously deferred items

- **1.7.10 UNCERTAINs** (terminal build's `getOnlinePlayers` descriptor, BaseComponent
  presence, `getClickedInventory`, MR-loader behavior on modern JVMs): all are probed, not
  assumed; the matrix's 1.7.10 lane is the arbiter. Worst case (no Bungee chat lib): the
  FlatTextPort path is the declared floor — features degrade to plain text, boot-reported.
- **Folia shared chest inventories**: single-viewer policy on Folia is a deliberate
  functional narrowing (documented; config `chest.single-viewer: auto`).
- **RAM for extreme claim counts**: 5 M claims ≈ 120 MB read plane. Documented as a sizing
  line; a paged/off-heap ClaimTable is a compatible future swap behind the same read API.
- **Bounded crash-loss window** for LAZY mutations (≤ flush interval, default 100 ms;
  CRITICAL is immediate). A WAL was considered and rejected for v1 (complexity vs a 100 ms
  regen-loss window); the journal API admits a WAL consumer later without touching the Realm.
- **jvmdg 1.3.6 pin**: the pipeline's behavior notes are version-specific; upgrading jvmdg
  requires re-running the full matrix (called out in the build README).
