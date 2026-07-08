# Bukkit/Spigot/Paper API deltas 1.7.10 → 26.1.2 — the factions surface

**Purpose.** Decision-ready inventory of every API delta a factions plugin touches
across PaperSpigot 1.7.10 → Paper/Folia 26.1.2 (the 2026 versioning), for a rewrite
that ships **ONE jar** using a platform seam + boot-time probing (Mental-style).

**Grounding.**
- `/Users/owengregson/Documents/StrikeSync/.claude/skills/paper-cross-version/SKILL.md`
  (Mental's proven cross-version model: 1.9.4 → 26.1.2, one MRJAR, no flags)
- `/Users/owengregson/Documents/StrikeSync/support-matrix.json` (`_comment` = the
  live-probed Java/bytecode-tier ground truth, probed 2026-07-03)
- `/Users/owengregson/Documents/StrikeSync/docs/superpowers/research/2026-07-02-legacy-boot-viability.md`
  (empirical boot ladder 1.9.4–1.16.5 on modern JVMs)
- `/Users/owengregson/Documents/StrikeSync/docs/superpowers/plans/2026-07-03-mental-full-range.md`
  (loader MR-awareness map, jvmdg spike results, `SUPPORTED_API` probe on 1.14.4)
- Own knowledge of Bukkit history for everything below 1.9.4 and above the Mental
  surface. **Anything not verifiable from the grounded docs that carries doubt is
  tagged `UNCERTAIN` — verify before relying on it. Untagged claims are
  high-confidence.**

**Terminology.** "Probe + fallback" = resolve the class/method/enum-constant ONCE at
boot (Class.forName / MethodHandles / Enum.valueOf in try-catch), cache the result,
print one loud boot line on a miss, install the era-intent fallback. Never parse
version strings for behavior when a capability probe works. "Compat module" =
classes compiled against a newer API, only classloaded behind a successful probe.

---

## Part 1 — The big matrix

| Surface | 1.7–1.8 | 1.9–1.12 | 1.13–1.16 | 1.17–1.20 | 1.21+/26.x | Seam strategy |
|---|---|---|---|---|---|---|
| `Bukkit.getOnlinePlayers()` | `Player[]` on stock 1.7.10; late Spigot/Paper 1.7.10 protocol-hack builds may already return `Collection` (UNCERTAIN per build). 1.8+ = `Collection<? extends Player>` | Collection | Collection | Collection | Collection | MethodHandle probe of BOTH descriptors at boot; all call sites go through `platform/Players.online()`. Never call directly from floor-compiled code |
| Material model | Numeric IDs + data bytes; `WOOL`+dye data, `BED`, `INK_SACK`, `SKULL_ITEM` | same (legacy names) | **1.13 flattening**: namespaced names, `WHITE_WOOL`…, `matchMaterial(String, legacy)` added, `LEGACY_*` constants exist 1.13+ only | modern names | modern names; registry growth only | Kernel speaks MODERN names only; single `LegacyMaterials` seam maps modern→(legacy enum name, data byte) via static table; one boot flattening probe (`Material.getMaterial("WHITE_WOOL") != null`) |
| GUI icon ItemStacks | `new ItemStack(WOOL,1,(short)data)` | same | `Material.valueOf(color+"_WOOL")`; durability moved to `Damageable` meta | same | same | Icon factory behind the material seam; data-byte constructor only in legacy branch |
| UUIDs / OfflinePlayer | UUIDs live since 1.7.6 accounts; `getOfflinePlayer(UUID)` present 1.7.9+; `Bukkit.getPlayer(UUID)` present in 1.7.10-era API (earliest builds UNCERTAIN) | stable | stable | stable | stable | Store members by UUID from day one; own name↔UUID cache fed by `PlayerJoinEvent`; probe `getPlayer(UUID)` with linear-scan fallback; NEVER `getOfflinePlayer(String)` on main thread |
| Chat event | `AsyncPlayerChatEvent` (since 1.3) + `setFormat` | same | same | same; Paper `AsyncChatEvent` (Adventure) from **1.16.5** | both; legacy event deprecated but still fires on Paper; 1.19.1+ signed chat semantics | Baseline listener = `AsyncPlayerChatEvent` everywhere; optional compat-modern `AsyncChatEvent` renderer module behind probe (separate listener class!) |
| Hover/click text | 1.7.10: no Spigot BaseComponent (bundled from **1.8**; late 1.7.10 builds UNCERTAIN); `/tellraw` exists 1.7.2+ | `net.md_5` BaseComponent + `Player.spigot().sendMessage` | same | Adventure native Paper 1.16.5+ | Adventure native | Text seam: MiniMessage → shaded+relocated Adventure Component → native audience (probe) → BungeeComponent (probe) → flat legacy § string |
| Titles | Client protocol 1.8+ (**1.7 clients can't render titles**); no Bukkit API — NMS `PacketPlayOutTitle` only | NMS until `sendTitle(String,String)` ~1.11 and 5-arg timed overload ~1.11.2 (exact minor UNCERTAIN) | `sendTitle` 5-arg | `sendTitle` / Adventure `showTitle` | same | Probe 5-arg `sendTitle` → 2-arg → versioned-NMS packet (1.8–1.10) → chat-line fallback (1.7) |
| ActionBar | none (protocol 1.8+); NMS chat-position-2 packet on 1.8 | NMS; Spigot `sendMessage(ChatMessageType.ACTION_BAR,…)` from **1.12**; Paper `sendActionBar` earlier (UNCERTAIN which build) | Spigot API | Adventure `sendActionBar` | same | Probe chain: Adventure → Spigot ChatMessageType → NMS packet → skip |
| BossBar | none (wither-hack only — do not ship) | `Bukkit.createBossBar` from **1.9** | + `KeyedBossBar` ~1.13 | Adventure BossBar | same | Probe `createBossBar`; below 1.9 degrade to scoreboard/actionbar/nothing |
| Scoreboard/Team | API since 1.5.2. **16-char prefix/suffix hard limit (IAE)**; `Team.addPlayer(OfflinePlayer)` only (addEntry ~1.8.x, UNCERTAIN) | 16-char limits; `addEntry(String)`; `Team#setColor` ~1.12 (UNCERTAIN) | **1.13: limits raised to 64**; name glyph color needs `setColor` (prefix color no longer bleeds) | stable | components, effectively unlimited | Central `Nametags`/`Boards` seam: truncate-to-16 on pre-1.13 probe; color via prefix-tail pre-1.13, `setColor` after |
| GUI: InventoryHolder pattern | works | works | works | works | works — BUT `InventoryView` **class → interface in 1.21** (ICCE both directions at plugin call sites) | Custom-holder identification everywhere (universal); ZERO direct `InventoryView` method calls in shared code — route via MethodHandle (`findVirtual` resolves both shapes) or use `event.getInventory()` / rawSlot math |
| `createInventory` titles | String | String | String | String; Paper Component overload 1.16.5+ | both | Use String overload universally (still fine on 26.x); Component only in compat-modern |
| Off-hand | n/a | **1.9**: `EquipmentSlot.OFF_HAND`, slot 40, `getItemInMainHand/OffHand`, `PlayerSwapHandItemsEvent` | present | present | present | `Hands` resolver (Mental has one): probe `getItemInMainHand`, fallback `getItemInHand` |
| Item pickup event | `PlayerPickupItemEvent` | same; **1.12** adds `EntityPickupItemEvent` (old one deprecated) | both exist; which fires for players on modern is UNCERTAIN | both exist | both exist | Register legacy listener always + probe-register Entity variant in separate class; dedupe |
| Explosion events | `EntityExplodeEvent` universal; `BlockExplodeEvent` from **1.8.3** (absent 1.7.10/1.8.0–1.8.2) | both | both (bed/anchor griefs fire BlockExplode on modern) | both | both | EntityExplode listener in core; BlockExplode in probe-registered separate class |
| ArmorStand / interact-at | **1.8**: `ArmorStand`, `PlayerInteractAtEntityEvent` | present | present | present | present | Probe-registered listener class; 1.7.10 has neither (skip silently is fine — nothing to protect) |
| Mount events | `org.spigotmc.event.entity.EntityMountEvent` (present in Spigot 1.8; 1.7.10 UNCERTAIN) | spigotmc package | spigotmc package | spigotmc package; **1.20.3 moves to `org.bukkit.event.entity`** | bukkit package; spigotmc variant removed on newer Paper (removal version UNCERTAIN) | Dual Class.forName probe; register whichever exists (two listener classes) |
| Chunk/claim math | `Chunk.getX/getZ`, `Location` `>>4` arithmetic — stable forever | stable | stable | stable | stable | Pure arithmetic in kernel; compute own chunk key `((long)z<<32)\|(x&0xFFFFFFFFL)` — never depend on Paper `Chunk#getChunkKey` |
| Chunk load/unload control | sync `getChunkAt`; unload cancellable | same | `getChunkAtAsync` Paper ~1.13 (PaperLib pattern); `setChunkForceLoaded` 1.13.2; **1.14 removes ChunkUnloadEvent cancellation** | async APIs | async APIs; Folia: region-thread rules | Never load chunks for claim checks (pure math); probe async APIs for the rare true chunk access |
| World height | 0–256 | 0–256 | 0–256 | **1.18: overworld −64…320**; `World#getMinHeight` ~1.17 (1.16.5 backport UNCERTAIN) | −64…320 | Probe `getMinHeight`, fallback 0; NO hardcoded Y assumptions in claim/home logic |
| Scheduling | `BukkitScheduler` | same | same | same; `teleportAsync` Paper 1.13+ | same on Paper; **Folia: BukkitScheduler throws UOE**; Region/Entity/GlobalRegion/Async schedulers (Paper API ~1.20+, UNCERTAIN exact); Folia in-matrix at 26.1.2 | `Scheduler` facade; Folia detect via `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`; compat-folia module; `folia-supported: true` in plugin.yml |
| Item/entity marking | lore steganography / transient metadata only; custom NBT = NMS | + scoreboard tags ~1.11 (persistent, UNCERTAIN exact minor) | **1.14: PersistentDataContainer** (item meta + entities); `NamespacedKey` lands **1.12** | PDC everywhere | PDC everywhere | PDC behind probe (NamespacedKey is sub-1.12 — descriptor hazard!); legacy fallback = §-encoded lore marker + scoreboard tags |
| plugin.yml | no `api-version` key (ignored by old loaders) | same | **1.13 introduces `api-version`**; absence on 1.13+ = Commodore legacy shim; server refuses api-version NEWER than itself; 1.14.4 `SUPPORTED_API=["1.13","1.14"]` (javap-verified) | accepted | accepted | Declare **`api-version: '1.13'`** — ignored below 1.13, avoids the shim on 1.13+, loads everywhere up to 26.x. Do NOT use paper-plugin.yml (1.19.3+ only) |
| Text stack | legacy § codes only | § codes; BungeeComponents | § + hex `§x` from **1.16** | Adventure native Paper 1.16.5+ | Adventure native | MiniMessage config strings → shaded+RELOCATED Adventure → probe native audience / downsample hex below 1.16 |
| Vault | API stable since 2011 | stable | stable | stable | stable | soft-depend + ServicesManager lookup behind Class.forName guard; use OfflinePlayer overloads (Vault 1.4+), never name-based |
| Commands | plugin.yml + `PluginCommand` + `TabCompleter` (since ~1.1) — all present 1.7.10 | same; Paper `AsyncTabCompleteEvent` from ~1.12 | same; Brigadier exists server-side 1.13+ | same; **Paper Brigadier lifecycle API 1.20.6+** | same | plugin.yml commands + TabCompleter as universal core; optional compat-brigadier module (Mental precedent) |
| Fly/gamemode | `setAllowFlight/setFlying/setFlySpeed` universal; **`GameMode.SPECTATOR` absent pre-1.8** | all present | all | all | all | Direct calls OK; SPECTATOR via boot-time `Enum.valueOf` (sticky-getstatic hazard on 1.7.10) |
| Vanish | `hidePlayer(Player)` | same | `hidePlayer(Plugin,Player)` 1.12.2+ | same | same | Probe new overload, fallback old |
| Potions | pre-1.9 data-value potions | **1.9**: `SPLASH_POTION`/`LINGERING_POTION` materials, `PotionData`, `LingeringPotionSplashEvent`, `AreaEffectCloudApplyEvent` | same | same | 1.20.5 deprecates PotionData for PotionType | `PotionSplashEvent` (universal) for claim combat rules; lingering behind probe |
| Pearls/teleport causes | `TeleportCause.ENDER_PEARL` universal | + `CHORUS_FRUIT`, `END_GATEWAY` 1.9 | same | same | same | Universal listener; post-floor causes via Enum.valueOf |
| Signs | `SignChangeEvent` universal | same | same | same | **1.20: SignSide API, editable signs, per-side events** | Legacy `getLine/ setLine` still works; side-aware handling only in compat-modern if needed |
| Raids | n/a | n/a | game raids **1.14**; Bukkit Raid API/`RaidTriggerEvent` ~1.15 (UNCERTAIN) | present | present | Probe-registered listener class to cancel raids in claims |
| Elytra | n/a | **1.9** elytra; `EntityToggleGlideEvent` ~1.10 (UNCERTAIN) | present | present | present | Probe-registered; interacts with fly-in-territory rules |
| NMS/reflection (if any) | versioned packages `net.minecraft.server.v1_7_R4/v1_8_R3`, spigot-mapped | versioned, spigot-mapped | versioned, spigot-mapped | 1.17 drops versioned packages; still spigot-mapped | **1.20.5: runtime becomes Mojang-mapped** (reflection-remapper needed); **1.21.3: Attribute enum→interface**; 1.20.5 Enchantment renames | Avoid NMS entirely if possible in a factions plugin; if not, route via remapper + name-resolving platform classes (Mental's `Attributes`/`Enchantments` pattern) |
| Java runtime | 1.7.10/1.8: era JVMs Java 6–8; Java 8 boots (historical fact); Java 9+/17+ boot UNCERTAIN — probe; no Java guard in server | 1.9.4–1.12.2: **no Java guard, boot up to Java 25** (Java 21 = newest warning-free rung) [measured] | 1.13.2→Java 13 cap; 1.14.4→**hard** Java 13 cap (no bypass flag); 1.15.2→14; 1.16.5→16 [measured] | Java 17+ | Java 25 era | **MRJAR: v52 (Java 8) base tree + `META-INF/versions/17` v61** — exactly Mental's shape; declare Java 8 as host floor even for 1.7.10 |

---

## Part 2 — The 10 riskiest deltas (prose)

### 1. `Bukkit.getOnlinePlayers()` — the 1.7.10 binary break
The signature changed from `Player[]` to `Collection<? extends Player>` in the
Bukkit repo during the 1.7.9→1.8 window (mid/late 2014). Because Spigot kept
building 1.7.10 protocol-hack jars from the shared repo after the change,
**which signature a given "1.7.10" server carries depends on its build date** —
stock/early builds return the array, late Spigot/PaperSpigot 1.7.10 builds may
return the Collection (UNCERTAIN per build; must be probed against the actual
target jar). Compiling against the modern API bakes the `()Ljava/util/Collection;`
descriptor into every call site → `NoSuchMethodError` on array servers, and
vice-versa. **Seam:** one boot-time MethodHandle resolution that tries the
Collection descriptor, then the array descriptor (wrapping in
`Arrays.asList`), cached in `platform/Players`. Every iteration over online
players in the entire plugin goes through it. This is also why the plugin
cannot simply "compile against 1.7.10" — the modern floor + probe direction is
the only shape that works both ways.

### 2. `InventoryView` class → interface (1.21) — the silent GUI killer
In 1.21 Spigot changed `org.bukkit.inventory.InventoryView` from an abstract
class to an interface. Bytecode compiled against the old API uses
`invokevirtual`; against the new, `invokeinterface` — each throws
`IncompatibleClassChangeError` on the other side. A factions plugin is
GUI-heavy (claims map GUI, faction admin GUI, chest), and `event.getView().getTopInventory()`
is idiomatic — every such call site is a landmine that only detonates on one
half of the range. **Seam:** ban direct `InventoryView` method calls in shared
code (a checkstyle/ArchUnit-style gate is worth it). Use
`InventoryEvent#getInventory()`, `getWhoClicked()`, and rawSlot arithmetic
(`rawSlot < event.getInventory().getSize()` ⇒ top inventory) — these call sites
sit on stable classes. Where a view method is genuinely needed, resolve it via
`MethodHandles.lookup().findVirtual(...)` at boot: MethodHandles dispatch
correctly whether the runtime type is a class or interface. Note
`InventoryClickEvent#getClickedInventory` availability on 1.7.10/1.8 is
UNCERTAIN — probe it, fall back to rawSlot math.

### 3. Material flattening (1.13)
Numeric IDs and data bytes died; ~half the enum was renamed; colored blocks
exploded into per-color constants. Factions touches this in GUI icons
(wool/banner/bed icons per relation color), config-declared materials
(protected containers, deniable interactables), and any serialized ItemStacks.
`Material.matchMaterial(String, boolean legacyName)` and the `LEGACY_*`
constants exist **only on 1.13+** — they are not a legacy-side tool.
**Seam:** the kernel's material vocabulary is modern-only (Mental's
`LegacyMaterialNames` precedent, inverted: modern→legacy). One static table
maps the few dozen modern names the plugin uses to `(legacy enum name, data byte)`;
one boot probe (`Material.getMaterial("WHITE_WOOL") != null`) selects identity
vs. translation. GUI icon creation is the only place the data-byte ItemStack
constructor may appear.

### 4. The listener-descriptor + sticky-getstatic hazards (all legacy versions)
Ground truth from Mental (GAP 1/GAP 2, live-reproduced): (a) Bukkit's
`createRegisteredListeners` reflects over EVERY declared method of a listener
class; if any method/field descriptor mentions a type absent on this server
(`NamespacedKey` pre-1.12, `EntityPickupItemEvent` pre-1.12, `AsyncChatEvent`
pre-Paper-1.16.5, PDC types pre-1.14…), the WHOLE class registers zero handlers
— one swallowed SEVERE line, and e.g. all claim protection silently dies while
the rest of the plugin looks alive. (b) A direct `getstatic` of a sub-floor
enum constant (`GameMode.SPECTATOR` on 1.7.10, `DamageCause.ENTITY_SWEEP_ATTACK`
pre-1.11, new `TeleportCause`/`SpawnReason` constants) is a **sticky**
`NoSuchFieldError` re-thrown on every event. **Seam:** (a) any listener that
mentions a post-floor type lives in its own class, registered only behind a
probe; sub-floor-typed helpers are hoisted into non-Listener classes; (b) ALL
enum constants newer than 1.7.10 are resolved once at boot via `Enum.valueOf`
in try-catch into nullable fields. For a factions plugin whose value
proposition IS its protection listeners, this is the highest-severity failure
mode in the whole design; adopt Mental's console-log-scanning CI gate that
fails the build on either swallow signature.

### 5. `api-version` and the single jar
`api-version` was introduced in 1.13. Absence on a 1.13+ server triggers the
Commodore bytecode rewriter + `Material.LEGACY_*` remapping — which would
actively FIGHT the plugin's own modern-name strategy and boot-time probes.
Declaring a version newer than the server refuses to load
(`Unsupported API version`). Grounded fact: 1.14.4's
`CraftMagicNumbers.SUPPORTED_API = ["1.13","1.14"]` (javap-verified in the
Mental campaign) — `1.13` is accepted. Pre-1.13 loaders ignore the key
entirely. **Decision: `api-version: '1.13'`**, the unique value that loads
everywhere from 1.7.10 to 26.1.2 with no shim. Also set
`folia-supported: true` (ignored by non-Folia servers). **Do not** use
`paper-plugin.yml`: it loads only on Paper 1.19.3+ and forfeits the entire
legacy range and Spigot.

### 6. Java runtime / bytecode tiers — v52 base + v61 MRJAR, floor Java 8
Measured facts (support-matrix `_comment`, probed 2026-07-03): 1.9.4–1.12.2
have no Java guard and boot up to Java 25 (Java 21 = newest warning-free);
1.13.2 caps at Java 13, 1.14.4 **hard-caps** at Java 13 with no bypass flag,
1.15.2 at 14, 1.16.5 at 16; 1.17.1+ run Java 17+, modern entries Java 25.
The 1.14.4 hole is closed only by a **class-v52 base tree** — the exact reason
Mental ships a Multi-Release mega-jar (jvmdg-downgraded v52 base +
original v61 under `META-INF/versions/17`; four verification gates:
`verifyJdk8Api`, `verifyDowngrade`, `verifyRelocation`, tester isolation).
Extending DOWN: PaperSpigot 1.7.10 (June 2014) targets Java-6 bytecode and its
era runtimes were Java 6–8; **Java 8 (released March 2014) boots it** — that is
the historical default host runtime. Whether stock 1.7.10/1.8.8 boot flagless
on Java 9/17/21 is UNCERTAIN (must be ladder-probed exactly like the 1.9.4–1.16.5
run; expect at minimum Unsafe/illegal-access noise). **Decision:** identical
tiering to Mental — v52 base + v61 `versions/17` — and document **Java 8 as the
host floor even on 1.7.10**. Do NOT chase a v50/v51 base for Java 6/7 hosts:
jvmdg's floor is Java 8, and Java-7-hosted 1.7.10 servers are a rounding error
in 2026. Loader MR-awareness (measured): 1.9.4–1.12.2 URLClassLoader-delegating
loaders honor MR on Java ≥17; 1.13.2–1.15.2 plain-JarFile loaders do not (moot
— their JVMs are <17); 1.16.5+ honor. 1.7.10/1.8's loader is the same
URLClassLoader lineage → presumed MR-honoring (UNCERTAIN, moot on Java 8).

### 7. Folia threading (26.1.2 Folia in-range)
On Folia the `BukkitScheduler` throws `UnsupportedOperationException`; work
must go to `GlobalRegionScheduler` (global state: power regen, autosave),
`RegionScheduler` (location-bound: claim-side effects), `EntityScheduler`
(per-player: fly checks, titles), `AsyncScheduler` (IO). These schedulers exist
in Paper API from roughly 1.20 (UNCERTAIN exact build — they delegate to the
main thread on non-Folia Paper); Folia itself first shipped for 1.19.4, and in
this project's matrix the Folia target is 26.1.2. Factions-specific traps:
claim checks fired from events are fine (already on the owning region thread),
but cross-region teleports (`/f home`) MUST use `teleportAsync` (Paper 1.13+;
required on Folia), and any "iterate all online players" tick task must become
per-player EntityScheduler tasks or a global task that only reads
thread-safe snapshots. **Seam:** one `Scheduler` facade chosen at boot via
`Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`;
compat-folia module compiled against modern API (Mental precedent, including
the gotcha that compat-folia needs the Folia scheduler API on its compile
classpath).

### 8. Chat pipeline divergence
`AsyncPlayerChatEvent` (String + `String.format` semantics) works 1.7.10 → 26.x
and remains the correct universal base for faction-tag injection and faction
channels. Paper's `AsyncChatEvent` (Adventure, 1.16.5+) matters for two
reasons: (a) other modern plugins mutate chat through it, and ordering across
the legacy/modern bridge can drop or double formatting; (b) 1.19.1+ signed
chat means canceling/rewriting player chat converts it to system/unsigned
messages — behavior differs subtly on modern clients (kick-on-tamper is off by
default on Paper; UNCERTAIN whether any 26.x change tightens this). **Seam:**
core registers the legacy event; a probe-registered modern module implements a
`ChatRenderer` on `AsyncChatEvent` instead, and exactly ONE of the two paths is
active per boot.

### 9. Faction-chest item persistence across versions
Serialized ItemStacks (YAML `ConfigurationSection`, `BukkitObjectOutputStream`
Base64) embed material names and meta shapes OF THE VERSION THAT WROTE THEM.
Plugin storage does NOT pass through DataFixerUpper on server upgrade — a chest
serialized on 1.8 (`INK_SACK:4`) will not deserialize on 1.13+, and pre-1.20.5
meta may not round-trip into the 1.20.5+ item-component world. Paper's
`ItemStack#serializeAsBytes` (stores DataVersion, upgrades on read — added
around Paper 1.16.5/1.17, UNCERTAIN exact) fixes this on modern only.
**Seam:** version-tag every stored item blob with the writing server's data
version; use `serializeAsBytes` behind a probe on modern; on legacy, store
Bukkit YAML serialization PLUS a modern-name translation table for the
flattening boundary; accept and document that cross-boundary chest migration
(1.12 → 1.13+) is a one-way explicit migration step, not an implicit load.

### 10. Text delivery stack + scoreboard limits
One rendering pipeline must serve: legacy §-codes (universal floor), `§x` hex
(1.16+ clients only — downsample below), Spigot BungeeComponents (1.8+),
native Adventure (Paper 1.16.5+). Shading Adventure into a jar that ALSO runs
on Adventure-native Paper makes relocation **mandatory** (Mental's
`verifyRelocation` gate exists because unrelocated `net/kyori` classes clash
with the server's own). The same pipeline feeds scoreboards and nametags,
where pre-1.13 the client+API enforce 16-char team prefix/suffix
(`IllegalArgumentException` at 17) and 1.13+ raise it to 64 — faction tags +
color codes overflow 16 trivially, so the seam must budget-truncate AFTER
color-code accounting, and pre-1.13 name color comes from the prefix tail
while 1.13+ needs `Team#setColor`. **Seam:** MiniMessage in configs; render
once to Component; deliver via probe chain; scoreboard/nametag text goes
through a length-budgeting adapter selected by the flattening probe.

**Honorable mentions (not top-10 but real):** the 1.20.5 Mojang-mapping flip +
1.21.3 `Attribute` enum→interface + 1.20.5 Enchantment renames (only bite if
NMS/attribute reflection creeps in — keep factions NMS-free); 1.14's removal of
`ChunkUnloadEvent` cancellation (use `setChunkForceLoaded`, 1.13.2+, for any
keep-loaded feature); `EntityMountEvent` package move at 1.20.3.

---

## Part 3 — Topic-by-topic detail

### 3.1 getOnlinePlayers (topic 1)
- Delta: `Player[]` → `Collection<? extends Player>`; change landed in Bukkit mid-2014,
  inside the extended 1.7.10 build era. UNCERTAIN: exact first Spigot/PaperSpigot
  1.7.10 build carrying the Collection form — probe the actual jar.
- Affected: only the 1.7.10 (and possibly earliest 1.8.x-dev) boundary; 1.8-release+ is Collection.
- Seam: MethodHandle dual-descriptor probe at boot; `platform/Players.online()`;
  forbid direct calls via a lint gate.

### 3.2 Material flattening (topic 2)
- 1.13 removed numeric IDs/data; renamed WOOD_*→ legacy of WOODEN_*, INK_SACK→dyes,
  WOOL→16 constants, BED→16 constants, SKULL_ITEM→PLAYER_HEAD, etc.
- `matchMaterial(String, boolean legacyName)` / `getMaterial(String, boolean)`: **1.13+ only**.
- GUI icons: legacy branch uses `ItemStack(Material, int, short data)`; modern branch
  uses per-color constants. Map rendering in classic factions is CHAT-based
  (ChatColor glyph grid) — no material dependency; keep it that way.
- Durability/damage moved to `Damageable` ItemMeta in 1.13 — don't read
  `getDurability` for identity on modern.
- Seam: single modern-name kernel + static modern→legacy table + one boot probe.

### 3.3 UUID era (topic 3)
- UUID rollout: Mojang accounts 1.7.6+; 1.7.10 API has `getOfflinePlayer(UUID)` and
  `Player#getUniqueId`; `Bukkit.getPlayer(UUID)` added during the 1.7.9 UUID update
  (earliest-build presence UNCERTAIN — cheap probe + linear scan fallback).
- Offline-mode servers: UUIDv3 of `"OfflinePlayer:"+name` — stable per name, so
  UUID-keyed storage works there too.
- `getOfflinePlayer(String)` may block on a web request on modern servers — never
  call on the main thread; maintain an own name↔UUID cache (join events + faction
  member records). This cache is also the tab-complete source for offline members.

### 3.4 Chat (topic 4)
- `AsyncPlayerChatEvent` (1.3+) with `setFormat(String)` (`%1$s`=display name,
  `%2$s`=message) — universal.
- Paper `AsyncChatEvent`: 1.16.5+ (Adventure merge, April 2021). Legacy event still
  fires on modern Paper when handlers exist (deprecated).
- Hover/click pre-Adventure: Spigot bundles the md_5 chat lib + `Player.Spigot#sendMessage(BaseComponent…)`
  from 1.8. 1.7.10: UNCERTAIN for late builds; `/tellraw` (1.7.2+) via console dispatch
  is the only portable rich-text channel there — or plain text fallback.
- 1.19.1+ signed chat: mutation/cancel converts to system chat; factions chat
  channels (cancel + re-send) keep working but messages lose signatures.

### 3.5 Titles / ActionBar / BossBar (topic 5)
- Titles: protocol 1.8+ (1.7 clients have no title screen element — fallback chat).
  Bukkit `sendTitle(String,String)` ~1.11 (deprecated), timed 5-arg ~1.11.2
  (exact minors UNCERTAIN). 1.8–1.10: versioned-NMS `PacketPlayOutTitle`.
- ActionBar: protocol 1.8+; Spigot `sendMessage(ChatMessageType.ACTION_BAR, …)` 1.12+;
  Paper string `sendActionBar` earlier (UNCERTAIN); Adventure 1.16.5+; NMS packet 1.8–1.11.
- BossBar: `Bukkit.createBossBar` 1.9+; `KeyedBossBar` ~1.13; Adventure BossBar on
  modern Paper. Pre-1.9 wither-hack: rejected (entity management cost, fragility).
- Seam: one `Feedback` facade with probe chains; every fallback prints its selection
  in the boot report.

### 3.6 Scoreboard/Team (topic 6)
- API stable since 1.5.2 → whole range OK structurally.
- Limits: prefix/suffix 16 chars (hard IAE) through 1.12; 64 from 1.13. Objective
  display name 32 → 128. Score entries: 16 chars in 1.7, 40 from 1.8 (UNCERTAIN edge).
- `Team#addEntry(String)` ~1.8.x (UNCERTAIN); 1.7.10 fallback `addPlayer(OfflinePlayer)`.
- `Team#setColor` ~1.12 (UNCERTAIN); required on 1.13+ for name glyph color.
- Per-player scoreboards (`getNewScoreboard`) work across the range; beware one
  scoreboard per player × many teams memory on big servers (design, not API).

### 3.7 Inventory GUIs (topic 7)
- Custom `InventoryHolder` identification: viable 1.7.10 → 26.x (create the inventory
  with your holder; compare `getInventory().getHolder()` — but note holder access on
  modern Paper warns for lazy block holders; custom holders are fine).
- `createInventory(InventoryHolder, int, String)`: universal. Component overload
  Paper 1.16.5+ (optional nicety).
- Click deltas: `InventoryClickEvent`/`ClickType`/`InventoryDragEvent` all exist by
  1.7.10 (drag event + ClickType landed in the 1.7.2–1.7.9 window). `getClickedInventory`
  presence at 1.7.10/1.8 UNCERTAIN → probe, fallback rawSlot math. `SWAP_OFFHAND`
  ClickType 1.16+ (Enum.valueOf). Number-key + off-hand swaps into GUIs must be
  cancelled on 1.9+ (hotbar button 40).
- The 1.21 InventoryView ICCE: see Risk #2 — the single most dangerous GUI delta.

### 3.8 Event inventory (topic 8)
| Event | Since | Note |
|---|---|---|
| `PlayerSwapHandItemsEvent` | 1.9 | probe-register |
| `EntityPickupItemEvent` | 1.12 | dual-register with legacy `PlayerPickupItemEvent`; which fires for players on modern Paper: UNCERTAIN |
| `BlockExplodeEvent` | 1.8.3 | beds/anchors/cannon protection; absent 1.7.10 |
| `SpawnChangeEvent` | ancient | universal |
| `PlayerInteractAtEntityEvent` | 1.8 | armor stands |
| `ArmorStand` (+manipulate event) | 1.8 (manipulate event 1.8.x UNCERTAIN) | probe class |
| Hanging events (`HangingBreakByEntityEvent` etc.) | 1.4.3 | universal at our floor |
| `EntityMountEvent` | spigotmc pkg (1.8-era; 1.7.10 UNCERTAIN) → `org.bukkit` 1.20.3; spigotmc variant later removed on Paper (UNCERTAIN when) | dual Class.forName probe |
| `PlayerAdvancementDoneEvent` | 1.12 | `org.bukkit.Achievement` REMOVED in 1.12 — never reference the class |
| `EntityDamageByEntityEvent` | universal | new `DamageCause` constants over time (FLY_INTO_WALL 1.9, HOT_FLOOR 1.10, ENTITY_SWEEP_ATTACK 1.11, …) — Enum.valueOf only |
| `BlockPistonRetractEvent#getBlocks` | 1.8 (slime blocks) | 1.7.10 fallback `getRetractLocation` |
| `EntityChangeBlockEvent`, `BlockFromToEvent`, `StructureGrowEvent`, bucket events | universal | claim protection core |

### 3.9 Chunks & claims (topic 9)
- Claim identity = `(worldId, x, z)` from pure arithmetic (`blockX >> 4`). Never
  `World.getChunkAt` for a membership test (it LOADS the chunk).
- `Chunk.getEntities`: stable; loads nothing extra, but on Folia only from the
  owning region thread.
- Async loads: Paper `getChunkAtAsync` ~1.13+ (PaperLib pattern: async on Paper,
  sync fallback elsewhere). Needed only for /f home safety checks in unloaded
  chunks and similar.
- `ChunkSnapshot`: stable across range (fine for async map rendering of block
  tops if ever needed).
- Chunk keys: compute in-house; Paper's `Chunk#getChunkKey` (Paper ~1.13.2+) is
  just `(z<<32)|x` — no probe needed.
- World height: `getMaxHeight()` universal; `getMinHeight()` ~1.17 (probe, fallback 0);
  overworld −64 from 1.18. Any "protect the whole column" or home-Y-clamp logic
  must use the probed min/max.
- `ChunkUnloadEvent` not cancellable from 1.14; `World#setChunkForceLoaded` 1.13.2+.

### 3.10 Scheduling & Folia (topic 10)
- `BukkitScheduler`: stable 1.7.10 → 26.x on Bukkit-lineage servers.
- Folia: first release 1.19.4 (2023); this matrix ships Folia at 26.1.2. Folia
  schedulers in Paper API from ~1.20 (UNCERTAIN exact build) and on all Folia.
- Detection: `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`
  (the standard `isFolia` probe).
- `teleportAsync`: Paper 1.13+; mandatory for Folia cross-region teleports; on
  Spigot/legacy fall back to sync `teleport` on the main thread.
- Facade mapping for factions: power regen/decay → global; claim particles/effects →
  region(location); fly checks + warmups → entity(player); storage IO → async.
- plugin.yml `folia-supported: true` required for Folia to load the plugin.

### 3.11 PDC vs metadata vs NBT (topic 11)
- `PersistentDataContainer`: 1.14+ (ItemMeta, Entity, TileEntity; Chunk ~1.16.x
  UNCERTAIN). `NamespacedKey`: **1.12+** (grounded via Mental GAP 1) — every
  PDC/key-typed symbol is a descriptor hazard below 1.12.
- Marking faction items (e.g., fchest key items): PDC on 1.14+; legacy fallback =
  color-code steganography in lore line 0 (§-encoded id) — survives serialization,
  invisible to players; validate on read.
- Marking entities: PDC 1.14+; scoreboard tags (`addScoreboardTag`, ~1.11,
  UNCERTAIN exact, persistent) as mid-era fallback; `setMetadata` (universal) is
  TRANSIENT — session-only marks like spawned holograms.
- Custom NBT pre-1.14 requires versioned NMS — avoid; the fallbacks above suffice.

### 3.12 plugin.yml / api-version (topic 12)
- See Risk #5. Decision: Bukkit `plugin.yml`, `api-version: '1.13'`,
  `folia-supported: true`, no paper-plugin.yml. Unknown keys are ignored by
  pre-1.13 loaders; api-version 1.13 is accepted by every 1.13+ server
  (grounded for 1.14.4 by the javap'd `SUPPORTED_API` list; presumed and to-verify
  for 26.x — UNCERTAIN only in the trivial sense that 26.x acceptance of '1.13'
  should be smoke-tested once).
- Commodore shim risk if api-version omitted: legacy-material remapping would
  corrupt the plugin's own modern-name kernel — this is a correctness issue, not
  just a nag line.

### 3.13 Text serialization (topic 13)
- Adventure native in Paper 1.16.5+. adventure-platform-bukkit claims support for
  older Bukkit (its documented floor is around 1.8.8; 1.7.10 support UNCERTAIN —
  if unsupported, the flat-§ fallback covers 1.7.10).
- Shade + RELOCATE adventure(-api, -text-minimessage, -text-serializer-legacy,
  optionally -platform-bukkit). Unrelocated kyori on modern Paper = classloader
  conflicts (Mental's `verifyRelocation` exists for exactly this).
- Legacy §-codes: the universal floor; `ChatColor.translateAlternateColorCodes`
  works 1.7.10 → 26.x. Hex `§x§R§R§G§G§B§B`: 1.16+ clients only — the legacy
  serializer must downsample below 1.16.
- MiniMessage as the config-facing format; render once, cache per-message.

### 3.14 Vault (topic 14)
- `net.milkbowl.vault.economy.Economy` / `EconomyResponse` unchanged since ~2011;
  works across the whole range (the Vault PLUGIN version differs per server era,
  the API classes don't).
- Use `OfflinePlayer` overloads (Vault 1.4+, 2014) — the String-name methods are
  deprecated and misbehave with UUID storage.
- Integration shape: `softdepend: [Vault]`; resolve via `ServicesManager` inside a
  `Class.forName("net.milkbowl.vault.economy.Economy")` guard class (descriptor rule
  applies — Vault types only in the guarded class).
- Permissions: use Bukkit `hasPermission` directly (universal); Vault-chat only for
  prefix integration if desired. Watch: "VaultUnlocked"/Vault2 forks exist but keep
  the legacy interfaces registered.

### 3.15 Commands (topic 15)
- plugin.yml `PluginCommand` + `CommandExecutor` + `TabCompleter` (`onTabComplete`):
  ALL present in 1.7.10 — the universal core. Factions is one root `/f` command with
  sub-command routing: this model needs nothing newer.
- Dynamic registration (aliases like `/faction`): `Server#getCommandMap` is a Paper
  accessor (~1.16.5, UNCERTAIN); reflection on `CraftServer.commandMap` field works
  across the range (field name stable; pre-1.20.5 spigot-mapped irrelevance — it's
  CraftBukkit, not NMS).
- Paper `AsyncTabCompleteEvent`: ~1.12+ (nice for member-name completion off-thread).
- Brigadier: Paper lifecycle API (`io.papermc.paper.command.brigadier`) 1.20.6+ —
  optional compat-brigadier module for argument types/suggestions on modern; the
  plugin.yml command remains the source of truth.

### 3.16 Fly & related (topic 16)
- `setAllowFlight`, `setFlying`, `setFlySpeed`, `getGameMode`: universal.
- `GameMode.SPECTATOR`: 1.8+ (Enum.valueOf seam; 1.7.10 sticky-getstatic hazard).
- `isGliding` (elytra) 1.9+, `EntityToggleGlideEvent` ~1.10 (UNCERTAIN) — fly-in-claim
  logic must treat gliding separately on 1.9+.
- Vanish interop: `hidePlayer(Plugin, Player)` 1.12.2+, old overload before;
  standard vanish-plugin metadata key `"vanished"` check is version-free.

### 3.17 Java/bytecode (topic 17)
Covered in Risk #6. Summary decision table:

| Host server | Native-era JVM (flagless) | Tree loaded from mega-jar |
|---|---|---|
| 1.7.10 / 1.8.x | Java 8 (Java 6/7 UNSUPPORTED by us; Java 9+ boot UNCERTAIN — probe) | v52 base |
| 1.9.4–1.12.2 | up to Java 25; Java 21 warning-free [measured] | v61 via MR-honoring URLClassLoader on ≥17 |
| 1.13.2 / 1.14.4 | Java 13 (1.14.4 hard cap, no flag) [measured] | v52 base |
| 1.15.2 | Java 14 [measured] | v52 base |
| 1.16.5 | Java 16 [measured] | v52 base |
| 1.17.1–1.19.x | Java 17 | v61 |
| 1.20.6+ / 1.21.x / 26.1.2 / Folia 26.1.2 | Java 25-era | v61 |

Build pipeline to copy verbatim from Mental: compile `options.release = 17` against
the floor API → shadowJar → jvmdg DowngradeJar (v52 base + `multiReleaseOriginal`) →
jvmdg ShadeJar (relocate jvmdg runtime under a plugin-unique prefix — the D-8
cross-plugin stub-isolation rule: no post-Java-8 JDK type in any cross-plugin API
descriptor) → gates `verifyJdk8Api` (empty allowlist vs real JDK-8 `rt.jar`),
`verifyDowngrade`, `verifyRelocation`.

**Compile-floor choice for FableFactions:** compiling core against **1.17.1 API**
(Mental's choice) keeps maximal modern-API comfort but makes EVERYTHING below 1.17
a runtime-resolution problem — acceptable for a combat plugin with a narrow legacy
surface, heavier for a factions plugin whose protection listeners live mostly in
ancient, stable APIs. A **lower core floor (compile against 1.13.x API)** shrinks
the probe surface for the 1.13–1.16 band to ~zero and confines probing to the
1.7–1.12 band + modern extras — recommended lean: **core floor = 1.13 API,
compat-modern (Adventure/Brigadier/Folia) compiled against 1.20.6+, legacy band
handled by probes**. Either works with the same MRJAR shape; this is a
probe-budget tradeoff, not a correctness one.

### 3.18 Everything else (topic 18)
- Damage hierarchy: `EntityDamageEvent`/`EntityDamageByEntityEvent` stable; 1.8.3+
  damage modifiers API deprecated (avoid); 1.20.4+ `DamageSource` available (ignore);
  resolve projectile shooters via `Projectile#getShooter` (`ProjectileSource` since 1.7.5 —
  at the floor, safe).
- Potions: 1.9 split splash/lingering; `PotionSplashEvent` universal is the claim-combat
  hook; `LingeringPotionSplashEvent`/`AreaEffectCloudApplyEvent` 1.9+ probe-registered.
- Ender pearls: `TeleportCause.ENDER_PEARL` universal; CHORUS_FRUIT/END_GATEWAY 1.9+
  via Enum.valueOf; pearl-through-claim-border is a pure `PlayerTeleportEvent` rule —
  version-free.
- Bed/anchor explosions: nether beds always exploded; **respawn anchors 1.16+**;
  protection = `EntityExplodeEvent` (universal) + `BlockExplodeEvent` (1.8.3+); the
  anchor/bed explosion yield shows up as BlockExplode on modern (verify per-version
  which event carries the block list — UNCERTAIN split).
- Signs: `SignChangeEvent` universal; 1.20 adds SignSide/editing (`Sign#getSide`,
  edit events) — legacy line APIs still function; only touch side API in compat-modern.
- Raids: game 1.14, Bukkit Raid API/`RaidTriggerEvent` ~1.15 (UNCERTAIN) —
  probe-registered "no raids in claims" listener.
- Elytra vs fly: 1.9+; see 3.16.
- Piston/liquid/enderman/wither claim grief: all covered by universal events
  (3.8 table); the single 1.7.10 gap is `BlockPistonRetractEvent#getBlocks` (1.8+).
- Sweep-attack in claims: `ENTITY_SWEEP_ATTACK` DamageCause 1.11+ — the canonical
  sticky-getstatic example; Enum.valueOf only.
- `SpawnReason` constants grow constantly (RAID 1.14, etc.) — same rule.
- Mapping flip / registry breaks (1.20.5 Mojang-mapped runtime; 1.21.3 Attribute
  enum→interface; 1.20.5 Enchantment renames): only relevant if NMS or
  attribute/enchant references creep in. Recommendation: keep the factions core
  100% NMS-free; if a feature demands NMS (e.g., fancy claim borders via packets),
  isolate it in a compat module using the Mental reflection-remapper pattern.

---

## Part 4 — Consolidated UNCERTAIN list (verify before build)

1. Exact PaperSpigot 1.7.10 terminal build's `getOnlinePlayers` descriptor (array vs Collection).
2. Stock 1.7.10 / 1.8.8 boot behavior on Java 9/17/21 flagless (ladder-probe like the 1.9.4–1.16.5 run); presumed-fine on Java 8.
3. 1.7.10/1.8 PluginClassLoader MR-awareness (URLClassLoader lineage — presumed honoring; moot on Java 8).
4. BungeeCord chat lib (`BaseComponent`) presence in late Spigot/PaperSpigot 1.7.10 builds.
5. Exact minors for: `sendTitle` 2-arg/5-arg (1.11 vs 1.11.2), Paper `sendActionBar(String)` introduction, `Team#addEntry`, `Team#setColor`, `EntityToggleGlideEvent`, `addScoreboardTag`, Raid API, Chunk PDC, `World#getMinHeight` (any 1.16.5 Paper backport), Paper `Server#getCommandMap`, Paper Folia-scheduler-API first build, `getChunkAtAsync` first Paper build, `serializeAsBytes` first Paper build.
6. Whether `PlayerPickupItemEvent` still fires for players on modern Paper (vs only `EntityPickupItemEvent`).
7. `org.spigotmc…EntityMountEvent` removal version on Paper (post-1.20.3 move).
8. adventure-platform-bukkit's true floor (1.7.10 vs 1.8.8).
9. `InventoryClickEvent#getClickedInventory` presence at 1.7.10/1.8.
10. 26.x acceptance of `api-version: '1.13'` and any 26.x chat-signing tightening (smoke-test once — expected fine given 1.21.11→26.1.2 continuity in the Mental matrix).
11. Which event (Block vs Entity explode) carries the block list for bed/anchor explosions per version.
12. Score-entry length limits at 1.7.10 (16 vs 40 chars).

## Part 5 — Architecture decision summary

- ONE jar: MRJAR, v52 base (jvmdg-downgraded) + v61 `META-INF/versions/17`;
  Java 8 host floor (including 1.7.10); Mental's four verification gates cloned.
- `plugin.yml` (not paper-plugin.yml), `api-version: '1.13'`, `folia-supported: true`.
- Core compile floor: 1.13.x API recommended (vs Mental's 1.17.1) to shrink the
  probe surface; compat modules (adventure-native, brigadier, folia, modern-chat)
  compiled against 1.20.6+ and classloaded behind probes.
- All version divergence resolved ONCE at boot into a printed manifest
  (probe + fallback; MethodHandles for signature splits like getOnlinePlayers and
  InventoryView; Enum.valueOf for every post-1.7.10 enum constant; separate
  listener classes for every post-1.7.10 event type).
- CI gate scans captured console logs for the two Bukkit swallow signatures
  (failed listener registration / per-event handler error) — a green matrix with
  either is a FAIL (Mental D-9 rule).
