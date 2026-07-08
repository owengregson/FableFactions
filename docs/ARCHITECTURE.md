# FableFactions — Final Architecture (v1)

**Status: NORMATIVE.** This document is the synthesis of the three-way architecture panel
(`docs/design/proposal-{A,B,C}.md`), nine adversarial critiques (`docs/design/critique-*.md`),
and a unanimous 3-judge verdict: **Proposal C — the Deterministic Kernel — wins**, amended by
the fixes and grafts below. Where this document is silent, `docs/design/proposal-C.md` applies
verbatim. Where they conflict, THIS document wins. Research ground truth lives in
`docs/research/` (feature parity: `ref-*.md`; build recipe: `mental-build.md`; seam patterns:
`mental-seam.md`; version facts: `version-deltas.md`; bug catalogs: `ref-bugs-*.md`).

Identity: plugin `FableFactions`, package root `dev.fablemc.factions`, commands `/f` + `/fa`,
one Multi-Release mega-jar spanning PaperSpigot 1.7.10 → Paper/Folia 26.1.2.

---

## 1. The spine (unchanged from Proposal C)

* **All domain state is one immutable pure-JDK value** (`KernelState`) published through one
  `AtomicReference<KernelSnapshot>`. Every read path — protection verdicts, chat tags, PAPI,
  GUI, map — is a wait-free, allocation-free snapshot read on the caller's thread.
* **Exactly one writer.** A plugin-owned daemon thread (`fable-kernel`) drains an MPSC intent
  queue, runs the pure `Reducer` (`(KernelState, IntentEnvelope) → (KernelState', List<Effect>)`),
  publishes one snapshot per drained batch. The writer never calls a Bukkit API — enforced by
  the build (the reducer lives in `:kernel`, which cannot see Bukkit).
* **Storage is a projection, never an authority.** Effects append to a CRC32C write-ahead
  journal (group-commit fsync), then batch-project to H2/MySQL on the `fable-storage` thread
  with a `journal_seq` checkpoint. Boot = load DB + replay journal tail. No code path reads
  the database to make a game decision after boot.
* **Version divergence resolves once at boot** into `Capabilities` + a typed `PlatformProfile`
  manifest (Mental two-tier pattern). Era adapters are leaf classes loaded by FQN string only
  behind passing probes. Business logic is era-blind, enforced by architecture tests.
* **Identical threading story on Spigot, Paper, and Folia.** There is no "Folia mode": event
  decisions are inline snapshot reads on whatever thread the event fires on; mutations are
  queue submissions; feedback routes through the `Scheduling` seam.

## 2. Normative amendments (panel-mandated; each fixes a confirmed fatal or grafts a superior mechanism)

**AM-1 — Text delivery: the native-Adventure tier is DELETED.** Jar-wide `net.kyori →
dev.fablemc.factions.lib.adventure` relocation is mandatory (verifyRelocation), and relocated
bytecode cannot interoperate with the server's own Adventure — any compiled native
`Component` sink or Paper `ChatRenderer` is an `AbstractMethodError`/relocation casualty
(confirmed fatal in A and C; B/Mental's invariant adopted). Final pipeline: MiniMessage
parsed **once** at catalog load into shaded+relocated Components → `TextPort` renders once →
delivery via **universal String sinks only**: `§`-legacy strings (hex `§x` downsampled below
1.16 via the probe) and Bungee components through `player.spigot().sendMessage(...)` where
`bungeeChat` probes true (1.8+; hover/click survive). Chat tag injection uses
`AsyncPlayerChatEvent` + `setFormat` on **every** version (it still fires on Paper 26.x);
`:compat-modern` ships **no chat renderer** in v1 (a fully-reflective signed-chat renderer is
a documented post-v1 candidate). ArchUnit gate: no `net.kyori` reference outside
`dev.fablemc.factions.core.text` + `dev.fablemc.factions.core.messages`.

**AM-2 — `PlayerLedger`: COW-sharded structure-of-arrays** (graft from A; fixes C's
8MB-copy-per-death fatal). Member hot fields live in parallel primitive arrays sharded ×256
by member ordinal: `long[] uuidMsb, uuidLsb; double[] powerBase; long[] powerAsOfTick;
int[] factionOrd; short[] rankIdx; byte[] deathStreak; long[] lastActivity, lastDeathAt,
joinedAt; int[] prefsBits; byte[] localeIdx; double[] powerBoost`. A member mutation copies
ONE shard's touched arrays (~4–32KB), not the arena. `MemberDirectory` (UUID→ordinal) keeps
its own 256-shard COW open addressing. Names live in a parallel `String[]` shard column.

**AM-3 — `ClaimAtlas`: two-level spatial sharding** (graft from A). World → region shard
keyed by `(chunkX>>5, chunkZ>>5)` (32×32-chunk blocks) via a COW open-addressed
`long→RegionTable` map; each `RegionTable` is a frozen open-addressed `long[] keys / int[] owner`
(load ≤0.6). Faction land is spatially contiguous, so `/f claim square`, fill, disband and
merge touch 1–4 region shards instead of hash-scattering across thousands. Lookup stays ~2
probes, zero alloc. Chunk key = `((long)x << 32) | (z & 0xFFFFFFFFL)`, computed in-house.

**AM-4 — Reverse claim index + incremental aggregates + reconciliation** (graft from A).
Each faction carries `FactionClaimList` (per-world sorted long[] runs of its chunk keys),
maintained by the reducer alongside the atlas, making disband/unclaim-all/merge
O(faction land). `landCount`, `memberCount`, `powerCacheSum` are incrementally maintained;
a low-frequency system intent (`ReconcileSweep`, every 30min) recomputes a rotating subset,
logs any drift as a WARN (self-healing, drift = bug signal, never silent).

**AM-5 — Paged bulk intents** (fixes C's O(N) writer-blocking mega-intent fatal). Any intent
whose touched-entity count can exceed **1024** is executed in bounded pages with a
continuation: the reducer processes ≤1024 items, emits effects, returns a continuation intent
that the writer re-enqueues on the system lane **behind** already-queued intents, and
publishes the snapshot between pages. Applies to: `UnclaimAll`, `DisbandFaction` (claims
pages → members page → registry final), `MergeExecute`, `TaxSweep` (pages of factions),
`SetZoneChunks`, `AdminClaimChunks`, `SwapConfig` tag re-render (pages of factions).
Interleaved intents observe consistent intermediate states (e.g. a shrinking-but-valid
faction); the final page emits the terminal effect (`FactionDisbanded` etc.). Invariant: no
single reducer step exceeds ~2ms or emits >4096 effects — pinned by a property test.

**AM-6 — Generation-tagged faction handles + disband scrubbing** (graft from A; fixes C's
ordinal-reuse fatal). A faction reference anywhere outside `FactionArena` is
`handle = (generation << 20) | ordinal` (int). The arena bumps the generation on free;
`resolve(handle)` returns null on generation mismatch, so a stale handle can never reach a
reincarnated faction. Additionally the disband final page **scrubs** all inbound references:
relation edges (via the relation reverse index), invites, merge requests, open escrows
targeting the faction. Claim atlas owner slots store handles, so stale-claim resolution is
automatically wilderness-after-mismatch (belt + braces: pages already removed them).

**AM-7 — Durable escrow ordering across the Vault boundary** (fixes C's exactly-once fatal).
The escrow-open record is journaled durably **before** any external Vault mutation:
deposit = `OpenEscrow(DEPOSIT)` intent (writer journals + fsync, CRITICAL tier) → Vault
withdraw on caller thread → `CreditBank(escrowId)` | on Vault failure `CancelEscrow`.
Withdraw = reducer debits bank + journals escrow in one step → `PayoutRequested` effect →
Vault deposit → `SettleEscrow(ok|failed)`. Power-buy identical to deposit. Boot
reconciliation: unsettled DEPOSIT escrows older than the boot ⇒ **refund wallet** (Vault
never observed-credited), unsettled WITHDRAW ⇒ **re-credit bank**; every reconciliation is
logged loudly and recorded in `ff_escrows` history. The unavoidable crash window (after Vault
mutation, before settlement journal) is bounded to one operation, resolved conservatively
(never duplicates money; may refund an amount the crash already delivered — documented), and
counted in bStats debug telemetry.

**AM-8 — Kernel immutability is enforced, not assumed** (fixes C's convention fatal).
Rules: (a) every array reachable from a published snapshot is **frozen by discipline**:
mutation = clone-then-mutate-then-wrap-in-new-record; (b) safe publication is exclusively the
`AtomicReference.set` in the writer loop (happens-before edge for all readers); (c) a
**debug-mode tripwire** (`-Dfable.debug.freeze=true`, on in CI): the writer records
`System.identityHashCode` of every array in the published snapshot into an identity set and
asserts each subsequent batch never mutates one (sampled checksums over hot shards);
(d) jcstress harnesses pin the publication contract for the composite read paths
(claim→relation→flag). ArchUnit: kernel state records expose arrays only through accessor
methods that are package-private to the kernel (readers use typed query methods; no array
ever escapes the kernel API).

**AM-9 — Writer failure boundary + watchdog** (graft from A's critique). Every intent is
reduced inside its own try/catch: a throwing reducer step (kernel bug) emits
`Rejected(INTERNAL_ERROR)` + one full-stack ERROR log + a bStats debug counter, state stays
at the pre-intent value, the writer continues. A second failure of the same intent *type*
within 60s trips a circuit breaker for that type (rejected at submit with `general.busy`,
loud log). If the writer thread itself dies (Error), an uncaught-handler disables the plugin
loudly (`PluginManager.disablePlugin` via `runGlobal`) — a dead pipeline must never look
healthy. Queue rejection policy stays as C §3.5 (bounded player lane → `REJECTED_BUSY`;
unbounded system lane).

**AM-10 — Java-8-line storage stack, pinned** (graft from B; fixes the base-tier fatal).
`HikariCP 4.0.3` (last Java-8 line), `H2 1.4.200` (Java-8 compatible; **NO `NON_KEYWORDS`
URL flag** — that is H2 2.x-only), `mysql-connector-j 8.0.33`. H2 URL:
`jdbc:h2:file:<path>;MODE=MySQL;DB_CLOSE_DELAY=-1`, pool=1, `MERGE INTO … KEY(id)` rewrite in
`H2Dialect`; MySQL `ON DUPLICATE KEY UPDATE`, pool from config (default 10). All three shaded
+ relocated under `dev.fablemc.factions.lib.*`; `META-INF/services/java.sql.Driver` excluded
(explicit `driverClassName` on the relocated class); **`META-INF/versions/**` stripped from
shaded third-party libs before the jvmdg step**; `verifyJdk8Api` (empty allowlist, real JDK-8
rt.jar) runs over the final mega-jar INCLUDING these libs.

**AM-11 — Single-instance advisory DB lock** (graft from B). At boot, before serving:
MySQL `SELECT GET_LOCK('fablefactions:<db>', 5)`; H2 an `ff_meta` lock row with
heartbeat-and-fence (owner UUID + expiry, refreshed every 15s by `fable-storage`, takeover
only after expiry). A second live instance pointed at the same database refuses to boot
read-write with a loud error. Mandatory because memory is authoritative — dual writers would
silently last-write-win the projection.

**AM-12 — Scheduler selection & Folia rules** (graft from B's fatal lesson). The backend
selector keys on **`caps.folia()` alone** (`RegionizedServer` class probe). The presence of
`EntityScheduler` classes (`foliaSchedulers`) is a boot-report fact and an assertion, never a
selector — those classes exist on plain Paper 1.20+. All location-bound integration effects
(WorldGuard region upsert, LWC purge, dynmap marker writes if the dynmap API demands it)
route through `Scheduling.runAt(location)`; per-player feedback through `runOn(player)`;
the Folia CI lane runs the FULL protection/PvP/power/reload suites, not a smoke subset.

**AM-13 — Floor-symbol build gates** (graft from B, scoped realistically). Two ASM gates run
in `check` over `:core` + `:platform` + `:api` classes:
`verifyDescriptorFloor` — no class registered as a baseline (non-`@ProbeGated`) `Listener`
may mention a post-1.7.10 Bukkit type in ANY method/field descriptor;
`verifyNoStickyGetstatic` — no GETSTATIC of a Bukkit enum constant absent at the floor
(constants must flow through the `Constants` resolver / `Enum.valueOf`).
Both gates resolve against a committed floor-symbol manifest: primary source = a full
class+member table dumped from a real PaperSpigot 1.7.10 API jar by the documented
`FloorSymbolDump` tool (`scripts/tools/`); until that jar is procured, the committed manifest
is the curated hazard list from `version-deltas.md` §3.8/Part 1 (every known post-floor type,
member, and enum constant a factions plugin touches) — the gate mechanism is identical, the
table upgrades in place, and the live 1.7.10 matrix lane is the backstop. `@ProbeGated`
listeners are cross-checked against the `ListenerGate` registration table (an exempt class
that is baseline-registered fails the build), and `reportCompatFootprint` prints the compat
class census (fails if it grows without a matrix entry).

**AM-14 — Config baking + per-player sessions** (graft from A). `ConfigImage` bakes derived
hot tables at parse time: zone-verdict lookup tables (`long[]` action×zone→verdict bits),
interactable/container material bitsets (indexed by Material ordinal), power scalars, world
multiplier arrays — published atomically as part of the snapshot (config is state; reload is
an intent). A `PlayerSession` per online player holds region-thread-owned tracking state
(`lastChunkKey`, `combatTagUntil`, warmup handle, GUI session, fly grace) — owned and
mutated ONLY by the player's region thread via `Scheduling.runOn`; identity fields (member
ordinal, permission bits like `factions.bypass`) are writer-published values re-read from
snapshot per event. No cross-thread mutable sharing: the session is confined, the snapshot
is immutable.

**AM-15 — World identity** (graft from B's critique). Worlds are keyed by `World#getUID`
with a small COW `worldIdx` registry maintained on `WorldLoadEvent`/`WorldUnloadEvent`
(both floor-safe). Never object identity, never name string on hot paths (names only in
storage rows).

**AM-16 — GUI/ClickType probes** (graft from B). `SWAP_OFFHAND`/`HOTBAR_SWAP` ClickTypes,
`PlayerInteractEvent#getHand`, `getClickedInventory` — probed via `Enum.valueOf`/method
probes into the `Constants`/`Views` resolvers; number-key + off-hand swaps into GUI/chest
inventories cancelled on 1.9+ (hotbar button 40). `AreaEffectCloud`, `TNTPrimed#getSource`
on 1.7 semantics, and all 1.9+ entity types stay body-only behind probed non-Listener
helpers (`DamageAttribution`).

**AM-17 — Durability tiers, re-tiered** (graft from A per its critique). `CRITICAL`
(group-commit fsync gates the user-facing confirmation): bank movements, escrows, chest
content commits, disband/merge/transfer-ownership. `STATE` (journal write, fsync ≤50ms
window, confirmation immediate): claims, relations, roles, flags, homes, warps, prefs,
power. `LEDGER` (batched, loss-tolerant to the same ≤50ms): audit rows, power history,
bank-tx history rows, inbox. Routine claims are STATE (333 auto-claims/s must not fsync-storm);
the fsync runs on `fable-storage`, never the writer; CRITICAL confirmations are released by
the projector's ack (publish-after-ack applies to the *message*, not the state).

## 3. Module graph (final)

```
:kernel        pure JDK. State records, COW structures, Intent/Effect vocab, Reducer, rules,
               math, MessageKey, ConfigImage. Build-fails on any Bukkit/Adventure/JDBC dep.
:api           public surface (dev.fablemc.factions.api): FableFactionsApi, FactionsView,
               request builders, 7 Bukkit events, PlaceholderSource. compileOnly paper 1.13.2.
:platform      the seam: Scheduling, Capabilities, PlatformProfile, resolvers (Players,
               LegacyMaterials, Views, Feedback, Hands, Nametags, ItemCodec, Constants),
               TextPort, Scope, MenuModel. compileOnly paper 1.13.2. api(:kernel).
:core          the plugin: boot, IntentBus/writer, EffectJournal, StorageProjector, dialects,
               listeners, command trees, GUI, chest/teleport/session engines, config parser,
               MessageCatalog, integrations, bStats, update checker. impl: Hikari/H2/MySQL
               drivers + adventure(+minimessage,+legacy) — all shaded+relocated.
:compat-folia  FoliaScheduling only. compileOnly :platform + paper 1.20.4. FQN-loaded.
:compat-modern ModernItemCodec (serializeAsBytes), BrigadierInstaller, AsyncChunkGet helper.
               compileOnly paper 1.20.6. FQN-loaded behind probes. (No chat renderer — AM-1.)
:probe         self-test plugin for the live matrix (Mental tester pattern, own jvmdg prefix).
```
Compile floor **1.13.2 API** everywhere Bukkit-facing (version-deltas §3.17 recommendation);
`options.release = 17`; the 1.7–1.12 band is runtime-probed; modern extras live in compat
modules. Dependency direction strict: `kernel ← api ← platform ← core ← compat-*`.

## 4. Feature parity contract

The complete WHAT is the reference feature set documented in `docs/research/ref-commands-*.md`,
`ref-engines.md`, `ref-services.md`, `ref-integrations.md`, `ref-resources.md` — every
command, alias, permission node, config key, message key, GUI menu, integration behavior,
placeholder, event, and scheduler cadence. Proposal C §12's feature→module map + deviation
register (D-1…D-15) is adopted verbatim as the parity proof, with deviations config-gated to
reference-default behavior except where the reference behavior is a catalogued bug.
The 45 catalogued bugs map to the six structural guarantees (single writer, projection
storage, durable escrow sagas, scoped lifecycle, kernel purity, probe-gated listeners) —
Proposal C §14 table adopted as the acceptance checklist.

## 5. Build pipeline (final)

Mental recipe kept verbatim (`docs/research/mental-build.md` §12 checklist): Gradle 9.5.1,
shadow 9.4.2 → jvmdg 1.3.6 `DowngradeJar` (`downgradeTo=1_8`, `multiReleaseOriginal=true`,
NEVER `multiReleaseVersions`; classpath = union of core+compat compile classpaths) →
`ShadeJar` (`shadePath="dev/fablemc/factions/lib/jvmdg/"`; probe plugin uses
`dev/fablemc/factions/probe/lib/jvmdg/`) → canonical `FableFactions-<v>.jar`; gates
`verifyDowngrade` (sentinel `dev/fablemc/factions/boot/FableFactionsPlugin` v52 base / v61
versions-17), `verifyJdk8Api` (empty allowlist), `verifyRelocation` (net.kyori + hikari + h2
+ mysql tokens), `verifyProbeIsolation`, plus AM-13's two floor gates and
`verifyKernelPurity`; `failOnJvmdgWarnings` with the console-capture gotchas;
`support-matrix.json` extended to 1.7.10 (floor entry ladder-probed in CI; `floorApi: "1.13"`,
`folia-supported: true`); plugin.yml `${version}`/`${apiVersion}` expansion; japicmp on `:api`
deferred until api-1.0 baseline exists. Relocations: `net.kyori`, `org.bstats`,
`com.zaxxer.hikari`, `org.h2`, `com.mysql` → `dev.fablemc.factions.lib.*`.

## 6. Testing gates (CI = `./gradlew build` + matrix)

Kernel formula pins · reducer property tests (jqwik: relation symmetry, caps, escrow
conservation, cache==recompute, page-bound AM-5) · replay determinism + crash-recovery ·
H2/MySQL projection parity · ArchUnit suite + KernelClasspathTest + TextPort boundary +
AM-13 gates · Scheduling TCK both backends · jcstress publication harnesses (AM-8) · JMH
pins (`Verdicts.decide` 0 B/op; atlas <100ns @5M; reducer ≥100k intents/s; publish p99 <5ms)
· live matrix per support-matrix entry with bytecode-tier assertion + D-9 console-swallow
scan (any swallowed listener-registration failure or framed linkage error ⇒ FAIL) · Folia
lane runs full suites (AM-12).

## 7. v1 beta scope note

Everything above is in scope for v1.0.0-beta.1 except: the fully-reflective signed-chat
renderer (post-v1; legacy chat path is universal), the real-1.7.10-jar floor-symbol table
(curated manifest until the jar is procured; gate mechanism ships now), and the live
integration matrix bootstrapping every one of the 18 entries in CI (the Gradle machinery and
probe plugin ship; per-entry enablement follows hardware/network availability).
