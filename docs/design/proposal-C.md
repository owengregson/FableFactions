# FableFactions — Proposal C: The Deterministic Kernel

**Stance: correctness-by-construction IS performance.** All factions state lives in one
immutable, pure-JDK value. It is mutated by exactly one thread, through one ordered pipeline
of `Intent` records, producing an ordered stream of `Effect` records. Everything else —
protection checks on 1000 players' events, chat tags, PlaceholderAPI, dynmap — is a lock-free
read of a published immutable snapshot. Storage is a projection of the effect stream, never an
authority. Every catalogued race in `ref-bugs-concurrency.md` is a read-modify-write against
shared mutable state or a dirty cache; this architecture contains **zero** shared mutable
domain state and **zero** caches-of-record, so those bug classes are not "avoided" — they are
unexpressible.

Package root `dev.fablemc.factions`, plugin `FableFactions`, commands `/f` + `/fa`.
One Multi-Release mega-jar, PaperSpigot 1.7.10 → Paper/Folia 26.1.2.

---

## 0. Reading guide

| § | Contents |
|---|---|
| 1 | System overview diagram |
| 2 | Module graph + build-time enforcement |
| 3 | Threading & state model, Folia mapping, backpressure, latency/throughput proof |
| 4 | Kernel state shape, full Intent/Effect vocabulary, reducer, determinism |
| 5 | The five hot paths: data structures + allocation story |
| 6 | Persistence: journal + projector, schema, load, migration, H2/MySQL parity |
| 7 | Platform seam: probes, adapters, text, scheduling, GUI, commands |
| 8 | Event/protection engine |
| 9 | Config system, messages/i18n |
| 10 | Public API + integration adapters |
| 11 | Build pipeline (Mental recipe: keep/change/why) |
| 12 | Feature→module mapping (requirement-1 proof) + deviation register |
| 13 | Testing strategy |
| 14 | How each catalogued bug class is made impossible |
| 15 | What is novel — SOTA claims as testable properties |

---

## 1. System overview

```
                        ┌────────────────────────────────────────────────────┐
 Bukkit/Folia events    │                     READ PLANE                     │
 (any region thread) ──▶│  KernelSnapshot (AtomicReference, immutable)       │
 commands, PAPI, chat   │  ClaimAtlas · MemberDirectory · FactionArena ·     │
                        │  RelationTable · ConfigImage · MessageCatalog ref  │
                        └────────────────────────────────────────────────────┘
                                   ▲ publish (one volatile store per batch)
        mutations                  │
 ───────────────────┐   ┌──────────┴───────────────────────────────────────┐
 IntentEnvelope ────▶│   │  WRITE PLANE — single writer thread              │
 (MPSC ring, 64Ki)   │   │  "fable-kernel" : drain ≤1024 → Reducer.apply()  │
                     └──▶│  (pure fn) → new KernelState + List<Effect>      │
                         └──────────┬───────────────────────────────────────┘
                                    │ Effect stream (ordered, seq-numbered)
             ┌──────────────┬───────┴────────┬──────────────┬───────────────┐
             ▼              ▼                ▼              ▼               ▼
        EffectJournal   StorageProjector  FeedbackRouter  Integration    ApiEventBridge
        (WAL, fsync     (async JDBC       (Notify/Reject  subscribers    (Bukkit events,
         group commit)   batches, H2/      → Scheduling   (dynmap, WG    TeamsAPI mirror)
                         MySQL)            .runOn)         sync, Discord,
                                                            LWC, bStats)
```

Two invariants carry the whole design:

* **I-1 (single writer):** only the `fable-kernel` thread ever constructs a new `KernelState`.
  No other code path can mutate domain state, because there is no mutable domain state to touch.
* **I-2 (projection):** every observer — SQL rows, dynmap markers, Discord posts, Bukkit
  monitor events, chat messages — is a function of the effect stream. Nothing ever reads the
  database to make a game decision after boot.

---

## 2. Module / Gradle-project graph

```
settings.gradle.kts:
include(":kernel", ":api", ":platform", ":core", ":compat-folia", ":compat-modern", ":probe")
project(":compat-folia").projectDir  = file("compat/folia")
project(":compat-modern").projectDir = file("compat/modern")
```

| Module | Sees Bukkit? | Compile classpath | Contents |
|---|---|---|---|
| `:kernel` | **No — build-fails if it tries** | pure JDK + JUnit/jqwik | `KernelState`, all Intent/Effect records, `Reducer`, `Verdicts` (protection decisions), `PowerMath`, `LandMath`, `ShieldWindow`, `TaxMath`, `ChunkKeys`, `RelationRules`, `NameRules`, `MoneyMath`, `ClaimAtlas`, `MemberDirectory`, `ConfigImage`, `MessageKey` |
| `:api` | compileOnly paper-api 1.13.2 | `:kernel` | public surface `dev.fablemc.factions.api.*`: `FableFactionsApi`, read views, request builders, the 8 public Bukkit events, `FactionsPlaceholderSource`. japicmp-gated once 1.0 ships |
| `:platform` | Yes (floor) | `:kernel` (api), compileOnly paper-api 1.13.2 | the seam: `Scheduling`, `Capabilities`, `PlatformProfile`, `TextPort`, `Players`, `Views`, `LegacyMaterials`, `Feedback`, `ItemCodec`, `Nametags`, `TaskHandle`, `Scope` |
| `:core` | Yes (floor) | `:api`,`:platform`,`:kernel`; compileOnly paper-api 1.13.2; impl: HikariCP, H2, mysql-connector-j, adventure(+minimessage,+legacy-serializer), bstats | boot, `IntentBus`, writer thread, `EffectJournal`, `StorageProjector`, listeners, command trees, GUI manager, integration adapters, config parser, message loader |
| `:compat-folia` | Yes (modern) | compileOnly `:platform`, compileOnly paper-api 1.20.4 (Folia symbols) | `FoliaScheduling` only. Folded into shadow jar; loaded by FQN string behind the Folia probe |
| `:compat-modern` | Yes (modern) | compileOnly `:platform`,`:core` interfaces, compileOnly paper-api 1.20.6 | `ModernChatRenderer` (AsyncChatEvent), `AdventureAudiencePort`, `BrigadierInstaller`, `ModernItemCodec` (`serializeAsBytes`). Probe-gated classes only |
| `:probe` | Yes | compileOnly everything | slim self-test plugin for the live integration matrix (Mental `tester` pattern, distinct jvmdg prefix) |

**Build-time enforcement (all wired into `check`):**

1. `kernel/build.gradle.kts` — dependency firewall (Mental §8 pattern):
   ```kotlin
   configurations.all { resolutionStrategy.eachDependency {
       require(!requested.group.startsWith("io.papermc"))  { "kernel must stay Bukkit-free" }
       require(!requested.group.startsWith("org.spigotmc")) { "kernel must stay Bukkit-free" }
       require(!requested.group.startsWith("net.kyori"))    { "kernel must stay Adventure-free" }
       require(requested.group != "com.zaxxer" && requested.group != "com.h2database"
            && requested.group != "com.mysql")              { "kernel must stay JDBC-free" }
   }}
   ```
   plus `KernelClasspathTest`: `Class.forName("org.bukkit.Bukkit")` must **throw** on the
   kernel test classpath, and an ArchUnit rule: kernel classes may import only `java.*`,
   `dev.fablemc.factions.kernel.*`.
2. ArchUnit suite in `:core`:
   * no `java.sql`/`javax.sql` import outside `dev.fablemc.factions.core.storage`;
   * no `net.kyori` reference outside `dev.fablemc.factions.core.text` (the TextPort boundary grep, §7.3);
   * no direct call to `Bukkit.getOnlinePlayers` / `InventoryView` methods /
     `Bukkit.getOfflinePlayer(String)` anywhere (bytecode scan; call sites must go through
     `platform.Players` / `platform.Views` / the name cache);
   * every class implementing `Listener` that references a post-1.7.10 Bukkit type in **any
     method/field descriptor** must be annotated `@ProbeGated(capability=...)` and registered
     only via `ListenerGate` (§8.3) — enforced by scanning descriptors against a floor-symbol
     manifest (the listener-descriptor hazard, version-deltas Risk #4).
3. Mega-jar gates `verifyDowngrade`, `verifyJdk8Api`, `verifyRelocation`, `verifyProbeIsolation`
   (§11).
4. `:api` japicmp `apiCompat` against a committed baseline once `api-1.0.jar` exists.

Dependency direction is strict: `kernel ← api ← platform ← core ← compat-*`. Nothing points
back down; `:kernel` ships unrelocated inside the jar.

---

## 3. Threading & state model

### 3.1 Who owns what

| State | Owner | Readers | Mutation path |
|---|---|---|---|
| `KernelState` (all domain state: factions, members, claims, relations, power, bank, warps, chests, invites, ranks, prefs, shields, zones, config image) | `fable-kernel` writer thread | everyone, via `AtomicReference<KernelSnapshot>` | `Intent` → `Reducer` |
| Effect journal file | writer thread (append) | recovery only | append-only |
| SQL database | `fable-storage` thread | boot loader, importers | batched projection of effects |
| Bukkit world/entities | region/main threads | listeners on owning thread | never touched by kernel/writer/storage threads |
| GUI sessions, chest viewer refcounts, teleport warmups | `SessionRegistry` on region threads, confined per player via `Scheduling.runOn` | owning thread | platform-side only; commits results as intents |

The **writer thread is not the main thread**. On Spigot, Paper, and Folia identically it is a
plugin-owned daemon thread that runs only `:kernel` code plus queue hand-offs. It never calls
a Bukkit API (build-enforced: the reducer is in `:kernel`, which cannot see Bukkit). This makes
the threading story literally identical across all 30+ supported server versions — there is no
"Folia mode".

### 3.2 The pipeline

```java
// core.pipeline
public final class IntentBus {
    private final MpscRingBuffer<IntentEnvelope> ring = new MpscRingBuffer<>(65_536);
    private final MpscUnboundedQueue<IntentEnvelope> systemLane = new MpscUnboundedQueue<>();
    public SubmitResult submit(Intent i, Origin o) { ... }       // player lane, may reject
    public void submitSystem(Intent i)             { ... }       // join/quit/tick/config, never rejected
}

record IntentEnvelope(long seq, long epochMillis, long nanoTime, int tick,
                      long rngSeed, Origin origin, Intent intent) {}
```

* All nondeterminism (wall clock, tick, random UUIDs) is **captured at enqueue** into the
  envelope. The reducer consumes `envelope` fields only — this is what makes replay
  deterministic (§15 claim N-1).
* Writer loop: `park` until signalled → drain up to **1024** envelopes → for each, run
  `Reducer.apply` accumulating effects into a reused `EffectBatch` → publish **one** new
  snapshot (`snapshotRef.set(...)`) → hand the batch to journal (same thread, buffered write)
  and to the projector/subscriber SPSC queues → loop. Publication is therefore
  **end-of-batch**, at most once per drained batch, at least once per 50ms tick of the flush
  timer.

### 3.3 Snapshot publication discipline

```java
public final class SnapshotHub {
    private final AtomicReference<KernelSnapshot> ref;   // the ONE reference
    public KernelSnapshot current() { return ref.get(); }     // lock-free, wait-free
}
```

Rules (enforced by convention + review + the ArchUnit "no kernel type mutation" rule — kernel
types are records/frozen arrays, so there is nothing to mutate):

* A handler takes **one** `KernelSnapshot` at entry and uses it for the whole operation —
  no torn reads across a swap (the Mental `Snapshot` freeze rule, generalized to *all* domain
  state, not just config).
* Snapshots are never stored long-term by readers; subscribers that need history consume the
  effect stream instead.
* `ConfigImage` is a **field of the snapshot** (§9): a `/fa reload` is itself an intent, so a
  config swap is serialized with every other mutation and can never tear an in-flight decision.

### 3.4 Folia mapping

| Work | Route |
|---|---|
| Event decisions (protection, move, chat) | inline on the event's region thread — pure snapshot read, no hand-off |
| Mutations | `IntentBus.submit` from any thread (MPSC) |
| Feedback to a player (`Notify` effects) | `Scheduling.runOn(player, ...)` — entity scheduler on Folia, main thread on Bukkit, with the retired-callback contract (exactly-once, no thread affinity — Mental TCK cloned) |
| Broadcasts / console | `Scheduling.runGlobal` |
| Teleports (`/f home`, `/f warp`) | `TeleportSaga` on the player's entity scheduler; `teleportAsync` when `caps.asyncTeleport`, sync `teleport` on legacy |
| Periodic (power tick, tax sweep, invite prune, dynmap refresh) | `Scheduling.repeatAsync` → enqueue one system intent; **never** touches Bukkit |
| Storage | `fable-storage` thread only |

`plugin.yml`: `folia-supported: true`. `FoliaScheduling` lives in `:compat-folia`, is folded
into the jar, and is instantiated by FQN string behind
`Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` — Mental §5 verbatim,
including the `scheduled == null → retired.run()` quirk fix and the ≥1-tick delay clamp
(kills catalogued BUG-20).

### 3.5 Backpressure

* Player lane: bounded 65,536 ring. On full, `submit` returns `REJECTED_BUSY` and the command
  layer answers `general.busy` instantly — the game thread never blocks, ever.
* System lane (join/quit/ticks/config/escrow settlements): unbounded linked queue — these are
  low-rate and must never drop (dropping a `PlayerDisconnected` would leak session state).
* Coalescing: `PowerTick` and `TaxSweep` intents carry their tick stamp; if the writer finds a
  newer one of the same kind already queued it drops the stale one (at most one pending each).
* High-water telemetry: queue depth > 48Ki logs one WARN/min and is exported to bStats debug
  chart; there is no silent shedding of player intents.

### 3.6 Why the single writer cannot bottleneck at 1000 players — the arithmetic

The load insight: **at 1000 players, >99.9% of factions work is reads, and reads never enter
the pipeline.** Movement, block, damage, chat, PAPI, GUI rendering are all snapshot reads on
the caller's thread. Intents are only *mutations*:

| Source | Worst-case rate @1000 players | Notes |
|---|---|---|
| Commands (claims, bank, invites, …) | ~50/s | humans typing; `/f claim square 8` is ONE `ClaimBatch` intent (≤200 chunks) |
| Deaths/kills | ~10/s | hard PvP server |
| Join/quit | ~5/s | |
| Auto-claim border crossings | ~100/s | only players with auto-mode ON crossing borders |
| Power tick | 1 intent / 60s (touches ~1000 online members) | §5(e) |
| Tax sweep | 1 intent / 24h | |
| **Total** | **< 200 intents/s** | |

Reducer cost per intent: a claim = 1 shard copy (~12KB memcpy) + validations (~2µs); a power
change = 1 member-record copy (~200ns); a bank op = 1 faction-record copy. Measured budget
target (JMH pin, §13): **≥100,000 intents/s sustained** on one 2020-era core — 500×
headroom over worst-case load. The queue-latency model at 200/s arrivals and 10µs service is
effectively zero waiting; p99 intent→publish target **< 5ms** including park/unpark, pinned
by a latency test.

Perceived command latency: pre-validation failures answer on the caller thread in
microseconds (§3.7). Success acknowledgements ride a `Notify` effect through
`Scheduling.runOn` — worst case one tick hop (≤50ms), identical to any conventional plugin
that messages from a scheduled task. State itself is visible in the snapshot within ~1ms —
*before* the message arrives.

### 3.7 Optimistic pre-validation (responsive commands)

Every command handler runs, on its own thread:

1. take `snapshot`;
2. run the **same kernel validation functions** the reducer will run
   (`ClaimRules.validate(snapshot, claimIntent)` — pure, shared code, one source of truth);
3. on failure → localized error immediately, nothing enqueued;
4. on success → fire the cancellable API event (§10.2), perform any external pre-step
   (Vault escrow, §4.6), enqueue the intent.

The reducer re-runs the identical validation against the authoritative state; a rare
lost-race (state changed between snapshot and reduce) produces a `Rejected` effect with the
same message key the pre-validation would have used — user sees one message either way,
TOCTOU-free because only the reducer's answer allocates resources.

---

## 4. The kernel

### 4.1 State shape (concrete)

```java
// :kernel  — dev.fablemc.factions.kernel.state
public record KernelState(
    long version, int tick,
    ConfigImage config,                 // §9 — part of state, swap = intent
    FactionArena factions,              // dense Faction[] by ordinal + free-list
    MemberDirectory members,            // UUID → packed member ref, COW open addressing
    MemberArena memberRecords,          // dense MemberRecord[] (power, streak, prefs…)
    ClaimAtlas claims,                  // per-world sharded COW long→int
    WarpTable warps, ChestTable chests, // per-faction sorted arrays
    InviteTable invites, MergeTable mergeRequests,
    InboxTable inbox,                   // player → pending message ids
    EscrowTable escrows,                // open Vault sagas (§4.6)
    NameIndex factionNames,             // fold-cased name → ordinal (uniqueness authority)
    ZoneStats zones, PredefinedRegistry predefined)
{}

// Faction: immutable; replaced whole on any faction-scoped change (they are small)
public record Faction(
    int idx, UUID id, String name, String nameFolded,
    UUID ownerId, String description, String motd, long createdAt,
    double powerBoost, double bank,
    long flagBits,                       // FactionFlag bitset + overrides mask
    int[] relOut; byte[] relOutKind,     // sorted "wishes" (declared relations)
    int[] relEff; byte[] relEffKind,     // sorted EFFECTIVE edges (symmetric, derived)
    Home home,                            // nullable record
    int shieldStartHour /*-1=none*/, int shieldDurationHours,
    boolean raidable,
    Rank[] ranks,                         // sorted by priority desc
    int landCount, double powerCacheSum, long powerCacheTick,   // derived, maintained by reducer
    String tagLegacy, String tagMini)     // pre-rendered chat tag (§5d)
```

`MemberRecord` is the per-player row (dense array, ~96 bytes): `uuid, nameLast, factionIdx,
rankIdx, powerBase, powerAsOfTick, powerFrozen, lastActivity, lastDeathAt, deathStreak,
joinedAt, prefsBits (invites/territory/tax/motd/autoMode/overriding/fly), localeIdx,
powerBoost`. Players who are factionless **and** at max power **and** have default prefs are
evicted from memory and reconstructed from defaults/DB on next contact — bounds memory on
ancient databases (1M active-ish members ≈ 120MB; evicted majority costs nothing).

**`ClaimAtlas`** — the million-chunk structure:

* per world: 4096 shards, shard chosen by `mix(chunkKey) & 0xFFF`;
* a shard is a frozen open-addressed table: `long[] keys; int[] owner;` (load factor 0.55);
* key = `(long)chunkX << 32 | (chunkZ & 0xFFFFFFFFL)` — computed in-house, never Paper's
  `getChunkKey` (version-deltas §3.9);
* owner int = faction ordinal; `-1` wilderness; safezone/warzone are real ordinals 0 and 1
  (sentinel factions, `isNormal()==false` — parity with `SAFEZONE_ID`/`WARZONE_ID`);
* mutation = copy ONE shard (~1000 entries at 4M claims ≈ 12KB) → new `ClaimAtlas` record
  referencing 4095 old shards + 1 new. 5M claims ≈ 80MB total, reads are 2 pointer hops +
  linear probe over primitive arrays, **zero allocation**.

**`MemberDirectory`**: 256-shard COW open-addressed `long,long → int` (UUID msb/lsb → index
into `MemberArena`). Same COW discipline; membership churn is human-rate.

**Relations**: each `Faction` carries sorted parallel arrays. The reducer maintains the
**effective** edge arrays symmetrically: ALLY/TRUCE become effective only when both sides
declared them (`relOut` both ways) — mutual-by-construction (kills logic-bug 21); ENEMY and
NEUTRAL mirror immediately (parity with the reference state machine). Lookup = binary search
over ≤ a few dozen ints.

### 4.2 Intent vocabulary (complete)

`sealed interface Intent permits …` — all records, all pure data. Grouped:

**Lifecycle** `CreateFaction(name, owner)`, `DisbandFaction(factionIdx, byAdmin)`,
`RenameFaction`, `SetDescription`, `SetMotd`, `TransferOwnership`,
`SendMergeRequest(sender, target, actor)`, `AcceptMergeRequest(sender, target, actor)`.

**Membership** `JoinFaction(factionIdx, player, viaInviteId|OPEN)`, `LeaveFaction`,
`KickMember(actor, target)`, `SendInvite(faction, inviter, invitee)`,
`RevokeInvite`, `DeclineInvite`, `DeclineAllInvites`.

**Ranks/roles** `PromoteMember`, `DemoteMember`, `CreateRole(name, priority, prefix)`,
`RenameRole`, `SetRolePriority`, `SetRolePrefix`, `DeleteRole`, `AssignRole`.

**Claims/zones** `ClaimChunks(player, worldIdx, long[] keys, mode)` (single & batch unified;
carries the `LandMaxPerCommand`-capped set), `UnclaimChunks`, `UnclaimAll(actor, faction)`,
`AdminClaimChunks(faction, keys)`, `AdminUnclaimChunks`, `SetZoneChunks(zoneOrdinal, keys)`,
`RemoveZoneChunk(zoneOrdinal, key)`.

**Relations** `DeclareRelation(actorFaction, targetFaction, kind)` (reducer derives effective
edges + wish bookkeeping).

**Power** `RecordDeath(dead, killer, worldIdx, chunkKey)` — streak, zone/world multipliers and
kill-scaling are computed **inside the reducer** from config+state (single source of truth;
kills logic-bug 1), `PowerTick(tick)`, `BuyPower(player, points, cost, escrowId)`,
`AdminPowerSet/Add/Remove/Reset(target, amount, actor, reason)`,
`SetPowerFrozen(target, frozen, actor, reason)`.

**Economy** `CreditBank(faction, amount, actor, escrowId)` (deposit phase-2),
`RequestBankWithdrawal(faction, amount, actor)` (emits `PayoutRequested`),
`SettleEscrow(escrowId, outcome)`, `TransferBank(from, to, amount, actor)`,
`TaxSweep(tick)`.

**Homes/warps/chests** `SetHome(faction, loc)`, `UnsetHome`, `SetWarp(faction, name, loc,
creator)`, `DeleteWarp`, `SetWarpPassword`, `SetWarpCost`, `CreateChest(faction, name)`,
`DeleteChest`, `CommitChestContents(faction, name, ItemBlob, sessionNonce)`.

**Flags/prefs/session** `SetFactionFlag(faction, flag, value, byAdmin)`,
`SetNotifyPref(player, pref, on)`, `SetLocale`, `SetAutoTerritoryMode`,
`SetTerritoryTitles`, `SetFly(player, on)`, `SetOverriding(player, on)`,
`SetShield(faction, startHour, duration)`, `ClearShield`,
`PlayerConnected(uuid, name, localeHint)`, `PlayerDisconnected(uuid)`,
`AckInbox(player, long[] entryIds)`.

**System** `SwapConfig(ConfigImage)`, `SeedPredefined(name)` (folded into `CreateFaction`),
`ImportBaseline(...)` (migration, boot only).

### 4.3 Effect vocabulary (complete)

`sealed interface Effect` — every record carries `long seq` and the causing intent's
`origin`. Categories and members:

* **Domain deltas** (drive derived indexes + storage + integrations):
  `FactionCreated, FactionDisbanded, FactionRenamed, DescriptionChanged, MotdChanged,
  OwnershipTransferred, MergeRequested, MergeCompleted(memberMoves, claimMoves, bankMoved),
  MemberJoined, MemberLeft(kicked?), InviteCreated, InviteRemoved(reason),
  RankChanged, RoleCreated/Renamed/RePrioritized/PrefixSet/Deleted/Assigned,
  ClaimSet(worldIdx, key, faction, prevOwner /*-1 or victim = overclaim*/),
  ClaimRemoved(worldIdx, key, prevOwner), ZoneSet, ZoneRemoved,
  RelationDeclared(a,b,kind), RelationEffective(a,b,kind,prevKind),
  PowerChanged(player, before, after, source, reasonCode),
  PowerFrozenChanged, DeathStreakAdvanced(player, streak),
  RaidableChanged(faction, nowRaidable),
  BankChanged(faction, delta, balance, txType, actor, counterparty, note),
  TaxCharged(faction, amount, balance),
  HomeSet/HomeCleared, WarpSet/WarpDeleted/WarpPasswordSet/WarpCostSet,
  ChestCreated/ChestDeleted/ChestContentsChanged(blobRef),
  FlagChanged, PrefChanged, LocaleChanged, AutoModeChanged, FlyChanged, OverrideChanged,
  ShieldChanged, SessionStarted(lastActivity), SessionEnded(lastActivity),
  InboxQueued(player, messageKey, args), InboxDelivered(player, ids),
  AuditRecorded(faction, actor, FactionAuditAction, detail), ConfigSwapped(diffSummary)`
* **Feedback**: `Notify(targetUuid, MessageKey, String[] args)`,
  `NotifyFaction(faction, pred, key, args)` (online→message, offline→`InboxQueued`, the
  `FactionMemberNotifier` semantics), `Broadcast(scope, key, args)`,
  `Rejected(origin, ReasonCode, args)`.
* **External requests** (executed by adapters, may re-enter as intents):
  `PayoutRequested(escrowId, player, amount)` (Vault credit),
  `EscrowRefund(escrowId, player, amount)`,
  `WgRegionUpsert(worldIdx, key, faction)` / `WgRegionRemove` (region-sync),
  `LwcPurgeRequested(worldIdx, key, newOwner)`.

The `StorageProjector` derives SQL from domain deltas mechanically (one switch, §6.2); no
effect is storage-only and none is skipped — the journal is the complete history.

### 4.4 The reducer

```java
public final class Reducer {
    /** PURE. No IO, no clock, no Bukkit, no statics. */
    public static Outcome apply(KernelState s, IntentEnvelope e) { ... }
    public record Outcome(KernelState next, EffectList effects) {}
}
```

One `switch (intent)` over the sealed hierarchy, delegating to per-domain rule classes
(`ClaimRules`, `PowerMath`, `EconomyRules`, `RelationRules`, `RoleRules`, `MergeRules`…),
each a set of static pure functions with the reference plugin's load-bearing formulas
transcribed and unit-pinned (§13): `computeMaxLand` truncation, `isValidBorder` 4-neighbor
rule, overclaim gate order (enabled → zone → own → enemy-required → victim raidable
`land > maxLand` → F5 offline protection → F6 shield), power clamp
`clamp(before+Δ, min, max)` with `|εΔ|<1e-5` no-change rule, per-event clamp **gated to
automatic sources only** (deviation D-2), death-streak `loss·mult^streak`, kill scale
`clamp(victim/killer, min, max)`, shield UTC wrap window, tax `round2` chain, invite TTL,
`MoneyParser` suffixes, rank `canManage = priority >`.

Aggregates that the reference recomputed by scanning (faction power sum, land count,
raidable) are **incrementally maintained** by the reducer and checked by a property test
("recompute == cache" invariant), so `checkRaidableTransitions` becomes an O(dirty-factions)
step of `PowerTick`/claim intents instead of an O(all rows) DB sweep.

### 4.5 Determinism & time

* `powerAt(member, tick)` — offline/online regen is modelled as **lazy accrual**:
  `min(max, base + rate·Δticks)` from `(powerBase, powerAsOfTick)`, settled to a concrete
  base at every power-affecting event and at epoch boundaries (config swap, freeze toggle,
  online/offline transition — the online set is kernel state fed by
  `PlayerConnected/Disconnected`). Iterated-clamp ≡ single-clamp for constant rates, so this
  is behaviorally identical to the reference tick; the equivalence is a jqwik property pin.
  Consequence: `PowerTick` is O(online) not O(all players ever) and produces ≤ online-count
  effects (§5e).
* UUIDs for new rows come from `rngSeed` (SplittableRandom seeded per envelope) —
  replay-stable.
* Replay: `state₀ + journal ⟹ stateₙ` byte-identical (canonical state hash) — CI property
  (§15 N-1).

### 4.6 External-effect sagas (Vault, teleports) — exactly-once with compensation

Vault and teleports cannot live inside a pure reducer, so they are two-phase with escrow:

* **Deposit** (`/f bank deposit`): handler pre-validates on snapshot → fires cancellable
  `FactionBankTransactionEvent(DEPOSIT)` (amount mutable, parity) → Vault `withdraw` on the
  caller thread → on success enqueue `CreditBank(faction, amount, escrowId)`. Reducer credits
  (cannot fail for storage reasons — memory is authority) or, if the faction vanished,
  emits `EscrowRefund` → Vault adapter re-credits the wallet on the player's thread.
* **Withdraw**: reducer debits first (authoritative, records escrow) → `PayoutRequested`
  effect → Vault adapter deposits wallet → enqueues `SettleEscrow(ok)`; on Vault failure
  `SettleEscrow(failed)` → reducer re-credits the bank. Open escrows persist in state (and
  thus journal/DB); on boot, unsettled escrows are resolved by policy (refund-to-bank) and
  logged loudly.
* **Power buy**: handler quotes `actual = min(req, max−powerAt(now))`,
  `cost = actual·costPerPoint` from the snapshot → Vault withdraw → `BuyPower(points, cost,
  escrowId)`. Reducer applies BUY (bypassing the per-event clamp, D-2); if effective <
  points (freeze/disabled/race), it emits `EscrowRefund` for the undelivered fraction.
  **Charge exactly for delivery** — kills catalogued bugs 9/11/12/14 (logic) and 11/12
  (concurrency).
* **Warp/home teleport**: `TeleportSaga` (platform): validate destination world loaded
  *before* charging → charge → warmup task `repeatOn(player)` with cancel-on-move/damage +
  combat-tag check (first-party, config-gated; D-6) → teleport (async on Paper 1.13+) →
  on any failure after charge, refund. Essentials interop remains available as the
  delegated path when enabled (parity).

---

## 5. The five hot paths

All five share the allocation story: **steady-state zero garbage** — primitives, frozen
arrays, pre-rendered strings; the only allocations are the outbound message when something
actually happens.

**(a) Chunk→claim on movement/block events.**
`MoveListener` (MONITOR, ignoreCancelled): null-`getTo()` guard; then
`fromKey = (loc.getBlockX()>>4)`, packed long compare — same-chunk exit is 2 shifts + 1
compare, no map access, no `getChunk()` (never loads chunks). On crossing:
`int owner = snap.claims.ownerAt(worldIdx, toKey)` — `worldIdx` from a tiny identity array
kept on the listener (worlds are few and stable; refreshed on world load events). Enter/leave
notice compares owner ordinals; territory-title message uses the faction's pre-rendered
name/hover strings. Auto-claim: if member's `autoMode != OFF`, enqueue
`ClaimChunks`/`UnclaimChunks` (one intent). Cost per crossing: ~2 hash probes, 0 alloc.
Per-move (non-crossing): ~5ns.

**(b) Relation player↔player.**
`RelationOps.effective(snap, uuidA, uuidB)`: 2 `MemberDirectory` probes (open-addressed,
mixed 128-bit hash) → factionIdx pair → same → `MEMBER`; else binary search
`factionA.relEff` (≤ ~64 entries) → byte kind, default `NEUTRAL`. ~40ns, 0 alloc. Because
effective edges are symmetric by construction, `effective(a,b) == effective(b,a)` always.

**(c) Protection decision pipeline.**
One static entry: `Verdicts.decide(snap, ActorRef actor, int worldIdx, long chunkKey,
Action action) → Verdict` (`ALLOW/DENY_*` enum constants). Order (short-circuits first):
bypass-permission bit (platform pre-resolves `factions.bypass` into `ActorRef` — one
`hasPermission` per event, cached per event not per block) → overriding bit → atlas lookup
(wilderness → allow for build; PvP rules per zone) → zone table (safezone/warzone toggles
from `ConfigImage`) → own-faction → effective relation vs. action matrix → owner flag bits
(`PVP`, `EXPLOSIONS`, `FIRE_SPREAD`, `FRIENDLY_FIRE`). Every input is an int/long/byte;
call site is monomorphic (single concrete `KernelSnapshot` class, static method — JIT inlines
the whole verdict). **Explosions**: `blockList` is walked once, computing each block's chunk
key; because block lists are spatially clustered, a 1-element memo (`lastKey/lastVerdict`)
collapses the work to ~O(chunks touched) — a 300-block creeper does ~6 atlas lookups, not 600
JDBC calls (kills BUG-9's worst case). **Projectiles/pets/TNT**: `DamageAttribution`
(platform) resolves `Projectile#getShooter`, `TNTPrimed#getSource`, `AreaEffectCloud`,
tamed-owner **before** the verdict — ranged obeys the same rules as melee (kills logic-bug 5).

**(d) Chat tag rendering.**
`Faction.tagLegacy`/`tagMini` are rendered **once, at mutation time** (create/rename/config
swap) by the writer, from the configured `chat.tag-format` with the name inserted as escaped
literal text (never re-parsed as MiniMessage — injection dead, logic-bug 7; names are also
gate-validated `[A-Za-z0-9_]{3,32}` at intake, D-5). The chat listener (exactly one of
`LegacyChatListener` (AsyncPlayerChatEvent, universal) or `ModernChatRenderer`
(AsyncChatEvent, probe-gated separate class in `:compat-modern`)) does: 1 member probe →
1 faction array read → prepend cached string / cached Component. 0 alloc beyond what the
chat pipeline itself allocates. No DB, no MiniMessage parse per message.

**(e) Power tick.**
`Scheduling.repeatAsync(60s)` enqueues `PowerTick(tick)`. Reducer: iterate the **online set**
(kernel state, ≤ player count): settle lazy accrual where it changed epoch, apply online-regen
delta; offline members are untouched (their accrual is lazy, §4.5). Then the raidable pass
over the **dirty-faction set** (factions whose power/land changed since last tick) —
`RaidableChanged` effects drive member notifications and optional server broadcast. Cost at
1000 online / 10k factions with 200 dirty: ~1ms inside the writer, once a minute. Power
history rows: 1:1 for DEATH/KILL/BUY/ADMIN; REGEN coalesced to settlement points (deviation
D-3, documented).

---

## 6. Persistence

### 6.1 Two layers, one truth

**Layer 1 — `EffectJournal` (WAL).** The writer appends every effect batch to
`plugins/FableFactions/data/journal/seg-<n>.fj`: length-prefixed records
`[u32 len][u32 crc32c][u64 seq][u16 type][payload]`, group-commit `fsync` every batch or
50ms, segment roll at 64MB. This is the crash-safety authority: `kill -9` loses at most the
last unsynced window (bounded, testable), never a torn row.

**Layer 2 — relational projection.** `fable-storage` thread consumes the effect stream via an
SPSC queue, coalesces per table, and executes **batched JDBC** (`addBatch`/`executeBatch`,
target ≥2k rows/statement) inside one transaction per flush (250ms cadence or 4k effects).
Each flush transaction also updates `ff_meta.journal_seq` — the checkpoint. Boot replays
`journal_seq+1…` from the WAL into the projector before serving. Journal segments older than
the checkpoint are deleted.

The DB is therefore *always* a consistent prefix of the effect stream — there is no
"dirty cache vs DB divergence" state to be in (kills BUG-1/4/8/16 storage-side).

### 6.2 Schema (H2/MySQL identical DDL, dialect only for upsert)

Parity tables (importable 1:1 from the reference): `factions`, `players`, `board`,
`warps`, `invitations`, `ranks`, `bank_transactions`, `power_history`, `faction_inbox`,
`audit_logs`, `team_chests`, `merge_requests` (**created this time** — reference bug fixed),
plus `ff_meta(schema_version, journal_seq)` and `ff_escrows`. Deltas from the reference
schema, deliberate: `board.id` becomes `(world VARCHAR(64), cx INT, cz INT, faction_id)` with
`PRIMARY KEY(world,cx,cz)` (no string keys on the hottest table); `factions.name` gets a
**UNIQUE** index on a fold-cased shadow column (belt for I-1's braces, kills BUG-17's DB
side); all writes are field-scoped or whole-row from the single serialized stream — either is
safe, whole-row is used for simplicity except counters (`power`, `money`) which use absolute
values from effects (the effect carries `after`, so no `col = col + ?` is needed and replays
are idempotent by `seq` guard).

H2: `jdbc:h2:file:…;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE`, pool=1, MERGE-INTO
rewrite in `H2Dialect`. MySQL: Hikari pool from config (default 10), `ON DUPLICATE KEY
UPDATE` in `MySqlDialect`. Drivers shaded + relocated, `META-INF/services/java.sql.Driver`
excluded, explicit `driverClassName` (classloader isolation, per ref-data §6).

### 6.3 Load path

`onEnable` → open pool → `SchemaMigrator` (versioned, idempotent `ensureColumn` chain +
`schema_version`) → `BaselineLoader` streams all tables into mutable builders
(`ClaimAtlas.Builder` fills shards in place — building is the one place mutation exists,
single-threaded, pre-publish) → replay journal tail → freeze → publish snapshot v0 →
**only then** register listeners and commands. 5M claims stream at ~500k rows/s ≈ 10s boot;
progress logged every 10%. Legacy import: `the (removed) legacy importer` maps the reference's
`world:cx:cz` board ids, relation/flags JSON blobs, and TINYINT prefs into the new schema —
one-shot, gated by `/fa admin import reference` (console), documented.

### 6.4 Shutdown

`onDisable`: (1) IntentBus rejects new player intents (`general.shutting-down`); (2) writer
drains remaining queue; (3) force-close chest sessions → `CommitChestContents` intents →
drain again; (4) final journal fsync; (5) projector `flushAndAwait(10s)` + checkpoint;
(6) close pool. Even a timeout at (5) loses nothing — the journal replays on next boot
(kills BUG-13/15).

---

## 7. The platform seam

### 7.1 Capability probe inventory (boot-computed, one `PlatformProfile` manifest)

`Capabilities.detect()` — coarse booleans, `Class.forName`/`getMethod`, never version parses:

| Probe | Detection | Consumer |
|---|---|---|
| `folia` | `io.papermc.paper.threadedregions.RegionizedServer` | SchedulingFactory |
| `adventureNative` | `CommandSender.sendMessage(Component)` + native MiniMessage present | TextPort tier |
| `bungeeChat` | `net.md_5.bungee.api.chat.BaseComponent` + `Player.Spigot#sendMessage` | TextPort tier (1.8+, UNCERTAIN on late 1.7.10 → probed) |
| `onlineCollection` | MethodHandle for `()Ljava/util/Collection;` vs `()[Lorg/bukkit/entity/Player;` | `Players.online()` (the 1.7.10 binary split) |
| `flattened` | `Material.getMaterial("WHITE_WOOL") != null` | `LegacyMaterials` identity vs table |
| `asyncTeleport` | `Entity#teleportAsync` | TeleportSaga |
| `asyncChunkGet` | Paper `getChunkAtAsync` | home-safety checks |
| `modernChatEvent` | `io.papermc.paper.event.player.AsyncChatEvent` | chat listener selection |
| `blockExplode` | `org.bukkit.event.block.BlockExplodeEvent` | probe-gated listener (1.8.3+) |
| `entityPickup`, `armorStands`, `raids`, `mountBukkit`/`mountSpigot`, `toggleGlide`, `lingering` | class probes | probe-gated protection listeners |
| `spectator`, `sweepCause`, `newTeleportCauses`, `newSpawnReasons` | `Enum.valueOf` try/catch (sticky-getstatic rule) | resolvers, nullable constants |
| `titleApi` (5-arg → 2-arg → none), `actionBarApi` (adventure→spigot→none), `bossBarApi` (1.9+) | method probes | `Feedback` chain |
| `teamEntry`, `teamColor`, `sb64limits` | method probes + flattened | `Nametags` budget-truncation |
| `clickedInventory` | method probe | GUI rawSlot fallback |
| `viewHandles` | `MethodHandles.findVirtual(InventoryView, …)` resolved at boot | `Views` (1.21 class→interface ICCE) |
| `minHeight` | `World#getMinHeight` | home/claim Y clamps (fallback 0) |
| `hidePlayerPlugin` | `hidePlayer(Plugin,Player)` | vanish interop |
| `serializeAsBytes` | Paper item byte codec | `ModernItemCodec` |
| `brigadier` | `io.papermc.paper.command.brigadier.Commands` | compat-modern installer |
| `commandMap` | Paper accessor vs `CraftServer.commandMap` field reflection | alias registration |

`PlatformProfile.resolve(env, caps, log)` folds every typed handle into `Required.owned` /
`OptionalSince.resolve` entries (Mental Tier-2 pattern verbatim), prints **one** boot-report
line (`present/total`, tiers, disabled features), and a Required miss disables its owning
feature loudly — no silent degradation (B10). Hot paths never re-probe: the profile is a
final field read.

### 7.2 Era adapter layout

Small final resolver classes in `:platform`, resolve-once-at-class-load, "try modern →
fallback → degrade loud": `Players` (MethodHandle dual-descriptor), `LegacyMaterials`
(modern-name kernel vocabulary; one static modern→(legacy enum, data byte) table used only by
the GUI icon factory), `Views`, `Feedback` (title/actionbar/bossbar chains; 1.7 clients get
chat-line fallback; **no NMS anywhere** — the 1.8–1.10 NMS title packet is deliberately
skipped in favor of the chat fallback, D-8), `Hands`, `Nametags` (16/64-char budget
truncation after color accounting, prefix-tail color pre-1.13, `setColor` after),
`ItemCodec` (modern: `serializeAsBytes` with DataVersion; legacy: `BukkitObjectOutputStream`
Base64 tagged with the writing data version; cross-flattening chest migration is an explicit
one-way step, per version-deltas Risk #9). Sub-floor-typed symbols live only in
non-Listener helper classes behind resolvers (descriptor rule).

### 7.3 Text pipeline

MiniMessage in configs/messages → parsed **once** at catalog load into Adventure `Component`s
(shaded, relocated `dev.fablemc.factions.lib.adventure`) → delivery tiers by probe:
native Adventure audience (Paper 1.16.5+) / Bungee components via `player.spigot()` (hover
and click survive on Spigot 1.8+) / `§`-legacy string with hex downsampled below 1.16 /
plain strip. `TextPort` is the **only** class that touches a Bukkit send/title/inventory
API with text; "no `net.kyori` outside `core.text`" is the ArchUnit gate. All user-supplied
strings (names, MOTD, descriptions, prefixes, warp names) are inserted as literal
`Component.text` via `TagResolver` placeholders — never concatenated into parseable strings.

### 7.4 Scheduling facade

`platform.Scheduling` is Mental's interface verbatim (`runGlobal/runAt/runOn/runOnLater/
ensureOn/runAsync/repeatGlobal/repeatOn/repeatAsync/isOwnedByCurrentRegion/describe`,
`TaskHandle`, retired-callback contract: either-thread / exactly-once / may-be-immediate),
implemented by `BukkitScheduling` and `compat-folia.FoliaScheduling`, selected by
`SchedulingFactory` via FQN string. A `SchedulingTck` (java-test-fixtures on `:platform`)
pins the contract on both backends.

### 7.5 GUI abstraction

`gui.yml`-driven `MenuModel` (ids, size normalized to [9..54] multiple-of-9, items with
slot/material/name/lore/glow/action) → `MenuSession` owned per player on their region thread;
inventories created via `Bukkit.createInventory(MenuHolder, size, String)` (universal String
overload), identified by custom `MenuHolder` — zero `InventoryView` calls (rawSlot math;
`Views` MethodHandles where a view method is unavoidable). Actions: RUN_COMMAND,
SUGGEST_COMMAND, OPEN_MENU, LANGUAGE_SET/RESET, CLOSE, REFRESH — parity with the reference,
placeholders rendered from the snapshot (no DB). Icons via `LegacyMaterials` (data-byte
constructor only inside the legacy branch).

### 7.6 Command framework 1.7.10 → 26.x

plugin.yml declares only `f` (aliases `faction`,`factions`) and `fa` (`factionadmin`,
perm `factions.admin`) — `PluginCommand` + `TabCompleter` are 1.7.10-safe. `CommandNode`
tree replicates the reference framework contract exactly: name/aliases/permission/
requiredArgs/optionalArgs/requiresPlayer/children pipeline order (perm → player → child →
arg-count → perform), `ctx.arg(i)` never throws, `parseArguments` long-option parser
(`--action=`, `--size=`), `CommandGuards` (requireFaction/Owner/OfficerOrAbove),
registry-driven `completionNames`. `:compat-modern.BrigadierInstaller` optionally upgrades
tab-completion richness on 1.20.6+ (plugin.yml stays the source of truth).
`api-version: '1.13'` (the unique value that loads 1.7.10→26.x without the Commodore shim).

---

## 8. Event / protection engine

### 8.1 Listener registrations & priorities (parity where it matters)

| Listener class | Events | Priority / ignoreCancelled | Gate |
|---|---|---|---|
| `BuildProtectionListener` | BlockBreak, BlockPlace | HIGH / true | always |
| `AllyUnlockListener` | BlockBreak, BlockPlace | HIGHEST / **false** | only when WG-sync enabled |
| `CombatProtectionListener` | EntityDamageByEntity (+ shooter/TNT/pet attribution), PotionSplash | HIGH / true | always |
| `ExplosionListener` | EntityExplode | HIGH / true | always |
| `BlockExplodeListener` | BlockExplode | HIGH / true | probe `blockExplode` (own class — 1.8.3+) |
| `GriefListener` | BlockSpread(fire), BlockIgnite, BlockFromTo, BucketEmpty/Fill, PistonExtend/Retract (both-ends check), EntityChangeBlock, HangingBreak(+ByEntity), StructureGrow, trample via PlayerInteract(PHYSICAL) | HIGH / true | always (all floor-safe events) — **event-complete matrix, D-4** |
| `InteractProtectionListener` | PlayerInteract (containers/doors/levers per config-declared material classes) | HIGH / true | always |
| `ArmorStandListener`, `RaidListener`, `MountListener` (bukkit/spigot dual), `GlideListener`, `LingeringListener` | respective | HIGH / true | probe-gated separate classes |
| `MoveListener` | PlayerMove, PlayerTeleport (pearl border rules) | MONITOR / true | always — the ONE consolidated move handler (titles + auto-territory + fly re-check) |
| `ChatListener` (legacy) / `ModernChatRenderer` | AsyncPlayerChatEvent / AsyncChatEvent | HIGHEST / true | exactly one active per boot |
| `SessionListener` | PlayerJoin, PlayerQuit | MONITOR | connected/disconnected intents, inbox/invite/MOTD/update delivery |
| `DeathListener` | PlayerDeathEvent | MONITOR / true | `RecordDeath` intent |
| `GuiListener`, `ChestSessionListener` | InventoryClick/Drag/Close | NORMAL | session-scoped |

All verdicts route through `Verdicts.decide` (§5c) — one decision function, default-deny in
claimed land, fail-safe DENY on any internal error. WG-sync mode preserved: NORMAL (WG) →
HIGH (ours, allow-only via `isFactionRegion` fast path) → HIGHEST un-cancel for
**mutual** allies/override.

### 8.2 Short-circuit ordering

Every handler's first lines: cheapest rejections first — same-chunk exit (move), non-fire
source (spread), non-player attribution (combat), wilderness-allow (build). The atlas lookup
is so cheap that no per-player "region cache" is needed — the snapshot **is** the cache.

### 8.3 `ListenerGate` (probe-gated registration)

`ListenerGate.register(scope, capability, () -> new BlockExplodeListener(...))` — the
supplier is invoked only when the capability holds, the class is otherwise never loaded
(constant-pool laziness), and registration is recorded in the feature's `Scope` for symmetric
teardown. The D-9 console-scan CI gate (§13) makes a silently-unregistered listener a build
failure, not a field bug.

---

## 9. Config system & messages

### 9.1 Typed atomic config

* Files (parity set): `config.yml`, `database.yml`, `notifications.yml`, `gui.yml`,
  `roles.yml`, `pre-defined.yml`, `messages/messages_<locale>.yml`
  (en, es, de, fr, pt-BR, zh, ru, ja shipped).
* `ConfigImage` — one immutable record-of-records parsed with **warn-and-fallback** readers;
  every key from the reference key tree is present (ref-services §2 inventory is the
  checklist), including the four reference-dead keys (`land.buffer-zone`,
  `economy.cost-create`, `economy.cost-claim`, `fly.disable-on-threat`) which are **wired**
  here (D-7) with defaults that preserve reference behavior (0/0/0/false-equivalent).
  Aliased keys (`power.per-player-max` vs `power.constraints.max-power`) resolve to **one**
  canonical field with a deprecation warning when both are set (kills logic-bug 22).
* Two-layer write model: human YAML never re-serialized; GUI/API writes go to
  `state/overrides.yml`; effective = overlay ?? file ?? default (Mental §7).
* **Reload = `SwapConfig` intent.** `/fa reload` parses a complete new image off-thread,
  enqueues it; the reducer swaps it inside the state and emits `ConfigSwapped`; the
  platform-side `FeatureReconciler` converges scoped features (listeners, timers, GUI, hooks)
  against the new image — close-scope/open-scope, never re-register-over (kills BUG-3).
  Database settings are boot-only (documented, as reference).

### 9.2 Message catalog / i18n

`MessageCatalog`: normalized locales (`pt-BR` special case), waterfall preferred → default →
en → fallback literal; per-player locale from the snapshot member record. Keys are interned
`MessageKey` handles (the full reference inventory including all `custom.*` keys — resources
§9 is the parity checklist; reference hard-coded literals become keys with the exact same
default strings, D-9). Templates parse MiniMessage once at load; `{token}` substitution fills
pre-split segments — no per-send parsing. `Notify` effects carry `MessageKey + args`, so the
kernel stays text-free.

---

## 10. Public API & integrations

### 10.1 `dev.fablemc.factions.api`

* Reads: `FableFactionsApi.snapshot()` → `FactionsView` (immutable, thread-safe: faction/
  member/claim/relation/power/bank queries — everything PAPI needs).
* Writes: `api.request(IntentSpec) → CompletionStage<Outcome>` (completes on the global
  thread with the reducer's answer).
* Effect subscription: `api.subscribe(EffectListener)` for read-model builders.

### 10.2 Bukkit events (parity set)

`FactionCreateEvent, FactionDisbandEvent, FactionJoinEvent, FactionLeaveEvent,
FactionChunkClaimEvent (with overclaimedFrom), FactionChunkUnclaimEvent,
FactionBankTransactionEvent (mutable amount)` — **cancellable ones fire during pre-flight on
the caller thread** (before any side effect; kills BUG-22's fire-after-commit), and the
reducer re-validates; MONITOR-grade "happened" notifications are re-fired from the
`ApiEventBridge` effect subscriber on the global thread.

### 10.3 Integration adapters (all soft-depend, config-AND-plugin-present gates, façade+Noop+factory)

| Integration | Direction | Design |
|---|---|---|
| **Vault** | out | `VaultEconomy` lazy provider resolution per call (EzEconomy late-registration), OfflinePlayer overloads only; escrow saga participant (§4.6) |
| **PlaceholderAPI** | in | `FactionsPlaceholders` (`%fable_*%` (no compat aliases)) — pure snapshot reads, zero IO |
| **dynmap** | out | `DynmapLayer` subscribes `ClaimSet/ClaimRemoved/FactionRenamed/RelationEffective`, debounced 2s, area markers per claim run; reference palette + marker-id formula preserved |
| **DiscordSRV** | out | reflection-only notifier; subscribes `FactionCreated/Disbanded/RelationEffective`, per-event templates from config |
| **WorldGuard** | both | `TerritoryGuard` façade (`canModifyTerritory`, `isFactionRegion` with the load-bearing `f_<world>_<x|nX>_<z|nZ>` name formula); `WorldGuardRegionSync` subscribes claim effects and mirrors cuboids (minHeight-aware); guard consulted in claim/sethome/warp-set pre-flight |
| **Essentials/EssentialsX** | out | teleport/jail/vanish interop behind `EssentialsInterop`; first-party warmup engine is the default fallback (D-6) |
| **LWC/LWCX** | both | reflection interop: creation gating, stale-protection removal, `LwcPurgeRequested` effect on claim ownership change |
| **TeamsAPI** | in/out | `TeamsApiRegistrarImpl` loaded by FQN only when present; providers wrap the api views + intent requests; role-change mirroring via `RoleChangeNotifierHolder`; optional services (chest 2.3, relation 1.6, notification 1.7, power-history 1.8) registered reflectively; `/f` unknown-subcommand fallback bridge preserved |
| **EzCountdown** | out | reflection notifier for relation announcements (duration/display-types from notifications.yml) |
| **bStats** | out | charts fed from snapshot counters + effect tallies (created/total factions, relation drilldown, db type); no DB scans |
| **Update checker** | out | Modrinth primary / GitHub fallback chained checker, async, op-join notice |

Every provider-typed class is instantiated only after presence confirmation; reflection-based
ones load unconditionally — the reference's two isolation strategies, preserved.

---

## 11. Build pipeline (Mental recipe: keep / change / why)

**Kept verbatim** (mental-build.md): Gradle 9.5.1 wrapper + foojay resolver; catalog
`shadow 9.4.2`, `jvmdowngrader 1.3.6`, `run-paper 3.0.2`; toolchain JDK = `max(jdk)` from the
matrix (25); `options.release = 17` (v61) — v52 comes from jvmdg, never javac; shadowJar
staged as `-modern` into `build/jvmdg-stage` (no `minimize()`); `DowngradeJar` with
`downgradeTo = 1_8`, `multiReleaseOriginal = true`, **never** `multiReleaseVersions`
(1.3.6 mutual-exclusion gotcha); downgrade classpath = union of core + compat-folia +
compat-modern compile classpaths; `ShadeJar` with `shadePath = "dev/fablemc/factions/lib/jvmdg/"`
emitting canonical `FableFactions-<v>.jar`; `failOnJvmdgWarnings` with the global-console
capture, `mustRunAfter(:api:apiCompat)`, and the Unsafe-noise filter; gates `verifyDowngrade`
(sentinel `dev/fablemc/factions/boot/FableFactionsPlugin` major 52 base / 61 in versions/17;
base-only classes carry no jvmdg-runtime refs; H4 record-reflection scan), `verifyJdk8Api`
(ASM tool vs real provisioned JDK 8, **empty allowlist**), `verifyRelocation`,
`verifyProbeIsolation` (probe plugin uses `dev/fablemc/factions/probe/lib/jvmdg/` — D-8
stub-descriptor scan included); `support-matrix.json` as the single source of MC/JDK truth
(`floorApi: "1.13"`, entries 1.7.10 … 26.1.2 paper + folia, each with measured `jdk` and
`bytecodeTier` asserted live by the probe plugin); plugin.yml `${version}`/`${apiVersion}`
expansion from the matrix; japicmp `:api` gate with real classpaths (no
`--ignore-missing-classes`).

**Changed, and why:**

1. **Compile floor 1.13.2, not 1.17.1.** A factions plugin's protection surface is ancient
   stable API; compiling at 1.13 shrinks the probe budget for the 1.13–1.16 band to ~zero and
   confines probing to 1.7–1.12 + modern extras (version-deltas §3.17 recommendation). Same
   MRJAR shape.
2. **Relocations**: `net.kyori → …lib.adventure`, `org.bstats → …lib.bstats`,
   `com.zaxxer.hikari → …lib.hikari`, `org.h2 → …lib.h2`, `com.mysql → …lib.mysql` (+ driver
   SPI exclusion). PacketEvents relocation dropped (no packets in this plugin).
3. **`verifyJdk8Api` ignore list** swaps Mental's PacketEvents entries for the soft-dep
   provider packages: `net/milkbowl`, `com/sk89q`, `me/clip`, `org/dynmap`,
   `com/earth2me`, `github/scarsz` (DiscordSRV), `com/griefcraft` (LWC),
   `com/skyblockexp` (TeamsAPI, EzCountdown).
4. **Matrix extended down to 1.7.10** with Java 8 as documented host floor there; the
   1.7.10/1.8.8 Java-9+ flagless-boot question and `getOnlinePlayers` descriptor are ladder-
   probed once in CI and recorded into the matrix `_comment` (version-deltas UNCERTAIN list
   items 1–3 become measured facts before release).
5. **New gates**: `verifyKernelPurity` (jar-level: no `org/bukkit` token in any
   `dev/fablemc/factions/kernel/` class), journal crash-recovery test in `check`.

---

## 12. Feature → module mapping (requirement-1 proof)

Legend: K=`:kernel` P=`:platform` C=`:core` M=`:compat-modern` F=`:compat-folia`.

| Reference feature (docs) | Home | Notes |
|---|---|---|
| `/f create,disband,rename,desc,motd,leader,leave,kick,promote,demote` | C `command.member` → K lifecycle/membership intents | exact guard order, message keys, 3–32 name rule (charset tightened, D-5) |
| `/f invite (send/list/revoke/accept/decline/declineall)`, `/f join`, OPEN flag join | C `command.invite` → K invite intents | TTL prune inside reducer; accept = delete+join in one intent (atomic) |
| `/f claim [one/auto/square/circle/fill/nearby/at]`, `/f unclaim [...all]` | C `command.claim` (collectors, `land.max-per-command` cap) → K `ClaimChunks` | border/overclaim/F5/F6 in `ClaimRules`; auto modes in member prefs |
| Overclaim (enabled/enemy-required/raidable/offline-protection F5/war-shield F6), victim notifications | K `ClaimRules` + effects | overclaim adjacency configurable (D-1) |
| `/f home,sethome,unsethome`, `/f warp` group (set/delete/list/password/cost) | C `command.travel` + `TeleportSaga` (P) → K home/warp intents | warmup/cooldown/combat-tag first-party (D-6); placement inside own claims enforced (D-10); costs via escrow |
| `/f fly` | C + K `SetFly` | own-territory gate, threat re-check on move + fall-immunity grant (D-11, wires `fly.disable-on-threat`) |
| `/f bank` group (balance/deposit/withdraw/transfer/history) + tax scheduler | C `command.bank`, `economy.VaultAdapter` → K economy intents | escrow sagas; withdraw rank-gate configurable (D-12); tx history paging parity |
| `/f power` (buy), `/f powerhistory`, `/fa power view/set/add/remove/reset/freeze/history` | C `command.power` → K `PowerMath` | source pipeline (freeze gate, sourceEnabled, multipliers, clamps) with D-2 clamp gating |
| Power regen tick (online/offline), death loss + streak F2, kill gain + scaling F3, grace period, zone/world multipliers, inactivity exclusion F1, raidable transitions + broadcast F4 | K (`PowerTick`, `RecordDeath`) | lazy accrual (D-3); `last_activity` actually maintained (fixes ref BUG-23) |
| `/f relation` (+list, wishes), limits max-allies/truces, mutual promotion, announcements (EzCountdown/Discord) | C `command.relation` → K `RelationRules` | wishes vs effective edges first-class |
| `/f role` group (list/create/rename/setpriority/setprefix/delete/assign), roles.yml gates, custom priorities 11–99, prefix budget | C `command.role` → K `RoleRules` | promote/demote steps **sorted priority** (fixes ref bug 18) |
| `/f flag` (list/set), `/fa flag`, flag defaults + player-editable locks | C → K `SetFactionFlag`; consumed by `Verdicts` | PVP/FRIENDLY_FIRE/EXPLOSIONS/FIRE_SPREAD/OPEN |
| `/f chest` group + 54-slot sessions, Base64 contents | C `chest.ChestSessions` (shared live inventory + refcount) → K blobs | single-live-inventory fix; Folia exclusive-open mode (D-13) |
| `/f map` (on/off/once, `--size`), clickable grid | C `command.map` — pure snapshot render | chat-glyph map, zero DB |
| `/f info` (+page), `/f list`, `/f top` | C — snapshot queries | comparators/paging parity |
| `/f notify`, `/f language` (+GUI), `/f gui` | C prefs/gui → K pref intents | |
| `/f audit`, `/fa audit`, `--action` filter, 18 audit actions | K `AuditRecorded` effects → storage; C renderers | |
| `/f merge send/accept` | C → K `MergeRules` | caps enforced on merge (D-14); merge_requests table created |
| `/f predefined` group + pre-defined.yml seeding, create-restriction, disband-block | C `predefined` + K registry in ConfigImage | |
| `/f help` (3 pages), `/fa help`, unknown-command rich link | C `command.help` | |
| `/fa bypass, claim, unclaim, disband, reload, safezone, warzone, shield` | C `command.admin` → K intents | shield UTC window in K `ShieldWindow` |
| Protection: build/PvP/explosion/fire + **event-complete grief matrix** | C listeners → K `Verdicts` | §8.1; D-4 additions |
| Territory titles / enter notices with hover info | C `MoveListener` + snapshot | |
| Chat tag (Paper renderer / legacy setFormat) | C/M chat listeners + pre-rendered tags | |
| Join notifications: invites summary, inbox (max-per-login), MOTD | C `SessionListener` → deliver-then-`AckInbox` | fixes ref BUG-10 |
| Zones: safezone/warzone semantics everywhere | K ordinals 0/1 + `Verdicts`/`PowerMath` | |
| GUI menus (main/language), gui.yml schema, actions | C `gui` + P `MenuModel` | |
| Locales: 8 shipped bundles, per-player override, normalization | C `MessageCatalog` | |
| Integrations: Vault, WorldGuard (+region-sync), PAPI (`%fable_*%`), EssentialsX, dynmap, LWC/LWCX, DiscordSRV, EzCountdown, TeamsAPI (all providers + custom roles + bridge), bStats (4 charts), update checker (Modrinth/GitHub) | C `integration.*` | §10.3 |
| Storage: H2 default / MySQL, Hikari settings, 12 tables + indexes, ensureColumn migrations, pagination semantics | C `storage` | §6 |
| Custom Bukkit events (7) | `:api` | §10.2 |
| Permission catalog (plugin.yml `factions.cmd.*` tree, defaults) | C resources | full parity list from resources §6.2 |
| plugin.yml softdepend/loadbefore lists | C resources | parity |
| Scheduler abstraction (Bukkit/Folia) | P/F | §7.4 |

**Deviation register** (every WHAT preserved; HOW deliberately changed — each is
config-gated with the reference behavior as default unless it is a catalogued bug):

D-1 overclaim adjacency + buffer-zone enforced (config; ref: dead knob) · D-2 per-event clamp
automatic-sources-only · D-3 REGEN history coalescing · D-4 event-complete protection ·
D-5 name charset validation · D-6 first-party warmup/cooldown/combat-tag · D-7 dead config
keys wired · D-8 no NMS titles (chat fallback 1.8–1.10) · D-9 hardcoded strings become keys
(same defaults) · D-10 warp/home placement + use-time revalidation · D-11 fly threat/fall
rules · D-12 bank-withdraw rank gate · D-13 chest concurrency mode · D-14 merge respects
caps · D-15 board PK is (world,cx,cz) not a string.

---

## 13. Testing strategy

1. **Kernel unit pins** (no server, milliseconds): every load-bearing formula from the
   research appendices — power clamp/regen equivalence, streak, kill-scale, `computeMaxLand`
   truncation, border rule truth table, shield midnight wrap, tax rounding chain, invite TTL,
   money parser, rank stepping, relation state machine, name folding.
2. **Reducer property tests** (jqwik): random intent sequences maintain invariants —
   relation symmetry, member-cap, land-cap-or-raidable, non-negative bank, escrow
   conservation (`Σ wallet deltas + Σ bank deltas + open escrows = 0`), cache==recompute for
   power/land aggregates.
3. **Replay determinism**: run a generated 100k-intent journal twice → identical state hash;
   crash-recovery: kill the projector mid-batch, replay from checkpoint → same DB rows.
4. **Storage parity**: same effect stream projected to H2 and MySQL (Testcontainers) →
   row-identical dumps.
5. **Architecture tests**: §2 ArchUnit suite + `KernelClasspathTest` + TextPort boundary +
   listener-descriptor manifest scan.
6. **Scheduling TCK** on both backends (retired contract, clamp, exactly-once).
7. **Performance pins (JMH, failing thresholds in CI)**: `Verdicts.decide` ≥ 20M ops/s and
   **0 B/op** (GC-profiler asserted); `ClaimAtlas.ownerAt` < 100ns at 5M entries; reducer
   ≥ 100k intents/s mixed workload; snapshot publish p99 < 5ms under 10k intents/s synthetic
   flood.
8. **Live integration matrix** (run-paper + `:probe` plugin, per support-matrix entry):
   boots real Paper/Folia at each version/JDK, asserts loaded bytecode tier
   (`-Dfable.probe.tier`), runs claim/protect/power/chat smoke suites, nonce-stamped
   results, and the **D-9 console-swallow gate**: any
   `has failed to register events for class dev\.fablemc\.factions\.` /
   `Could not pass event .* to FableFactions` / framed
   `NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError` in the captured log turns PASS
   into FAIL.
9. **Jar gates** (§11) on every `./gradlew build`.

---

## 14. How each catalogued bug class is made impossible

**Concurrency catalog (ref-bugs-concurrency.md):**

| Bug | Structural elimination |
|---|---|
| 1 full-row clobber | there is no `find→mutate→save`; SQL is a projection of one serialized effect stream; no code path can write a stale snapshot because no reader-writer pair exists |
| 2/4 power & bank RMW | all power/bank mutation is a reducer step on the single writer; two "concurrent" applies are two ordered intents against the same authoritative value |
| 3 reload listener leak | reload is a `SwapConfig` intent + scope-based reconciler; registration only ever happens through `Scope`, teardown closes the scope whole — there is no re-run-`start()` path to leak |
| 5 claim-lock removal | there are no locks to mismanage; claims serialize through the writer |
| 6 land-cap TOCTOU | cap checked inside the reducer against the state it mutates — check and act are the same atomic step |
| 7 chest dupe | one shared live Bukkit inventory per (faction,chest) with viewer refcount; contents committed once, from one snapshot of one inventory |
| 8 death-streak two copies | `RecordDeath` mutates streak + lastDeath + power in one reducer step on one state value; a second copy cannot exist |
| 9 main-thread JDBC | decisions read snapshots; `java.sql` is unimportable outside `core.storage` (ArchUnit) and that package runs only on `fable-storage`; explosions are O(chunks) memoized atlas reads |
| 10 inbox loss | delivery emits the exact id set; `AckInbox(ids)` deletes only what was delivered; overflow beyond max-per-login stays queued |
| 11/12 charge-then-fail | escrow saga: charge and grant are linked by an escrow record in state; every non-delivery path emits a compensating `EscrowRefund`; conservation is a property test |
| 13 shutdown write loss | WAL fsync precedes projector; ordered shutdown drains; un-flushed batches replay on boot |
| 14 fly-state leak/wipe | fly is kernel state; `PlayerDisconnected` settles it; reload doesn't rebuild services so nothing is wiped |
| 15 chest flush | scope close force-commits open sessions; contents also journaled |
| 16 relations partial mirror | relations are typed edges; the reducer maintains the symmetry invariant in one step; JSON blobs don't exist |
| 17 name uniqueness TOCTOU | `NameIndex` in state checked in the same reducer step that inserts + DB UNIQUE as belt |
| 18/21 join/invite TOCTOU | cap check, invite delete and join are one intent |
| 19 async Bukkit API | the writer runs only `:kernel` code (build-cannot see Bukkit); every Bukkit touch is an effect routed through `Scheduling` |
| 20 Folia timer/region | `Scheduling` clamps delays ≥1 tick; `runOn` is region-correct; TCK-pinned |
| 22 event after commit | cancellable events fire pre-flight, before any side effect, uniformly |
| 23 dead last_activity | `SessionStarted/Ended` effects update it; a unit pin asserts F1 exclusion actually fires |

**Logic catalog (ref-bugs-logic.md):** #1 delta computed once in `PowerMath`, service layer
cannot override (there is no second layer); #2 clamp gated by source class (D-2); #3/#4 =
concurrency 2/4; #5 `DamageAttribution` before every combat verdict; #6 event-complete
matrix (D-4) with default-deny; #7 literal-text insertion + charset validation (D-5);
#8 = concurrency 7; #9 escrow (§4.6); #10 adjacency + buffer-zone wired (D-1); #11 merge
runs the same guarded reducer rules as join/claim (D-14); #12 placement/ownership
revalidation (D-10); #13 first-party warmup/cooldown/combat-tag (D-6); #14 validate → charge
→ refund-on-failure ordering; #15 configurable withdraw rank gate (D-12); #16 fall-immunity
grant + move-time threat re-check (D-11); #17 = concurrency 5; #18 sorted-priority stepping;
#19 null-`getTo` guard in the one move handler; #20 kick resolves via the name↔UUID cache
(offline-capable); #21 mutual-effective edges; #22 one canonical max-power key.

---

## 15. What is novel here — SOTA claims as testable properties

* **N-1 Deterministic replayability.** The complete plugin state is a fold over a journaled
  intent/effect log. *Test:* replay any journal twice → identical canonical state hash; CI
  replays a 100k-intent corpus. No shipped factions plugin can reproduce a live incident
  from a log; this one can.
* **N-2 Reads never wait.** Protection/relation/chat/PAPI queries are wait-free reads of an
  immutable snapshot — no lock, no CAS-retry, no map with concurrent writers. *Test:* JMH
  with a writer flood shows read p99.9 unchanged (< 2× solo-read latency).
* **N-3 Zero-allocation hot path.** `Verdicts.decide` allocates 0 bytes/op in steady state at
  5M claims. *Test:* JMH GC profiler pin fails the build on regression.
* **N-4 O(1) at millions of claims.** COW-sharded primitive atlas: < 100ns lookups at 5M
  entries, mutation cost bounded by one 12KB shard copy. *Test:* pinned bench.
* **N-5 The writer cannot be the bottleneck.** Mutation demand at 1000 players is < 200
  intents/s; the reducer is pinned ≥ 100k/s — 500× headroom, measured not asserted.
* **N-6 Crash safety without main-thread IO.** fsync-bounded WAL + checkpointed projector:
  `kill -9` loses ≤ 50ms of accepted mutations, DB never torn. *Test:* automated crash-replay
  in `check`.
* **N-7 Bug-class immunity is compiled in.** The 45 catalogued defects map to 6 structural
  guarantees (single writer, projection storage, escrow sagas, scoped lifecycle, kernel
  purity, probe-gated listeners) — most enforced by the build itself (ArchUnit + resolution
  firewall + jar gates), not by review vigilance.
* **N-8 One artifact, 20 server generations.** v52 base + versions/17 MRJAR with a
  live-asserted bytecode-tier matrix from 1.7.10 to Folia 26.1.2, `api-version: '1.13'`,
  and a console-swallow gate that turns silent legacy degradation into red CI.
* **N-9 Config/state unification.** Config is snapshot state; a reload is an ordered intent —
  torn config reads are not a failure mode that can be written.

*Total estimated code:* kernel ~9k LOC, platform ~4k, core ~18k — smaller than the reference,
because caching layers, lock choreography, and defensive re-reads simply don't exist.
