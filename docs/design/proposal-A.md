# FableFactions — Proposal A: The Data-Oriented Core

**Stance: performance is an architecture, not an optimization.** Plugin architectures die by
allocation churn, pointer-chasing, and lock contention. FableFactions is therefore designed
from the memory layout up: primitive-keyed open-addressing tables, structure-of-arrays
ledgers, interned integer identities, copy-on-write published snapshots read lock-free by
every event handler, and a **single-writer state pipeline** through which *all* mutation
flows. Persistence is a write-behind journal projected **from** memory — memory is
authoritative, SQL is a replica.

Package root: `dev.fablemc.factions`. Plugin: **FableFactions**, main class
`dev.fablemc.factions.FableFactionsPlugin`, root command `/f` (aliases `faction`,
`factions`), admin `/fa` (alias `factionadmin`). One shipped artifact:
`FableFactions-<version>.jar`, a Multi-Release mega-jar spanning PaperSpigot 1.7.10 →
Paper/Folia 26.1.2.

---

## 0. Design axioms (everything below derives from these)

| # | Axiom | Consequence |
|---|-------|-------------|
| A1 | **Memory is authoritative.** All faction/player/claim/relation state lives in RAM, loaded whole at boot. | Zero main-thread IO is structural, not disciplined: there is nothing to query. Storage is a projection. |
| A2 | **One writer.** Every mutation is a `Command` executed on one dedicated thread (the *Core Writer*). | Read-modify-write races, TOCTOU, lock-map bugs, and torn multi-entity updates are impossible: the writer is the single linearization point. There are zero locks in the state model. |
| A3 | **Readers never wait.** Event handlers read immutable, atomically-published snapshots (COW shards + volatile epochs). | Protection/move/chat hot paths are wait-free, zero-allocation, O(1). Folia region threads and the main thread read the same structures identically. |
| A4 | **Identities are small integers.** Factions and online players get dense interned ordinals; chunks are packed 64-bit keys `((long)z<<32)|(x&0xFFFFFFFFL)`. | No boxed keys, no UUID hashing on hot paths, flat arrays instead of object graphs. |
| A5 | **Version divergence is resolved once, at boot.** Capability probes → one immutable `PlatformProfile`; era code is reachable only behind probes; seam call sites are monomorphic. | One jar, 1.7.10 → 26.1.2, no version parsing, no per-event reflection. |
| A6 | **Pure logic lives in `kernel`,** a Bukkit-free module, enforced at build time. | Every formula (power, land, tax, shield, adjacency) is unit-pinned without a server. |
| A7 | **Nothing degrades silently.** Every probe fallback prints one boot line; CI fails on Bukkit's swallowed-error signatures. | The Mental D-9 discipline, inherited wholesale. |

---

## 1. Module / Gradle project graph

```
settings.gradle.kts:
include(":kernel", ":api", ":platform", ":core", ":compat-folia", ":compat-modern", ":tester")
project(":compat-folia").projectDir  = file("compat/folia")
project(":compat-modern").projectDir = file("compat/modern")
```

| Module | Sees Bukkit? | Compile API | Role |
|---|---|---|---|
| `kernel` | **No** (build-enforced) | pure JDK 17 syntax → v61 → jvmdg v52 | Primitive collections (`LongIntHash`, `LongByteHash`, `UuidIntHash`, `IntStack`), chunk math, `ClaimIndex`, `RelationGraph`, `PowerMath`, `LandMath`, `TaxMath`, `ShieldWindow`, `NameRules`, the **domain state** (`FactionTable`, `PlayerLedger`) and the **command reducer** (`Ops`, `Reducer`, `Effect`), journal encoding, `MenuModel`, `CommandSpec` tree, message-template model. |
| `api` | Yes (`compileOnly` spigot-1.13.2) | 1.13.2 | Public surface `dev.fablemc.factions.api.*`: Bukkit events (`FactionCreateEvent`, `FactionDisbandEvent`, `FactionJoinEvent`, `FactionLeaveEvent`, `FactionChunkClaimEvent`, `FactionChunkUnclaimEvent`, `FactionBankTransactionEvent`), read-only `FactionsApi` service. japicmp-gated (grow-only). |
| `platform` | Yes | spigot-1.13.2 | The seam: `Capabilities`, `PlatformProfile`, `Scheduling` (+`BukkitScheduling`), `TextPort`, `Players`, `Materials`, `Feedback` (title/actionbar/bossbar chains), `TeamBudget` (16/64-char scoreboard), `InventoryPort`, `ViewHandles` (MethodHandle InventoryView), `Hands`, `MountEvents`, `Pdc`, `WorldHeights`, `CommandMaps`, `ItemBlobs`. Publishes a Scheduling TCK as `java-test-fixtures`. |
| `core` | Yes | spigot-1.13.2 | The plugin: boot, feature reconciler, listeners, command executors, `StateEngine` wiring, storage (journal → JDBC), integrations, GUI, messages. Shades+relocates: adventure (api, minimessage, legacy+gson serializers), HikariCP, H2, mysql-connector-j, bStats → `dev/fablemc/factions/lib/…`. |
| `compat-folia` | Yes (paper-folia 1.20.4+) | Folia API | `FoliaScheduling` only. Folded into core's shadowJar; instantiated by FQN string behind the `folia` probe. |
| `compat-modern` | Yes (paper 1.20.6) | modern Paper | `ModernChatRenderer` (`AsyncChatEvent`), `NativeAudience` (Adventure-native send), `BrigadierDecorator` (1.20.6+ lifecycle), `ModernItemBlobs` (`serializeAsBytes`). Folded in, FQN-gated per probe. |
| `tester` | Yes | 1.13.2 | Second mega-jar (distinct jvmdg prefix `dev/fablemc/factions/tester/lib/jvmdg/`) that boots in the live matrix and self-asserts: loaded bytecode tier, listener registration counts, reload leak-freedom, protection smoke suite, crash-recovery drill. |

**Dependency direction (enforced):** `kernel ← platform ← core`; `api ← core`; `compat-* → platform (compileOnly)`. Build-time enforcement:

```kotlin
// kernel/build.gradle.kts — the enforcement edge
configurations.all {
    resolutionStrategy.eachDependency {
        require(!requested.group.startsWith("io.papermc"))    { "kernel must stay Bukkit-free" }
        require(!requested.group.startsWith("org.spigotmc"))  { "kernel must stay Bukkit-free" }
        require(!requested.group.startsWith("net.kyori"))     { "kernel must stay Adventure-free" }
    }
}
```

plus `KernelClasspathTest` asserting `Class.forName("org.bukkit.Bukkit")` throws on the
kernel test classpath, and ArchUnit-style bytecode gates (§13, G1–G8): no `java.sql` outside
`core.storage`, no `net.kyori` outside `TextPort`+`MessageCatalog`, no `InventoryView`
method calls anywhere, no field/method descriptor in a registered `Listener` class naming a
post-1.7.10 Bukkit type.

**Compile floor = Spigot 1.13.2 API** (deliberate change from Mental's 1.17.1, per
`version-deltas.md` §3.17): a factions plugin's protection listeners live almost entirely in
pre-1.13 stable API, so a 1.13 floor shrinks the probe surface to the 1.7–1.12 band plus
modern extras. jvmdg lowers *bytecode*, not API — compiling v61 against the 1.13 API is
exactly Mental's shape one rung lower.

---

## 2. The state model: who owns what

### 2.1 Interned identities

- **`FactionTable` (kernel).** Every faction gets a dense `int` ordinal at load/create.
  Ordinal `0` = none/wilderness (absence), `1` = SAFEZONE, `2` = WARZONE, `≥3` = real
  factions. Disband frees the ordinal into a free-list with a 16-bit generation counter
  packed into the ordinal handle (`gen<<16|ord` for any long-lived reference) so a stale
  handle can never resolve to a reincarnated faction. UUID↔ordinal maps live at the edges
  (`UuidIntHash`, open addressing over parallel `long[] msb, long[] lsb, int[] val` — zero
  boxing).
- **`PlayerLedger` (kernel).** Structure-of-arrays over *known players*, indexed by a dense
  player ordinal: `double[] power`, `double[] powerBoost`, `long[] lastActivity`,
  `long[] lastDeathAt`, `byte[] deathStreak`, `int[] factionOrd`, `int[] rankIdx`,
  `long[] flags` (frozen, overriding, territoryTitles, notify bits, autoTerritoryMode 2
  bits, locale index 6 bits). Grown by doubling; 1M known players ≈ 44 MB. Owned
  exclusively by the writer.
- **`PlayerSession` (core).** One per *online* player, created on join, evicted on quit,
  found via a `UuidIntHash` (uuid → session slot). Caches everything hot paths need so an
  event handler does exactly one probe: `factionOrd`, `rankPriority`, `bypass`,
  `overriding`, `chatTagLegacy` (String), `chatTagNative` (Object), `worldIdx`,
  `lastChunkKey`, `autoMode`, `flyOn`, `combatTagUntil`, `warmupHandle`, `notifyBits`,
  `localeIdx`. Fields written only by the writer (via release stores), read by any thread
  (acquire).

### 2.2 `ClaimIndex` — the chunk→owner map (the crown jewel)

Per world (dense `worldIdx` from a `WorldRegistry`):

```java
final class WorldClaims {
    final AtomicReferenceArray<ClaimShard> shards;   // 8192 shards
}
final class ClaimShard {                              // IMMUTABLE once published
    final long[] keys;    // packed chunk keys, open addressing, linear probe
    final int[]  owners;  // owners[i]==0 => empty slot; else faction ordinal
    // capacity = pow2, load factor <= 0.60
}
```

- **Key:** `key = ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL)` — computed in-house,
  never Paper's `Chunk#getChunkKey` (kernel is Bukkit-free; identical formula).
- **Shard select:** `mix = key * 0x9E3779B97F4A7C15L; shard = (int)(mix >>> 51)` (top 13
  bits → 8192 shards).
- **Read:** 1 volatile read (`shards.get`) + linear probe (avg 1.4 probes at LF 0.6) —
  **wait-free, 0 allocations, ~2 cache lines touched**.
- **Write (writer thread only):** copy the one affected shard (2M claims/world → ~244
  entries/shard → ≤512-slot table ≈ 6 KB), mutate the copy, `shards.set(i, copy)`. Readers
  see old or new shard atomically. Claim ops are player-driven (≤ hundreds/sec even with
  auto-claim sprinting): ≤ ~1.5 MB/s of transient copying, nothing.
- **Memory:** ~20 B/claim at LF 0.6 → 3M claims ≈ 60 MB. Deletion uses backward-shift
  (no tombstones), so tables never degrade.
- Also per faction: an `int→LongArray` of owned chunk keys (`FactionClaimList`, writer-owned,
  used by `/f unclaim all`, dynmap, WG-sync, land counting — count is a maintained `int[]
  landCount` indexed by ordinal, so *no* counting query ever happens).

### 2.3 `RelationGraph`

Effective relations are **symmetric by construction** (the wish handshake makes ally/truce
mutual; enemy/neutral mirror unconditionally — same observable rules as the original, §6 of
pvp-commands-admin). Therefore one global immutable table suffices:

```java
final class RelationSnapshot {          // volatile-published whole on any relation change
    final LongByteHash pairs;           // key = ((long)min(a,b)<<32)|max(a,b) → Relation ordinal
}
```

Lookup = 1 probe. Relations change at human speed (commands), so whole-table COW (typical
size: factions × avg 8 relations × 12 B ≈ 1 MB for 10k factions) costs microseconds per
change. One-directional **wishes** are cold state on the writer (`Int2ObjectHash<IntByteHash>`),
never consulted by hot paths.

### 2.4 Faction cold state & counters

`FactionView[] factionViews` — a volatile-published COW array indexed by ordinal. Each
`FactionView` is an immutable record: id (UUID), name, nameLower, description, motd,
ownerUuid, home (packed), shieldStartHour, shieldDurationHours, createdAt, raidable,
prerendered chat-tag fragments. Rebuilt (one small object) on rename/motd/etc. — rare.
Hot per-faction scalars live in flat writer-owned arrays published for racy display reads:
`AtomicLongArray flagBits` (bit0 pvp, 1 friendly-fire, 2 explosions, 3 fire-spread, 4 open),
`int[] landCount`, `int[] memberCount`, `double[] bank`, `double[] factionPowerCache`
(recomputed incrementally by the writer on member power change — the raidable sweep is
O(factions) with zero queries). **Rule: decisions read writer-local truth (they run on the
writer); displays read published values.** Ranks per faction: `RankTable` — immutable
arrays sorted by priority DESC (fixes promote/demote order-dependence structurally).

### 2.5 Ownership summary

| State | Owner (writes) | Readers | Publication |
|---|---|---|---|
| ClaimIndex shards | Core Writer | all event threads | COW shard via `AtomicReferenceArray` |
| RelationSnapshot | Core Writer | all | volatile whole-table COW |
| FactionView[], RankTables | Core Writer | all | volatile COW array |
| flagBits/landCount/memberCount/bank | Core Writer | display paths | atomic/opaque reads |
| PlayerLedger | Core Writer | writer only (snapshotted fields into sessions) | n/a |
| PlayerSession fields | Core Writer | all | release/acquire (VarHandle) |
| Settings (config) | reload command (writer) | all | one volatile reference |
| MessageCatalog | reload (writer) | all | one volatile reference |
| SQL database | Storage thread | Storage read-lane (cold pagination) | — |
| Open chest inventories | region thread (Bukkit) + writer for content ops | viewers | single live `Inventory` per (faction,chest) |

---

## 3. Threading model: the single-writer pipeline

```
region/main threads                Core Writer thread                Storage thread
──────────────────                ───────────────────               ───────────────
event fires                         │                                  │
  read snapshots (wait-free)        │                                  │
  [phase A] validate + fire         │                                  │
  cancellable Bukkit event          │                                  │
  submit Op ──────MPSC queue──────► apply:                             │
                                    re-validate invariants             │
                                    mutate state (sole writer)         │
                                    append JournalRecords ──SPSC ring──► coalesce + batch
                                    emit Effects ──┐                   │ JDBC txn commit
                                                   │                   │ (250 ms / 256 ops,
  ◄── Scheduling.runOn/runAt/runGlobal ────────────┘                   │  CRITICAL = barrier)
      (messages, teleports, WG/dynmap mirroring,                       └─ confirm CRITICAL
       API events post-commit)                                            effects
```

### 3.1 The Core Writer

A single dedicated daemon thread (`FableCoreWriter`). It never touches the Bukkit API —
that is what makes it legal on Folia and Paper alike. Input: a bounded MPSC array queue
(65,536 slots, padded head/tail, park/unpark signaling; hand-rolled in kernel, ~150 LOC).
Every mutation in the entire plugin is an `Op` — claims, joins, power deltas, bank moves,
relation wishes, config reload swap, session create/evict, chest content commits, admin
edits. Ops re-validate **all** invariants against authoritative state at apply time (caps,
name uniqueness, adjacency, raidability, shield, buffer zone, member cap, invite existence)
— phase-A checks are only fast-fail UX. The writer is the linearization point; "check then
act" cannot race because check and act are the same thread.

Timers (power tick, tax, raidable sweep, invite pruning, autosave heartbeat) are
writer-internal deadlines serviced by the queue-poll timeout — no Bukkit scheduler
involvement, no async/sync split to get wrong.

### 3.2 Two-phase commands & Bukkit events

Phase A (calling thread): resolve session, snapshot-validate for instant feedback, fire the
**cancellable** API event (`FactionChunkClaimEvent` etc.) synchronously on the region/main
thread where plugins expect it, honor cancellation/mutation (`FactionBankTransactionEvent#setAmount`),
then submit the Op. Phase B (writer): re-validate → apply → journal → effects. If
re-validation fails post-event, the op degrades to the same user-facing denial as a phase-A
failure and **no post-commit consumer fires** — audit, dynmap, WG-sync, DiscordSRV,
TeamsAPI mirroring are all driven by post-commit effects, so they can never record a
mutation that didn't happen (fixes the original's audit-on-MONITOR-of-precommit-event
weakness; delta documented in the API javadoc).

### 3.3 Effects

`Effect` records emitted by the reducer are routed by `EffectRouter` on the correct thread
via the `Scheduling` facade: player messages → `runOn(player)`; teleports → warmup engine →
`teleportAsync` (probe) on the player's region; WG region mirroring / dynmap markers /
LWC cleanup → `runGlobal`/`runAt(location)`; Vault settlements/compensations → main thread.
The retired-callback contract (Mental §4) applies: exactly one of task/retired runs, no
thread affinity assumed.

### 3.4 Folia mapping

- Event handlers already run on the owning region thread; they only read snapshots — safe.
- The writer thread is plugin-owned — safe everywhere.
- All Bukkit touches route through `Scheduling` (`BukkitScheduling` vs `FoliaScheduling`
  chosen at boot; Folia impl folded in by FQN behind the probe, `folia-supported: true`).
- Folia quirks handled in the facade: tick delays clamped ≥1; `entity.getScheduler().run`
  returning null fires `retired` to honor the contract; cross-region teleports use
  `teleportAsync` (1.13+ Paper probe; sync fallback on Spigot/legacy).

### 3.5 Shutdown & reload ordering (leak-proof lifecycle)

`onDisable`: (1) close all feature scopes (unregister every listener/task — the reconciler
owns them); (2) stop intake (queue rejects, commands answer "shutting down"); (3) drain
writer queue to empty; (4) force-persist open chest inventories through the writer; (5)
journal flush **barrier** — storage thread commits everything; (6) close Hikari pool. Every
step bounded by timeout with loud logging. `/fa reload` never rebuilds listeners by
re-running start over live state: it parses a fresh `Settings`, swaps the volatile, and the
**feature reconciler converges scopes** (close removed, open added) — reload is idempotent
by construction (§9).

---

## 4. The five hot paths — exact structures and allocation stories

Baseline: 1,000 online players, 3M claims, protection checks on every block/entity/move
event. All figures are per-event steady state.

### (a) Chunk→claim lookup on movement/block events

```java
@EventHandler(priority = MONITOR, ignoreCancelled = true)
public void onMove(PlayerMoveEvent e) {
    Location to = e.getTo(); if (to == null) return;                    // logic-bug #19 pinned
    Location from = e.getFrom();
    int fx = from.getBlockX() >> 4, fz = from.getBlockZ() >> 4;
    int tx = to.getBlockX()  >> 4, tz = to.getBlockZ()  >> 4;
    if (fx == tx && fz == tz && from.getWorld() == to.getWorld()) return;   // ~92% of moves
    PlayerSession s = sessions.get(e.getPlayer().getUniqueId());        // UuidIntHash probe
    long key = ChunkKeys.key(tx, tz);
    int owner = claims.ownerAt(s.worldIdx(to), key);                    // shard probe
    if (owner == s.lastOwnerOrd && key == …) …                          // territory-transition only on change
}
```

- Cost: same-chunk fast path = 4 int compares, **0 alloc** (`getBlockX` is int math on the
  existing Location; no `getChunk()` — that can load chunks). Crossing path = 1 UuidIntHash
  probe (2 long compares) + 1 volatile read + ≤2 probes + 1 session compare.
- **Bytes touched:** ~3 cache lines (~192 B). **Allocations: 0.** Estimated 15–40 ns
  crossing, <5 ns same-chunk. There is exactly **one** move listener (`MoveIntake`) that
  fans out to territory-notify, auto-claim, fly-revocation, warmup-cancel, map-follow — the
  original's doubled move listeners are consolidated.

### (b) Relation lookup player↔player

```java
int a = attackerSession.factionOrd, b = victimSession.factionOrd;      // acquire reads
if (a == b) → MEMBER path (friendly-fire flag bit test);
byte rel = relations.pairs.get(RelationGraph.pairKey(a, b));           // 1 probe, miss → NEUTRAL
```

**0 alloc, 1 hash probe (~1 cache miss), ~5–20 ns.** No JSON, no string scan — the
original's hand-rolled `relations_json` indexOf parse is replaced by an interned matrix;
the JSON survives only in the SQL projection for the importer.

### (c) Protection decision pipeline

One pure function, `AccessEngine.decide(session, worldIdx, chunkKey, Action) → int verdict`
(ALLOW / DENY_* codes):

1. `session.bypass || session.overriding` → ALLOW (2 bit tests).
2. `owner = claims.ownerAt(...)` → `0` (wilderness) → ALLOW for build; PvP falls to server rules.
3. `owner == 1/2` (safezone/warzone) → table verdict per Action (config bits pre-baked into
   a 64-entry `long[] zoneVerdicts` at Settings parse — no config-object walk per event).
4. `owner == session.factionOrd` → ALLOW.
5. flags word: `flagBits.get(owner)` bit test (pvp/explosions/fire-spread) per Action.
6. relation probe (b) → ALLY ⇒ ALLOW build (mutual by construction — logic-bug #21 dead).

**≤4 cache misses, 0 allocations, ~30–60 ns.** Deny messages are resolved only on the deny
branch (cold). Explosion events: iterate `blockList()` with a 1-entry last-key cache plus
an 8-slot direct-mapped chunk-decision cache on the stack frame (explosions cluster
spatially) → O(blocks) integer work, **0 queries, 0 alloc** vs the original's 2 DB queries
per block. The full protection event matrix is in §8.

### (d) Chat tag rendering

The tag is **prerendered by the writer** whenever faction/rank/name/prefix changes, into
`session.chatTagLegacy` (§-string) and `session.chatTagNative` (an Object holding the
platform component on modern; built through `TextPort`). The chat listener (HIGHEST,
ignoreCancelled) does:

```java
String tag = session.chatTagLegacy;                    // acquire read, may be ""
event.setFormat(tag + "%s: %s".intern-free concat);    // one unavoidable String alloc
```

Legacy path (`AsyncPlayerChatEvent`, universal): **1 String concat** (Bukkit's format
contract forces it) — the only allocation on the path. Modern path (compat-modern
`ModernChatRenderer`, probe-gated, exactly one of the two registered per boot): installs a
`ChatRenderer.ViewerUnaware` that appends the cached native component — 0 parse, 0
MiniMessage work per message. Per-message cost ~100 ns vs the original's per-message DB
lookup + MiniMessage deserialize.

### (e) Power tick

Runs **on the writer** every `power.tick-interval-seconds` (default 60 s):

```java
for (int i = eligibleCursor.next(); i >= 0; ) {        // int-array of ordinals below max & not frozen
    double before = power[i];
    double delta  = online.get(i) ? regenOnline : regenOffline;   // Settings-frozen scalars
    double after  = PowerMath.clamp(before + delta, min, max);    // branchless min/max
    if (after != before) { power[i] = after; journal.powerDelta(i, after, REGEN); }
}
```

- The **eligible set** (players below max, unfrozen, source-enabled) is maintained
  incrementally by the writer (players enter on loss, leave on reaching max/freeze), so the
  tick is O(eligible), not O(all known players).
- Journal records are encoded into fixed 64-byte slots of a preallocated SPSC ring — **0
  allocations**; the storage thread coalesces per player (last-write-wins on `power`,
  appends to `power_history`) into one batched `UPDATE`/`INSERT` transaction.
- 50k eligible players ≈ 50k × (~12 B read + clamp + 64 B slot write) ≈ **3–6 ms on the
  writer, zero impact on any game thread**, vs the original's `findAll()` + per-player
  UPSERT on an async pool. Raidable sweep piggybacks: O(factions) over
  `factionPowerCache`/`landCount` arrays, no queries; transitions journal `raidable` and
  emit notify effects. Death/kill/buy/admin power paths are Ops through the same
  `PowerMath.settle()` — **one** place computes the final delta (streak, kill-ratio scale,
  world×zone multipliers, source gating, freeze, event clamp *gated to AUTOMATIC sources
  only*, min/max clamp) and returns `(effectiveDelta, after, reason)`.

---

## 5. Persistence: journal-projected SQL

### 5.1 Write path

- The reducer appends `JournalRecord`s: hot lane = fixed 64-B primitive-encoded slots
  (power, activity, counters, claim set/clear, flag bits, bank balance); cold lane = an
  object queue for variable payloads (names, MOTD, chest blobs, audit details) — rare,
  allocation acceptable.
- **Storage thread** drains, **coalesces by (table, pk, column-set)** — last-write-wins for
  scalars, append-only for `power_history`/`bank_ledger`/`audit_log`/`inbox` — and flushes
  one JDBC transaction every **250 ms or 256 records**, whichever first, as batched
  prepared statements (field-scoped `UPDATE … SET col=?` / keyed upserts — **no full-row
  save exists anywhere**; there is no code path that could write a stale wide row).
- **Durability classes:** `STATE` (coalesced, next flush), `LEDGER` (append batch, next
  flush), `CRITICAL` (bank movement, chest contents, claim/overclaim, faction
  create/disband/merge, ownership transfer): the flush fires immediately and the op's
  confirmation Effect (the "Deposited …" message, the Vault settle) is released **only
  after commit**. Vault ordering: reserve (phase A, main thread) → Op → commit → settle
  message; any writer rejection or commit failure emits a **compensating Vault credit**
  effect — money is conserved on every path.
- Crash window: ≤250 ms of `STATE`/`LEDGER` records (announced honestly in docs); `CRITICAL`
  ops lose nothing user-visible because confirmation is post-commit. `meta.last_applied_seq`
  is written with each flush; the tester's crash drill (kill -9 mid-load) asserts clean
  restart with no constraint violations and no confirmed-op loss.

### 5.2 Schema (v2)

All DDL emitted by `SchemaManager` through one `SqlDialect` (`MySqlDialect`, `H2Dialect`).
Invariants live **in the schema**, not in read-then-check code:

| Table | Key columns | Notes |
|---|---|---|
| `meta` | `k` PK | `schema_version`, `last_applied_seq`, `imported_from` |
| `factions` | `id` UUID PK | `name`, **`name_folded` UNIQUE**, description, motd, owner_id, created_at, power_boost, bank, raidable, shield_start_hour, shield_duration_hours, home_* — flags/relations **not** here |
| `faction_flags` | (faction_id, flag) PK | sparse booleans |
| `relations` | (`a`,`b`) PK, `a<b` CHECK | symmetric effective relation; `wish_a`,`wish_b` columns carry pending wishes |
| `players` | `id` UUID PK | power, power_boost, faction_id, rank_id, title, joined_at, last_activity, last_death_at, death_streak, power_frozen, notify bits, auto_territory_mode, locale |
| `claims` | (`world`,`x`,`z`) PK | faction_id (indexed), access_json |
| `ranks` | id PK | faction_id idx, name, prefix, priority |
| `warps` | id PK | faction_id idx, name (+ UNIQUE(faction_id,name_folded)), world/x/y/z/yaw/pitch, password, use_cost, creator, created_at |
| `invites` | (**faction_id, invitee_id**) PK | inviter_id, created_at — duplicate invites impossible |
| `merge_requests` | (sender_id, target_id) PK | actor, created_at — **created at boot** (the original registered but never created it; fixed) |
| `bank_ledger` | id PK, (faction_id, created_at) idx | append-only; type, amount, actor, counterparty, note |
| `power_history` | id PK, (player_uuid, created_at) idx | delta, reason, power_after |
| `audit_log` | id PK, (faction_id, created_at) idx | action, actor, detail |
| `inbox` | id PK, player_id idx | message, created_at |
| `chests` | (faction_id, name_folded) PK | contents blob (versioned, §5.5), created_at |

### 5.3 Load path

Boot streams every table (fetch size 1024) into the interned structures: factions → ordinals
→ views/flags/rank tables; relations → pair table; claims → shards + per-faction lists +
`landCount`; players → ledger. 3M claims load in single-digit seconds from H2; a progress
line per 500k rows. No lazy loading, no cache-miss path exists afterward.

### 5.4 Migration & legacy import

- **v2 migrations:** numbered, recorded in `meta.schema_version`, applied in order at boot
  (additive `ALTER` preferred; each migration idempotent).
- **Legacy import:** if v2 tables are absent and the original plugin's tables (`board`,
  `factions` with `relations_json`, …) are detected, a one-shot `LegacyImporter` maps them:
  `board.id "world:x:z"` parsed → `claims`; `relations_json`/`flags_json` exploded into the
  join tables (asymmetric ALLY pairs demote to wish state — logic-bug #21 sanitized at
  import); TINYINT booleans mapped; `merge_requests` created. Original tables are left
  untouched; `meta.imported_from` records provenance.

### 5.5 H2/MySQL parity & drivers

Same Hikari settings as the reference (`connectionTimeout` 10 s, `idleTimeout` 600 s,
`maxLifetime` 30 min, pool name `FableFactions-DB`). H2: file
`data/factions`, URL flags `MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE`, pool = 1
writer + 1 read-lane connection; MySQL: pool from `database.yml` (default 10, min idle 2),
UTF-8/UTC URL params. Drivers shaded+relocated, SPI service file excluded,
`driverClassName` set explicitly (`dev.fablemc.factions.lib.h2.Driver` else `org.h2.Driver`
for tests). Upsert divergence isolated in the two `SqlDialect` impls (MySQL
`ON DUPLICATE KEY UPDATE`; H2 `MERGE INTO … KEY(...)`). Because *all* SQL is generated by
the storage module (no ORM), there is no attribute-map NULL-insert trap, no unordered
column list, and pagination for history/audit/bank pushes `ORDER BY created_at DESC LIMIT ?
OFFSET ?` down to SQL on the read lane while preserving observable ordering. Chest blobs:
Base64 `BukkitObjectOutputStream` (universal) **prefixed with the writing server's data
version**; modern servers use `ItemStack#serializeAsBytes` behind a probe
(`ModernItemBlobs`) which self-upgrades via DataFixerUpper; cross-flattening (≤1.12 →
1.13+) chest migration is an explicit `/fa import chests` step, per version-deltas Risk #9.

---

## 6. The platform seam

### 6.1 Capability probe inventory (boot-computed, one immutable `PlatformProfile`)

Tier 1 `Capabilities` (coarse booleans, `Class.forName`/`getMethod`/behavioral):

| Probe | Technique | Gates |
|---|---|---|
| `folia` | `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` | `FoliaScheduling` FQN load, `folia-supported` behavior |
| `adventureNative` | `Component` + `MiniMessage` classes AND `CommandSender.sendMessage(Component)` method | TextPort native sink, `NativeAudience` |
| `modernChat` | `io.papermc.paper.event.player.AsyncChatEvent` | `ModernChatRenderer` vs legacy chat listener (exactly one active) |
| `brigadier` | `io.papermc.paper.command.brigadier.Commands` | `BrigadierDecorator` |
| `flattened` | `Material.getMaterial("WHITE_WOOL") != null` | `Materials` identity vs legacy icon table |
| `hexColors` | server class-version ≥1.16 signal via `net.md_5.bungee.api.ChatColor#of` presence | legacy serializer downsampling |
| `pdc` | `PersistentDataContainer` + `NamespacedKey` by name (descriptor-hazard-safe) | chunk/item marking; lore-steganography fallback |
| `asyncTeleport` | `Entity#teleportAsync` method | teleport engine |
| `onlineCollection` | MethodHandle dual-descriptor resolve of `Bukkit.getOnlinePlayers()` (`Collection` then `Player[]`) | `Players.online()` — **all** iteration goes through it |
| `viewHandles` | `MethodHandles.lookup().findVirtual(InventoryView, …)` | the 1.21 class→interface ICCE killer; shared code never calls view methods directly |
| `clickedInventory` | `InventoryClickEvent#getClickedInventory` method | rawSlot-math fallback |
| `blockExplode` | `BlockExplodeEvent` class (1.8.3+) | probe-registered listener class |
| `armorStands` | `PlayerInteractAtEntityEvent`/`ArmorStand` classes (1.8+) | probe-registered listener class |
| `entityPickup` | `EntityPickupItemEvent` (1.12+) | dual pickup listeners, deduped |
| `swapHands` | `PlayerSwapHandItemsEvent` (1.9+) | GUI slot-40 guard |
| `spectator` | `Enum.valueOf(GameMode, "SPECTATOR")` (sticky-getstatic-safe) | fly/vanish logic |
| `sweepCause`, new `TeleportCause`/`SpawnReason`/`DamageCause` constants | `Enum.valueOf` in try/catch, cached nullable | protection details |
| `titles` | 5-arg `sendTitle` → 2-arg → chat fallback (no NMS) | `Feedback.title` chain |
| `actionBar` | Adventure → Spigot `ChatMessageType.ACTION_BAR` → skip | `Feedback.actionBar` chain |
| `bossBar` | `Bukkit.createBossBar` (1.9+) | warmup progress display |
| `teamApis` | `Team#addEntry`, `Team#setColor` methods; prefix budget 16 vs 64 by `flattened` | `TeamBudget` nametag adapter |
| `minHeight` | `World#getMinHeight` | home/warp Y clamping |
| `mountEvent` | dual `Class.forName` `org.spigotmc.…EntityMountEvent` / `org.bukkit.…` (1.20.3 move) | two tiny listener classes, whichever exists |
| `commandMap` | Paper `Server#getCommandMap` → `CraftServer.commandMap` field reflection | dynamic alias registration |
| `asyncTabComplete` | Paper `AsyncTabCompleteEvent` (~1.12) | off-thread member-name completion |
| `raids` | Raid API classes (~1.15) | probe-registered "no raids in claims" listener |
| `itemBytes` | `ItemStack#serializeAsBytes` | `ModernItemBlobs` |

Tier 2 `PlatformProfile`: typed `Required.owned(name, Feature, resolver)` /
`OptionalSince.resolve(name, since, fallback, note, resolver)` entries (Mental pattern
verbatim). A `Required` miss disables its owning feature loudly; `OptionalSince` misses are
quiet, typed, era-correct fallbacks. One boot-report line: `platform profile — 31/34
handles; text=native|bungee|flat; scheduling=folia|bukkit; features disabled: none`. Probes
resolve into `static final` fields → C2 constant-folds the branches → **seam call sites are
monomorphic** (exactly one `Scheduling` impl, one chat listener, one text sink loaded per
boot).

### 6.2 Era adapter layout

- `platform/` resolvers: universal or probe-chain (list above). Sub-floor-typed symbols are
  hoisted into non-`Listener` helper classes instantiated behind resolvers (the
  listener-descriptor rule, §8.3).
- `compat-folia/`: `FoliaScheduling` only.
- `compat-modern/`: Adventure-native audience, `ModernChatRenderer`, Brigadier decoration,
  `ModernItemBlobs`. Compiled against Paper 1.20.6; reachable only via FQN strings behind
  probes; folded into the shadowJar.
- The 1.7–1.12 band needs **no compiled module**: it is the probe/MethodHandle/fallback
  path of the 1.13-floor code (legacy icon table, tellraw/plain-text rich-chat fallback on
  1.7.10, Team prefix 16-char budget, `PlayerPickupItemEvent`).

### 6.3 Text pipeline (one, exactly one)

MiniMessage strings exist **only in configuration files**. `MessageCatalog` parses each key
**once per reload** into a component *template* (shaded, relocated Adventure); user content
(faction names, MOTDs, titles, warp names) is injected as **literal text nodes**
(`Component.text(...)` / TagResolver placeholder) — structurally injection-proof.
`TextPort` is the sole boundary: it serializes to §-legacy for the universal String sinks,
uses cached MethodHandles to the server's **native** Adventure on modern Paper (the shaded
copy stays inert there), and Bungee `player.spigot().sendMessage` on Spigot 1.8+ to keep
hover/click; 1.7.10 falls to flat § text. Hex downsampled below 1.16. Architecture gate G3:
no `net.kyori` reference outside `TextPort`/`MessageCatalog`; `verifyRelocation` enforces
the shaded copy at the artifact level.

### 6.4 Scheduling facade

Mental's `Scheduling` interface verbatim (`runGlobal`, `runAt(Location)`, `runOn(Entity,
task, retired)`, `runOnLater`, `repeatGlobal/On/Async`, `ensureOn`,
`isOwnedByCurrentRegion`, `describe`), plus the TCK from platform test-fixtures run against
both impls. FableFactions routing: effects → `runOn(player)`; WG/dynmap mirroring →
`runGlobal`; warmup countdowns → `repeatOn(player)` with `retired` cleanup; storage is its
own thread (not the async scheduler — deterministic drain on shutdown).

### 6.5 GUI abstraction

`MenuModel` (kernel): parsed `gui.yml` — slots, materials (modern names), MiniMessage
name/lore keys, glow, actions (`RUN_COMMAND`, `SUGGEST_COMMAND`, `OPEN_MENU`, `REFRESH`,
`CLOSE`, `LANGUAGE_SET`, `LANGUAGE_RESET`), placeholders (`{player}`, `{faction}`,
`{faction_members}`, `{faction_land}`, `{faction_bank}`, `{power}`, `{max_power}`,
`{language_*}`). `InventoryPort` (platform): creates inventories with a custom
`FableMenuHolder` (identity routing — universal 1.7.10→26.x), **String titles only**
(Component overload never needed), icon `ItemStack`s via `Materials` (modern constant on
1.13+, `(legacyEnum, dataByte)` table on legacy — the data-byte constructor appears in
exactly one class). Click handling: `event.getInventory()` + rawSlot arithmetic
(`rawSlot < inv.getSize()` ⇒ top); zero `InventoryView` calls in shared code (gate G4);
number-key/off-hand swaps cancelled behind the `swapHands` probe. Menus are read-only views
over snapshots + command dispatch — no state lives in the GUI.

### 6.6 Command framework 1.7.10 → 26.x

Universal base: plugin.yml `f`/`fa` + `CommandExecutor` + `TabCompleter` (present since
1.7.10). The tree is a declarative `CommandSpec` (kernel): name, aliases, permission,
guards (`requirePlayer`, `requireFaction`, `requireOfficer`, `requireOwner`), typed arg
schema (used for both parsing and tab completion), and an executor id bound in core.
Option parsing (`--action=…`), `MoneyParser` (`k/m/b/t` suffixes), confirm-tokens
(disband 30 s, leader self-confirm) are framework services. Enhancements behind probes:
`AsyncTabCompleteEvent` completion; `BrigadierDecorator` (compat-modern) registers argument
types/suggestions on 1.20.6+ while plugin.yml remains the source of truth; dynamic aliases
via `CommandMaps`. Unrecognized `/f <name>` routes to the TeamsAPI subcommand bridge when
registered (§10).

---

## 7. Protection & event engine

### 7.1 Listener architecture

Listeners are thin **intake adapters**: extract primitives (ints, longs, session ref), call
`AccessEngine.decide`, apply the verdict, resolve the deny message only on deny. Ordering &
registration:

| Intake | Events | Priority | Notes |
|---|---|---|---|
| `MoveIntake` | PlayerMoveEvent (+Teleport for map/fly/warmup) | MONITOR, ignoreCancelled | the single move listener; short-circuit order: same-chunk → session → claim probe |
| `BuildIntake` | BlockBreak, BlockPlace | HIGH, ignoreCancelled | WG-sync mode honored (§10.2): allow-fast-path when WG already enforced at NORMAL |
| `AllyUnlockIntake` | BlockBreak, BlockPlace | HIGHEST, ignoreCancelled=false | WG-sync only: un-cancels for **mutual** allies/override |
| `InteractIntake` | PlayerInteract (containers, doors, buttons, levers, plates, trample PHYSICAL), PlayerInteractEntity | HIGH | config-driven material categories baked to `EnumSet`→`long[]` bitset at parse |
| `DamageIntake` | EntityDamageByEntityEvent | HIGH | **attribution resolver**: Projectile→shooter, TNTPrimed→source, AreaEffectCloud, tamed-pet owner; melee and ranged share one code path |
| `ExplosionIntake` | EntityExplode (+BlockExplode probe-gated) | HIGH | blockList `removeIf` with chunk-decision cache; entity-damage-by-explosion honored via DamageIntake |
| `SpreadIgniteIntake` | BlockSpread(fire), BlockIgnite, BlockBurn | HIGH | |
| `PistonIntake` | BlockPistonExtend/Retract | HIGH | validates **both** source and destination chunks; `getBlocks` vs `getRetractLocation` behind probe (1.7.10) |
| `LiquidIntake` | BlockFromTo, PlayerBucketEmpty/Fill | HIGH | cross-border flow denial |
| `EntityGriefIntake` | EntityChangeBlock (enderman/wither), StructureGrow, Hanging break/place, VehicleDestroy | HIGH | |
| `ArmorStandIntake` | PlayerInteractAtEntity, ArmorStandManipulate | HIGH | probe-gated class (1.8+) |
| `PotionIntake` | PotionSplash (+Lingering/AreaEffectCloudApply probe-gated) | HIGH | PvP rules for ranged effects |
| `PearlIntake` | PlayerTeleportEvent(ENDER_PEARL + probed causes) | HIGH | claim-border pearl rules |
| `PickupIntake` | PlayerPickupItemEvent always; EntityPickupItemEvent probe-gated, deduped | — | |
| `RaidIntake` | RaidTriggerEvent | HIGH | probe-gated (1.15+): no raids in claims |
| `JoinQuitIntake` | Join/Quit | NORMAL/MONITOR | session create Op / evict Op; `lastActivity` stamped both ways (kills the dead-column bug) |
| `DeathIntake` | PlayerDeathEvent | MONITOR, ignoreCancelled | captures primitives on the region thread → `PowerDeathOp` |
| `ChatIntake` / `ModernChatRenderer` | AsyncPlayerChatEvent / AsyncChatEvent | HIGHEST | exactly one active per boot |
| `ChestIntake` | InventoryClose/Click/Drag | NORMAL | shared-live-inventory sessions (§7.3) |

Default deny in claimed land; every intake funnels through the **one** `AccessEngine`.

### 7.2 Probe-gated-listener-class rule (structural)

Any listener whose method/field descriptors mention a post-1.7.10 Bukkit type lives in its
**own class**, registered only behind the corresponding probe (Bukkit reflects over every
declared method at registration; one absent descriptor type silently kills the whole
class). Enforced twice: (1) build gate **G5** scans every `Listener` class's constant
pool/descriptors against a whitelist of 1.7.10-era types, failing the build otherwise;
(2) the live matrix's console scan (D-9) fails on `has failed to register events` /
`Could not pass event` / framed linkage errors. All post-floor enum constants resolve via
`Enum.valueOf` into nullable statics — no sticky `NoSuchFieldError` can exist.

### 7.3 Faction chests without duplication

One **live shared `Inventory` per (faction, chest)** held by `ChestSessions` (core):
first opener materializes it from state (54 slots), subsequent openers get *the same
instance* (Bukkit then handles viewer concurrency); a viewer refcount persists contents via
a `ChestCommitOp` (CRITICAL) on last-close **and** on a 10 s dirty timer while open;
`onDisable` force-closes and commits all sessions. Two divergent copies of one container
can never exist — the dupe is structurally gone.

### 7.4 Teleport engine (first-party)

`/f home` and `/f warp`: warmup (`teleport.warmup-seconds`, cancel-on-move ≥1 block /
cancel-on-damage), cooldown, and a combat tag (`teleport.combat-tag-seconds`, set by
DamageIntake on PvP) — config-gated, defaults tuned to match the reference *when Essentials
is present* (delegation still available via `integrations.essentialsx.enabled`, including
jail checks), but the balance rule no longer depends on an optional plugin. Charges
(warp `use_cost`) are **reserved and settled on confirmed teleport**, refunded on any
failure/cancel path. Warps/homes must be inside the owning faction's territory at set time
**and are re-validated at use** (overclaimed/unclaimed destination ⇒ deny with message).
Fly: enable requires own-territory (radius-checked, `fly.enemy-radius`), `MoveIntake`
revokes on leaving eligibility, and any revocation grants a fall-damage immunity window
(next-fall cancel) — the knobs the original shipped dead (`fly.disable-on-threat`) are live.

---

## 8. Config system & messages

### 8.1 Typed atomic Settings

One immutable `Settings` object parsed whole, swapped by a single volatile reference via a
`ReloadOp` on the writer (readers freeze the reference once per operation — no torn reads).
Sections are records with `DEFAULTS` constants, `warn-and-fallback` parsing, and
**parse-equality unit pins** (empty section == era baseline). Files: `config.yml`
(factions.*, integrations.*), `database.yml` (restart-only, documented), `roles.yml`,
`gui.yml`, `notifications.yml`, `pre-defined.yml`, `messages/messages_<locale>.yml` × 8
shipped locales. Every knob of the reference is represented, including the formerly-dead
ones now implemented (`land.buffer-zone` — enforced on **every** claim path;
`economy.cost-create`/`cost-claim`; `fly.disable-on-threat`) and the doc-vs-resource
discrepancies resolved per resource (max-members 50, updates on, `land.per-power`/`max`
added). **One canonical value per tunable**: alias keys (`power.loss-on-death` vs
`power.sources.death-loss.amount`; `max-power` vs `constraints.max-power`) are collapsed at
parse into one canonical field with a boot warning when both are set and disagree —
divergent-key bugs cannot recur. Hot-path-relevant settings are additionally **baked** into
primitive tables at parse (zone verdict bits, interactable material bitsets, power scalars)
so no event ever walks a config object. GUI/API writes go to a machine overlay
(`state/overrides.yml`; effective = overlay ?? file ?? default) so hand-edited, commented
YAML is never re-serialized.

### 8.2 Messages / i18n

`MessageCatalog`: 27 top-level groups × 8 locales (`en, es, de, fr, pt-BR, zh, ru, ja`),
same keys as the reference (full inventory in pvp-resources §9), waterfall preferred →
default → en → inline fallback; `normalizeLocale` rules preserved (`pt-br`→`pt-BR`);
per-player locale column + `/f language` + GUI language menu. Templates pre-parsed once
(§6.3); `{token}` placeholders become TagResolvers; user strings inserted as literals.
Name validation at the domain edge: `[A-Za-z0-9_-]{3,32}` (per messages spec), `§`/control
chars rejected — enforced in kernel `NameRules`, tested.

---

## 9. Feature lifecycle (reconciler)

Every optional subsystem is a `FeatureUnit` (descriptor + `assemble(Scope, Settings)`):
claims, protection, power, economy+tax, chat, map, GUI, chests, warps+teleport engine,
notifications+inbox, audit, update-notifier, predefined factions, and each integration.
`Scope` collects every acquired resource (`scope.listen(listener)`, `scope.task(handle)`,
`scope.service(closeable)`); disable = close scope whole (reverse order, failures
suppressed-collected); a mid-assemble throw closes the partial scope → feature OFF,
zero-touch. `/fa reload` = parse → swap Settings → `reconciler.converge()`. There is no
`start()`-over-live-state path — the reload-listener-leak class is unrepresentable.
`PlatformProfile.disabledFeatures` are never converged.

---

## 10. Public API & integrations

### 10.1 Public API (`:api`)

`FactionsApi` registered in Bukkit `ServicesManager`: read-only queries served from
snapshots (`factionOf(uuid)`, `claimOwner(world,x,z)`, `relationBetween`, `power(uuid)`,
views), mutation entry points that submit Ops and return `CompletionStage<Result>`. The
seven Bukkit events (§1) preserve the reference semantics (cancellable set identical;
`FactionBankTransactionEvent.setAmount` honored **including on TRANSFER** — fired pre-commit,
fixing the uncancelable-transfer bug). japicmp `apiCompat` gate: grow-only against a
committed baseline jar.

### 10.2 Integration adapters (all soft-depend; facade + Noop + factory; probe = config
toggle AND plugin present; one INFO line each way)

| Integration | Isolation | Design |
|---|---|---|
| **Vault** | typed impl behind `Class.forName("net.milkbowl.vault.economy.Economy")` guard class | `EconomyPort` with `reserve/settle/refund` — OfflinePlayer overloads only; consumed by bank, power buy, warp cost, create/claim costs. All charge paths are reserve→apply→settle with compensation (§5.1). |
| **WorldGuard (+WorldEdit)** | typed `WorldGuardTerritoryGuard` behind factory; `NoopTerritoryGuard` default | `TerritoryGuard` checks for warp/home placement; **region-sync mode** (`worldguard-sync-regions`): claims mirrored as `ProtectedCuboidRegion`s, members added to domains, WG enforces at NORMAL, `BuildIntake` fast-allows synced regions, `AllyUnlockIntake` (HIGHEST, ignoreCancelled=false) un-cancels for mutual allies. Mirroring driven by post-commit effects; `syncAll` at boot. Restart to toggle (documented). |
| **PlaceholderAPI** | typed `FactionsPlaceholders` behind presence check | expansion id `fablefactions` (+ legacy `pvpindex` param aliases for drop-in parity): faction_name/power/members/land/bank, player_power/role/role_prefix — all O(1) snapshot reads (PAPI calls can be hot). |
| **EssentialsX** | typed interop behind factory | jail check, vanish check, optional teleport delegation; first-party warmup remains the default engine. |
| **dynmap** | typed `DynmapLayer` | marker set from snapshot at boot (+1 tick), incremental updates from post-commit claim/unclaim/disband/rename effects, reference color palette preserved; all dynmap API calls on `runGlobal`. |
| **DiscordSRV** | **reflection-only** notifier (JDA 4 message send) | faction-created/disbanded, relation ally/truce/enemy messages, channel-id config; fed by post-commit effects. |
| **LWC/LWCX** | **reflection-only** interop | protection-creation gating by faction build rights, stale-protection removal on interact, bulk cleanup on claim-ownership-change effects; `unregister()` on scope close. |
| **TeamsAPI** | reflective `TeamsApiRegistrarImpl` (FQN-gated), optional services registered reflectively per TeamsAPI version gates (1.5→2.5) | Teams/Invite/Warp/Claim/Power/Relation/Chest/Notification/PowerHistory services adapt to Ops + snapshots; role registry + `RoleChangeNotifierHolder` pattern preserved; subcommand bridge on `/f`; **both** power totals (with and without inactivity exclusion) preserved as in the reference. |
| **EzCountdown** | facade (no imports) → reflectively-loaded typed impl | relation announcements to displays; `notifications.yml` keys preserved. |
| **bStats** | shaded, relocated | charts: created/total factions, relation drilldown, database type — all fed from in-memory counters (no DB scans; the 15-min relation refresh becomes a snapshot walk). |

`softdepend` list and `loadbefore` preserved verbatim from the reference plugin.yml.

---

## 11. Build pipeline (the Mental recipe, adapted)

**Kept verbatim** (with `me/vexmc/mental` → `dev/fablemc/factions` substituted):
Gradle wrapper 9.5.1 + foojay resolver; catalog `shadow 9.4.2`, `jvmdowngrader 1.3.6`,
`run-paper 3.0.2`; toolchain JDK 25 (= `max(jdk)` from the matrix), `options.release = 17`,
`-parameters`, UTF-8; version only in `gradle.properties`; shadowJar staged as `-modern`
into `build/jvmdg-stage` (never `build/libs`), no `minimize()`; jvmdg `DowngradeJar`
(`downgradeTo = VERSION_1_8`, `multiReleaseOriginal = true`, **never**
`multiReleaseVersions` — mutually exclusive in 1.3.6; classpath = union of core +
compat-folia + compat-modern compile classpaths for supertype resolution); jvmdg `ShadeJar`
with `shadePath = "dev/fablemc/factions/lib/jvmdg/"` emitting the canonical jar;
`failOnJvmdgWarnings` with the global-console-capture mitigations (`mustRunAfter
:api:apiCompat`, Unsafe-note filter); all four gates wired into `check`:
`verifyDowngrade` (base ≤ v52; `versions/17` = v61 subset; sentinel
`dev/fablemc/factions/FableFactionsPlugin.class` forked 52/61; no jvmdg-runtime ref in
base-only classes; H4 record-reflection scan), `verifyJdk8Api` (ASM gate vs a real
provisioned JDK 8, **empty allowlist**), `verifyRelocation` (no unrelocated `net/kyori`,
`com/zaxxer`, `org/h2`, `com/mysql`, `org/bstats` entries or references outside the lib
prefix), `verifyTesterIsolation` (distinct tester jvmdg prefix; no unrelocated stub FQN in
any method descriptor); tester mega-jar with its own prefix; `support-matrix.json` as the
single source of truth (floorApi, per-entry jdk/platform/suites/ci/bytecodeTier); live
matrix with per-boot nonce, per-entry JDK launchers, `-Dfable.tester.tier` assertion, and
the **D-9 console scan** (any swallowed listener-registration/handler/linkage signature ⇒
FAIL).

**Changed, and why:**

1. **Compile floor API = Spigot 1.13.2** (Mental used Paper 1.17.1): factions listeners
   live in ancient APIs; a 1.13 floor confines probing to the 1.7–1.12 band + modern
   extras (§1). `api-version: '1.13'` in plugin.yml (ignored pre-1.13, accepted 1.13→26.x,
   avoids the Commodore shim that would fight our modern-name kernel); **not**
   paper-plugin.yml.
2. **Matrix extended down**: entries for 1.7.10 and 1.8.8 (Java 8 rungs, `bytecodeTier: 52`)
   with a one-time ladder-probe task for their flagless modern-JVM behavior (version-deltas
   Part 4 UNCERTAIN items 1–3); 1.9.4–1.12.2 on Java 21 (tier 61), 1.13.2–1.16.5 on their
   caps (tier 52), 1.17.1+ (tier 61), Paper + Folia 26.1.2 ceiling.
3. **Relocation set**: adventure, HikariCP, H2, mysql-connector-j, bStats (Mental's
   PacketEvents relocation dropped — no packets in a factions plugin). JDBC SPI files
   excluded; drivers named explicitly (§5.5).
4. **`verifyJdk8Api` ignore list** extended with the integration provided-API packages
   (`net/milkbowl/vault`, `com/sk89q`, `me/clip`, `com/earth2me`, `org/dynmap`,
   `github/scarsz/discordsrv`, `com/griefcraft`, `com/skyblockexp`) — server-provided,
   never bundled.
5. **compat-modern joins compat-folia** in the shadow fold-in + downgrade-classpath union
   (Mental only had folia).
6. **Extra `check` gates G1–G8** (§13) including the listener-descriptor scanner — a gate
   Mental enforced only by convention+matrix; we make it static.
7. japicmp baseline starts at `api-1.0.0` built from the first release tag (same worktree
   provenance/sha-pin discipline).

---

## 12. Feature → module mapping (requirement-1 proof)

| Reference feature (docs) | FableFactions home |
|---|---|
| `/f create/disband/leave/kick/promote/demote/leader/rename/desc/motd` | `core.command` specs → `Ops.Faction*` on writer; math/validation in kernel (`NameRules`, `RankTable`) |
| `/f role list/create/rename/setpriority/setprefix/delete/assign` (+roles.yml gates, priority bounds, builtin protection, TeamsAPI mirroring) | `Ops.Role*` + kernel `RankAuthority`; `RoleChangeNotifierHolder` in api-bridge |
| `/f claim one/auto/square/circle/fill/nearby/at`, `/f unclaim …/all`, overclaim (raidable/offline-protection/war-shield/enemy-relation gates), border adjacency, buffer zone, land costs | kernel `ClaimPlanner` (ONE planner used by every mode incl. admin + merge + auto) + `LandMath`; `Ops.Claim/Unclaim`; `MoveIntake` auto-mode |
| Safezone/Warzone (`/fa safezone|warzone`, zone rules) | ordinals 1/2 in `ClaimIndex`; zone verdict table in `AccessEngine` |
| Protection: build/PvP/explosion/fire (+ event-complete extensions) | §7 intakes → `AccessEngine` |
| Power: regen on/offline, death loss + streak, kill gain + ratio scaling, world/zone multipliers, freeze, min/max, per-event clamp, grace period, inactivity exclusion, buy, admin set/add/remove/reset/freeze/history, `[Power]` staff debug | kernel `PowerMath.settle` (single source); `Ops.Power*`; writer tick; `power_history` journal lane |
| Raidable transitions + broadcasts (member + server-wide) | writer sweep over cached arrays; effects |
| Bank deposit/withdraw/transfer/history + periodic tax (+ notify, min-balance, min-charge, 2-dp rounding) | `Ops.Bank*` (CRITICAL durability, Vault reserve/settle/refund), kernel `TaxMath`, writer tax timer, `bank_ledger` |
| Faction chests create/delete/list/open (54 slots, Base64 blobs, caps) | `ChestSessions` shared-live-inventory + `Ops.ChestCommit`; `ItemBlobs`/`ModernItemBlobs` |
| Invites send/accept/decline/declineall/list/revoke + TTL + join notifications | `Ops.Invite*`; TTL pruned by writer timer; schema PK dedupe |
| `/f join` (open flag, member cap), merge send/accept (cap-respecting; overflow rejected loudly — divergence from reference bug documented) | `Ops.Join`, `Ops.Merge` (single atomic op on writer) |
| Relations ally/truce/enemy/neutral + wish handshake + limits + list/wishes + announcements (chat/EzCountdown/DiscordSRV) | `RelationGraph` + wish state; `Ops.RelationWish`; fan-out effects |
| Home/sethome/unsethome, warps (set/delete/list/password/cost, caps), teleports | `Ops.Home/Warp*`; teleport engine §7.4 |
| Fly (+ territory eligibility, revocation, fall immunity) | session flag + `MoveIntake` + `Ops.FlyToggle` |
| Map (`/f map on/off/once`, size), territory-enter titles/hover info, per-player notify toggles | `MapRenderer` over snapshot probes; `MoveIntake`; notify bits in ledger/session |
| Chat faction tag (Paper renderer / legacy format) | §4(d); `ChatIntake` / compat-modern renderer |
| `/f list`, `/f top` (sorts power/land/bank), `/f info` (+page), `/f audit` (+`--action` filter), `/f powerhistory` | snapshot sorts (cold, on-demand) + SQL read-lane pagination for history/audit |
| Inbox / offline notifications (max-per-login, deliver-then-delete) | `inbox` table + `Ops.InboxDeliver` (delete-by-delivered-id-set) |
| MOTD-on-join, invite summary on join | `JoinQuitIntake` effects |
| `/f language` + GUI language menu + 8 locale bundles | `MessageCatalog` §8.2 |
| GUI (`gui.yml` menus, actions, placeholders) | `MenuModel` + `InventoryPort` §6.5 |
| Predefined factions (`pre-defined.yml`, create/claim/sethome/list/reload, seeding, disband-block) | `PredefinedCatalog` (Settings sibling) + `Ops.Predefined*` |
| Admin: bypass/claim/unclaim/disband/reload/shield/flag/audit/power | admin `CommandSpec` tree → same Ops with admin source flags |
| War shield (UTC rolling window) | kernel `ShieldWindow` (pinned math) |
| Update checker (Modrinth/GitHub chain, op join notice) | `UpdateFeature` (async pool; volatile latest) |
| bStats, PlaceholderAPI, dynmap, DiscordSRV, WorldGuard(+sync), Essentials, LWC, TeamsAPI, EzCountdown, Vault | §10.2 |
| database.yml knobs, H2/MySQL, driver shading | §5.5 |
| All config keys incl. formerly-dead ones | §8.1 (dead ones implemented; parity table in Settings javadoc) |

---

## 13. Testing strategy

1. **Kernel pins (pure JUnit, no server):** power settle (streak escalation ×
   `multiplier^streak`, kill ratio clamp, world×zone multipliers, AUTOMATIC-only event
   clamp, min/max, freeze matrix), `computeMaxLand` truncation + inactivity window,
   adjacency (4-cardinal, first-claim, enemy-surround), buffer zone, shield midnight wrap,
   tax rounding/min-balance/min-charge, invite TTL, money parser, name rules, chunk-key
   packing, claim-shard COW semantics, relation pair-key symmetry, rank ordering.
2. **Property tests (linearizability):** random command sequences submitted from N threads
   through the real MPSC+writer vs the same sequence applied to a sequential model —
   final states must match; invariants (land ≤ maxLand, members ≤ cap, unique names, bank
   conservation across deposit/withdraw/transfer/tax) asserted after every step.
3. **Parse-equality config tests:** empty YAML section ⇒ DEFAULTS record; alias-key
   collapse; overlay precedence.
4. **Architecture gates (fail `check`):** G1 kernel Bukkit-free (dep guard + classpath
   test); G2 no `java.sql`/Hikari types outside `core.storage`; G3 no `net.kyori` outside
   TextPort/MessageCatalog; G4 no `InventoryView` invocation anywhere; **G5 listener
   descriptor scanner** (every registered Listener's method/field descriptors ⊆ 1.7.10-safe
   set unless the class is on the probe-gated registry); G6 no `Bukkit.getOnlinePlayers()`
   call outside `platform.Players`; G7 no `synchronized`/`java.util.concurrent.locks` in
   `core.listener`/`kernel` (wait-free-reads claim kept honest); G8 no direct enum
   `getstatic` of post-1.7.10 constants (constant-pool scan against a table).
5. **Allocation & latency benchmarks (JMH, CI-tracked):** the five hot paths measured with
   `ThreadMXBean#getThreadAllocatedBytes` — asserted **0 bytes/op** for (a)(b)(c)(e) and
   ≤1 String for (d); claim-lookup throughput floor asserted (≥20M ops/s on CI hardware).
6. **Scheduling TCK** run against BukkitScheduling and FoliaScheduling (retired-contract).
7. **Live matrix (tester jar):** every support-matrix entry boots real Paper/Folia with the
   shipped mega-jars, per-entry JDK, nonce-stamped results; suites assert: loaded bytecode
   tier == matrix `bytecodeTier`; listener counts per HandlerList; **reload leak-freedom**
   (5× `/fa reload` ⇒ identical handler counts, identical tick work); protection smoke
   (build/pvp/explosion/bucket/piston in claimed land); crash drill (kill −9, restart,
   assert no confirmed-op loss). **Console-swallow CI gate:** the harness greps captured
   server logs for `has failed to register events for class dev\.fablemc\.factions`,
   `Could not pass event .* to FableFactions`, and `NoSuchFieldError|NoSuchMethodError|
   NoClassDefFoundError` with following frames naming `dev.fablemc.factions` — any hit
   downgrades PASS to FAIL (Mental D-9, cloned).
8. **Artifact gates:** verifyDowngrade / verifyJdk8Api (empty allowlist) / verifyRelocation
   / verifyTesterIsolation; japicmp on `:api`.

---

## 14. How each catalogued bug class is made impossible

Concurrency catalog (pvp-bugs-concurrency.md):

| Bug # | Class | Structural kill |
|---|---|---|
| 1 | Full-row save clobbers concurrent writes | **No full-row write exists.** Journal ops are field-scoped; the storage layer has no "save(model)" API to misuse. Hot counters (power, bank) never share a write unit with cold fields. |
| 2, 4, 8 | Non-atomic RMW on power/bank; two-copy updates | All mutation on one thread; `PowerMath.settle` does read→compute→write→history as one writer step. Two copies of an entity cannot exist — there is one ledger. |
| 3 | Reload leaks listeners | Reload = Settings swap + scope reconvergence; `start()`-over-live-state is not a code path. Tester asserts handler counts across 5 reloads. |
| 5, 17 | Claim lock removed in `finally` | There are no locks to mismanage; claims serialize on the writer. |
| 6, 18, 21(conc) | Land-cap / member-cap / invite TOCTOU | Check and act are the same thread (writer re-validation); schema PKs (invites, names) back-stop. |
| 7 | Team-chest divergent copies | Single shared live Inventory per chest (§7.3). |
| 9 | Blocking DB on game thread | Memory-authoritative + gate G2: no JDBC type is reachable from listener/command packages. Explosions are O(blocks) integer work. |
| 10 | Inbox delete-before-deliver / truncation loss | `InboxDeliverOp` reads ≤max rows, delivers, deletes **exactly the delivered id set** post-delivery. |
| 11, 12 | Charge-then-ignore-result; deposit lost on save failure | reserve→apply→settle with compensation; CRITICAL confirmation post-commit; cost computed from `settle()`'s effective delta. |
| 13 | In-flight writes vs pool close | Ordered shutdown with drain + flush barrier (§3.5). |
| 14 | flyState leak / reload wipe | Fly is session state, evicted on quit, re-derived on join; sessions survive reload (reload doesn't rebuild the state engine). |
| 15 | Open chests unflushed | Dirty timer + forced commit on scope close/disable. |
| 16 | relations_json partial mirror | Symmetric pair-key storage; a half-applied mirror is unrepresentable. |
| 19 | Bukkit API from async | Writer never touches Bukkit; effects route through Scheduling (compile-scoped: `core.engine` has no Bukkit imports except the Effect types marshalled out). |
| 20 | Folia timer delay ≤0; wrong-region fallback | Facade clamps ≥1 tick; `runOn` has the retired contract; there is no "global fallback that touches entities" primitive. |
| 22 | Transfer event after commit | All bank events fire pre-submit (phase A), cancellation/mutation honored uniformly. |
| 23 | last_activity never written | Stamped by JoinQuit Ops; kernel pin asserts the inactivity exclusion actually excludes. |

Logic catalog (pvp-bugs-logic.md):

| Bug # | Kill |
|---|---|
| 1 | `PowerMath.settle` is the only delta computation; DEATH/KILL take the caller-computed effective base — a config-override path doesn't exist; streak pin test. |
| 2 | Event clamp applies only to source class AUTOMATIC (regen/death/kill); ADMIN_*/BUY exempt; pinned. |
| 3, 4 | Same as concurrency 2/4 — single writer + settle. |
| 5 | DamageAttribution resolves projectile/TNT/cloud/pet to the true attacker before any rule. |
| 6 | Event-complete intake matrix (§7.1), default-deny, one AccessEngine. |
| 7 | Templates parsed once; user text inserted as literal components; `NameRules` allowlist. Injection has no syntax channel. |
| 8 | = concurrency 7. |
| 9 | = concurrency 11. |
| 10 | `ClaimPlanner` is the single path for one/auto/square/circle/fill/nearby/at/admin/merge; overclaim adjacency requirement and buffer-zone are planner rules (config-gated), so no mode can skip them. |
| 11 | Merge is an Op that re-runs join/claim invariants; overflow ⇒ rejection with message (divergence from reference bug documented in MIGRATION.md). |
| 12 | Warp/home set requires standing inside own claim (coordinate form admin-only); use-time revalidation. |
| 13 | First-party warmup/cooldown/combat tag (§7.4). |
| 14 | Charge settles only on confirmed teleport; refund on every failure path. |
| 15 | Withdraw/transfer gated by configurable min-rank (default officer+); deposit open. |
| 16 | Fly revocation grants fall-immunity; eligibility re-checked on move with radius. |
| 17 | = concurrency 5. |
| 18 | `RankTable` invariant: sorted by priority; promote/demote walk priorities, not list order. |
| 19 | Single MoveIntake null-checks `getTo()` first. |
| 20 | Kick resolves offline members via the name↔UUID cache (fed by joins + member records). |
| 21 | Mutual-by-construction relations; one-sided ALLY unrepresentable (import sanitizes). |
| 22 | One canonical max-power Setting; aliases collapse at parse with disagreement warning. |

---

## 15. What is novel here (SOTA claims, stated as testable properties)

1. **Wait-free protection plane.** No event handler acquires a lock or allocates: claim
   lookup ≤2 volatile reads + ≤4 probes; verified by gate G7 (no monitors in listener
   packages) and JMH allocation assertions (0 bytes/op for paths a, b, c, e).
2. **Single-writer linearizable domain.** Every catalogued TOCTOU/lost-update bug is
   killed by one mechanism, not twenty-three ad-hoc fixes; verified by the multi-threaded
   property suite comparing against a sequential model.
3. **Storage as projection.** The DB cannot diverge from memory because it is *derived*
   from the journal; there is no read path from SQL after boot and no full-row write API.
   Verified: gate G2 + crash drill (no confirmed-op loss; ≤250 ms unconfirmed window).
4. **COW-sharded claim index.** O(1) reads over millions of claims at ~20 B/claim with
   ~6 KB copy cost per mutation — measured: ≥20M lookups/s single-thread floor in CI,
   ≤80 MB steady heap at 3M claims (heap-dump assertion in the tester).
5. **Interned relation matrix.** Player↔player relation = 2 field reads + 1 probe; no
   JSON, no strings, symmetric by construction.
6. **Prerendered identity text.** Chat tags and nametag fragments are computed at
   change-time, not message-time: chat cost is one String concat on legacy, zero parse on
   modern; measured in JMH.
7. **Probe-constant seams.** All version divergence resolves to `static final` state at
   boot; hot seam call sites are monomorphic (single loaded impl per boot) — JIT-verifiable
   via `-XX:+PrintInlining` in the perf harness, structurally enforced by FQN-gated compat
   modules.
8. **One artifact, nineteen years of servers.** v52 base + v61 `META-INF/versions/17`
   mega-jar loads flagless from PaperSpigot 1.7.10 (Java 8) through Paper/Folia 26.1.2
   (Java 25); the live matrix asserts the loaded tier per entry and fails on Bukkit's
   swallowed-error signatures — silent degradation is a build failure, not a field report.
9. **Static listener-descriptor gate (G5).** The classic "protection silently registered
   zero handlers" failure is caught at *compile/check time* by scanning listener
   descriptors against the floor type set — to our knowledge no shipping factions plugin
   enforces this statically.
10. **Bounded main-thread budget.** For 1,000 online players at 20 move events/s each,
    total main/region-thread cost of the movement plane is ~20k × ≤40 ns ≈ **0.8 ms/s**
    (0.004% of one thread) — asserted as a regression budget in the perf harness.

---

## Appendix A — hot-path cost budget (design targets, CI-enforced)

| Path | Reads | Allocations | Target latency | Backing structure |
|---|---|---|---|---|
| same-chunk move | 4 int compares | 0 | <5 ns | ints from Location |
| chunk-cross claim lookup | 1 UuidIntHash + 1 volatile + ≤2 probes | 0 | 15–40 ns | `ClaimShard` COW |
| relation lookup | 2 acquire reads + 1 probe | 0 | 5–20 ns | `LongByteHash` snapshot |
| protection decision | ≤4 cache misses | 0 | 30–60 ns | AccessEngine over snapshots |
| chat message | 1 acquire read | 1 String (legacy) / 0 (modern) | ~100 ns | prerendered session tag |
| explosion (300 blocks) | 300 probes + cache | 0 | <20 µs | chunk-decision cache |
| power tick (50k eligible) | SoA scan | 0 (ring slots) | 3–6 ms on writer | `PlayerLedger` + journal ring |
| claim mutation | shard copy ~6 KB | 1 shard + 1 op | <10 µs writer | COW publish |
| SQL flush | batched statements | batch-local | ≤250 ms cadence | storage thread |

## Appendix B — memory budget (3M claims, 10k factions, 1M known players, 1k online)

| Structure | Estimate |
|---|---|
| ClaimIndex shards | ~60 MB |
| Per-faction claim lists | ~24 MB |
| PlayerLedger (SoA) | ~44 MB |
| FactionView[] + rank tables + relation snapshot | ~8 MB |
| Sessions (1k online) | <1 MB |
| Journal rings + MPSC queue | ~2 MB fixed |
| **Total steady state** | **~140 MB** — declared, measured by tester heap assertion |
