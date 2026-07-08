# Critique A — Correctness & Maintainability (concurrency specialist, hostile review)

Target: `docs/design/proposal-A.md` ("The Data-Oriented Core").
Cross-checked against `docs/research/pvp-bugs-concurrency.md` and `docs/research/pvp-bugs-logic.md`.

Verdict: **sound-with-fixes.** The single-writer + COW-snapshot spine is the right idea and
genuinely kills most of the catalogued RMW/TOCTOU classes. But the proposal repeatedly asserts
impossibility it has not actually achieved. Several §14 "structural kill" claims are falsified by
the design's own text elsewhere, and the highest-traffic shared structure on the hot path (the
session-lookup map) is given none of the COW discipline the claim index gets. The writer is a
god-actor with no failure boundary and no defined backpressure policy — two omissions that, under
load or a single buggy op, silently wedge the entire plugin.

Below: section, flaw, concrete failure scenario, fix. Ordered most-severe first.

---

## FATAL-1 — The single writer has no per-Op exception boundary; one throwing Op wedges all mutation and persistence

**Section:** §3.1 (Core Writer), §2 (all state "owned exclusively by the writer").

**Flaw.** Every mutation in the entire plugin is an `Op` applied on one daemon thread
(`FableCoreWriter`). The proposal never states what happens when an Op handler throws. A single
`grep` of the doc finds *zero* exception/catch/uncaught language for the writer loop (only the
reconciler's `assemble` throw is handled, §9). A dedicated `Thread` whose `run()` loop lets an
exception escape **terminates**. When the writer dies:

- reads keep working (snapshots are still published), so the server looks healthy;
- **no** further Op is ever applied — claims, joins, power, bank, relations, session
  create/evict, chest commits, config reload all silently stop;
- the bounded MPSC queue (FATAL-2) fills and never drains;
- nothing is journaled, so the storage thread goes idle and the DB freezes at the last flush.

**Failure scenario.** An admin runs a rare edit (`/fa power set` on a player whose ordinal was
just freed, or a merge that trips an unhandled edge in `Ops.Merge`), the reducer throws an
`ArrayIndexOutOfBounds`/`NPE`, the writer thread dies. Twenty minutes later the queue is full,
new `/f claim`/`/f bank` commands answer nothing or block, and the server hangs on the next full
queue. Restart loses ≤250 ms plus every unconfirmed op since the death. Nothing in the log points
at the original edit. This is a strictly worse failure than any single catalogued bug because it
takes the whole domain down.

**Fix.** Wrap each Op application in try/catch on the writer loop: log with op id + payload, emit a
user-facing "internal error" deny effect, **continue** to the next op. Never let the writer loop
exit except on shutdown. Add a liveness watchdog (writer heartbeat epoch; if it stalls > N ms,
log loudly and optionally restart the writer draining the surviving queue). The property-test
suite (§13.2) must include fault-injection ops that throw and assert the writer survives.

---

## FATAL-2 — No defined full-queue / backpressure policy for normal operation; every possible policy breaks a headline promise

**Section:** §3.1 ("bounded MPSC array queue, 65,536 slots"), §3.5 (only *shutdown* says "queue
rejects").

**Flaw.** The queue is bounded, but the doc specifies overflow behavior **only at shutdown**.
Under normal load the policy is undefined, and all three implementable policies violate a stated
axiom:

- **Block the producer** → the producer is a region/main thread → main-thread stall. This is
  exactly the "zero main-thread IO is structural" (A1) / "readers never wait" (A3) promise
  inverted. A slow writer now stalls the game thread, which is worse than the async DB writes it
  replaced.
- **Drop the op** → a silently lost claim / bank move / join / power settle → the "single-writer
  linearizable domain" (§15.2) now loses writes, reintroducing the lost-update class (BUG-1/2/4)
  by a different door.
- **Spin/park the producer** → same as block.

**Failure scenario.** A 1,000-player server hosting a raid: hundreds of deaths (`PowerDeathOp`
each), auto-claim ops from sprinting attackers, bank ops, chest commits — all while the writer is
mid–power-tick (§4e admits 3–6 ms during which it *drains nothing*). The 65,536-slot queue fills.
If policy = block, the region thread servicing the raid stalls mid-tick → TPS collapse (the very
symptom of BUG-9 the design set out to kill). If policy = drop, a player's `/f bank deposit` Op is
discarded after their Vault wallet was already reserved in phase A → money destroyed unless the
reserve is also unwound, which the drop path doesn't describe.

**Fix.** Specify it explicitly. Recommended: **never block a game thread.** Submit with a
non-blocking `offer`; on failure, fail the command in phase A with a "server busy, try again"
message and unwind any phase-A Vault reserve (compensating credit). Size the queue for worst-case
burst, add per-producer fairness, and shed *only* at the phase-A boundary where a user-visible
denial + reserve rollback is safe — never silently inside the reducer. Add a load test that drives
the queue to saturation and asserts (a) no game-thread block, (b) no reserved funds lost.

---

## FATAL-3 — Faction-chest persistence contradicts "the writer never touches Bukkit," and the shared-live-Inventory dupe (BUG-7) is re-opened on Folia

**Section:** §2.5 (ownership row: *"Open chest inventories | region thread (Bukkit) **+ writer for
content ops** | viewers"*), §3.1 ("The Core Writer… **never touches the Bukkit API** — that is what
makes it legal on Folia"), §7.3, §14 concurrency-bug-7 ("**structurally gone**").

**Flaw A — two writers, one of which must touch Bukkit off-thread.** §2.5's own ownership table
names *two* writers for chest inventories (region thread AND the Core Writer), which directly
violates the single-writer axiom A2. To persist a chest the writer must read the live Bukkit
`Inventory`'s `ItemStack[]`. Bukkit inventories are **not** thread-safe; reading one from the
writer thread is an off-thread Bukkit access — the exact BUG-19 class the design forbids — and it
falsifies "the writer never touches Bukkit." The 10 s dirty-timer commit is described as a
writer-internal deadline (§3.1), so the *writer* is the thing snapshotting a live Bukkit inventory.

**Flaw B — Folia re-opens the dupe.** §7.3 says two openers "get the same instance (Bukkit then
handles viewer concurrency)." That is true on a single-main-thread server. **It is false on
Folia**, where two viewers of one faction chest are two different players owned by two different
region threads, and Folia provides *no* global lock serializing their inventory clicks against a
shared `Inventory`. Concurrent `InventoryClickEvent`s from two regions mutating the same
`ItemStack[]` is a data race → the same last-write-wins dupe/loss BUG-7 describes. So §14's
"structurally gone" is only true on non-Folia servers — precisely the platform the whole design
claims to support first-class.

**Failure scenario.** On Folia: A (region 1) and B (region 2) both have "vault" open with 64
diamonds. Both take the stack in overlapping ticks; two region threads write the backing array with
no happens-before → torn contents or duped stack. On any server: the writer's 10 s dirty-timer
fires mid-click and reads a half-mutated `ItemStack[]` → persists a torn snapshot; kill -9 before
the next commit loses the diff.

**Fix.** The inventory must be **region-confined**, not writer-touched. Snapshot contents to a
Bukkit-free blob **on the owning region thread** (phase A / on close / on a region-scheduled dirty
tick), and submit that immutable blob to the writer as the `ChestCommitOp` payload — the writer
never sees an `Inventory`. On Folia, confine each faction chest to a single region (e.g., anchor to
the chest block's region and route all opens through `Scheduling.runAt` on that region), or
serialize opens through a per-chest single-viewer lock. Fix §2.5 to name exactly one writer.

---

## FATAL-4 — The session-lookup map is read lock-free on every hot path but mutated (and resized) in place by the writer, with none of the COW discipline the claim index gets

**Section:** §2.1 (`PlayerSession` "found via a `UuidIntHash` (uuid → session slot)"), §4(a)/(d)
(`sessions.get(uuid)` on move and async chat), §2.5 (publication column specifies session *fields*,
never the *map*).

**Flaw.** The proposal lavishes COW-shard + `AtomicReferenceArray` publication on the claim index
and whole-table volatile COW on relations/views — but the `UuidIntHash` that every hot path probes
first is a plain open-addressing table (parallel `long[] msb, long[] lsb, int[] val`) with **no
stated publication or copy-on-write**. It is written by the writer on every join (insert) and quit
(delete, with backward-shift per §2.2's deletion note), and a join that crosses the 0.6 load factor
triggers a **resize** (realloc + rehash of the backing arrays). Meanwhile the async
`AsyncPlayerChatEvent` thread and the region move thread call `sessions.get(uuid)` with no
synchronization.

**Failure scenario.** Peak login storm (server restart, 200 players reconnecting): the writer
inserts sessions, hits the resize threshold, and swaps the backing arrays. A concurrent chat/move
thread mid-probe reads a partially-published new array (arrays are not `final`/volatile here) or
follows a probe chain that backward-shift-delete is concurrently rewriting → returns the **wrong
slot** (another player's session → wrong faction tag, wrong protection verdict), a spurious
**miss** (player treated as factionless on their own land — denied building), or an **unbounded
probe loop** (a chunk-cross event never returns → region thread hangs). This is a genuine hot-path
data race the design's own methodology (COW everything a reader touches) should have caught.

**Fix.** Give the session map the same treatment as the claim index: publish an immutable
snapshot via a volatile/`AtomicReference`, or use a lock-free open-addressing table designed for
concurrent readers (final arrays, tombstones instead of backward-shift, monotonic capacity swap
with a volatile length + acquire/release on the array reference). Add a stress test: hammer
`get()` from N threads while the writer inserts/removes/resizes and assert no torn/missed/looping
reads. The same audit applies to the `WorldRegistry` map (§2.2) and any UUID↔ordinal edge map
consulted off the writer.

---

## MAJOR-5 — `PlayerSession` is mutable-in-place with per-field release stores, so A3's "atomically-published snapshot" is false for the most-read structure; torn multi-field reads and read-your-writes gaps follow

**Section:** §2.1 ("Fields written only by the writer via release stores, read by any thread via
acquire"), A3 (readers read "immutable, atomically-published snapshots"), §7.4 (combat tag / fly /
warmup).

**Flaw.** Unlike the claim shard / relation table / `FactionView` (all immutable, whole-object
publish), a `PlayerSession` is a *mutable* object whose ~15 fields are updated individually. A
reader that needs a *consistent set* of fields (factionOrd + rankPriority + chatTag on a faction
join; bypass + overriding + factionOrd on an admin toggle) can observe a **torn combination** —
new factionOrd with stale rankPriority/chatTag. The axiom A3 says readers only ever see atomic
snapshots; the session mechanism does not provide that.

Worse is the **read-your-writes** gap created by routing every session write through the async
writer. Fields that a fast game event must *set* and a closely-following command must *read* —
`combatTagUntil` (set by `DamageIntake`), `flyOn`, `warmupHandle`, `autoMode`, `lastChunkKey` —
are all writer-owned per §2.5. Between the event and the writer applying the update Op there is a
multi-millisecond window in which the acting player reads a stale value.

**Failure scenario (combat-tag escape — reopens logic-bug #13).** Player takes a hit
→ `DamageIntake` submits a `combatTagUntil` Op → player instantly types `/f home` → the home
command's phase-A guard reads `session.combatTagUntil`, which the writer hasn't updated yet → tag
absent → teleport proceeds → writer sets the tag a tick too late. The first-party combat tag §7.4
touts as the fix for BUG-#13 is bypassable by racing the command against its own async write.
Symmetrically, warmup cancel-on-move/damage (§7.4) can lose the race and teleport anyway.

**Failure scenario (duplicate transition — event ordering).** `lastChunkKey`/`lastOwnerOrd` are
writer-owned (§2.5) but read by `MoveIntake` to detect transitions (§4a). A sprinting player fires
~20 moves/s; the writer's update Op lags, so several consecutive moves read the *same* stale
`lastChunkKey` and each re-detects the border cross → duplicate territory-enter titles and
**duplicate auto-claim Ops** for adjacent chunks. The writer re-validates and no-ops exact
duplicates, but duplicated titles/effects and racing auto-claims on *different* chunks during the
lag are user-visible.

**Fix.** Two options. (a) Make the session an immutable record published via a single volatile
reference (true A3 compliance) so multi-field reads are consistent — accept the extra small
allocation per change (changes are human-rate except `lastChunkKey`/`combatTag`). (b) For the
handful of fields that are set-by-event/read-immediately, make them **region-confined** local state
owned by the acting player's region thread (never the writer) with an explicit note that they are
outside the single-writer model, and reconcile that with A2/§2.5 honestly. Do not leave the doc
claiming both "one writer" and "region thread sets the combat tag."

---

## MAJOR-6 — Async session-create Op races the player's first events → null / factionless session on the hot path

**Section:** §7.1 (`JoinQuitIntake` "session create Op"), §4(a) (`sessions.get(...)` then
`s.worldIdx(...)` with no null guard shown).

**Flaw.** Session creation is an Op applied asynchronously by the writer. Between `PlayerJoinEvent`
and the writer processing the create Op, the player can move, interact, or chat. `sessions.get`
returns a miss.

**Failure scenario.** Player joins standing in their own claim, immediately places a block. The
move/interact fires before the writer creates the session → `sessions.get` = miss → either NPE on
`s.worldIdx()` (§4a dereferences `s` unguarded) or, if guarded, the player is treated as factionless
and denied building on their own land for a few ms. On a laggy join (writer mid-tick) the window is
larger. Rejoin-before-evict is the mirror hazard: a create for a slot the previous session hasn't
vacated.

**Fix.** Either create the session **synchronously in phase A** on the join thread (it is
player-local state; a lightweight non-writer creation with a writer confirmation Op is defensible),
or hold events for a session-less player in a "pending" state until the create Op lands. Every hot
path must null-guard `sessions.get` and define the session-absent verdict explicitly (default: treat
as no-faction but do **not** deny on own claim — impossible to know without the session, so bias to
the server default). Add a test that fires a move/interact in the same tick as join.

---

## MAJOR-7 — Cross-structure staleness: a snapshotted claim owner is combined with *live* per-faction arrays indexed by raw ordinal → ordinal-reuse misattribution and array-growth AIOOBE

**Section:** §2.2 (`owners[i]` = raw faction ordinal, **no generation tag**), §2.4 (`AtomicLongArray
flagBits`, `int[] landCount`, `double[] bank` — *live* arrays indexed by ordinal), §2.1 (ordinals
freed to a free-list with a 16-bit generation counter).

**Flaw.** The design carefully generation-tags long-lived faction *handles* to prevent
reincarnation ABA — but the claim shard stores a **raw** ordinal, and the per-faction hot scalars
are **live mutable arrays**, not COW snapshots. So a protection decision mixes a *snapshotted*
owner (from a COW shard read once) with a *live* flag/relation lookup indexed by that ordinal.
Consistency across the two is not guaranteed on ordinal reuse or array growth.

**Failure scenario (reuse misattribution).** Reader on the move/protection path reads
`owner = shard.get(chunk) = 5` from a shard it captured. Concurrently faction 5 disbands (ordinal
freed) and a brand-new faction reincarnates ordinal 5 within the reader's snapshot-hold window.
The reader then evaluates `flagBits.get(5)` / `relationBetween(mine, 5)` against the **new**
faction's live data → a chunk that belonged to the disbanded faction is briefly protected by the
unrelated new faction's flags/relations. The generation guard that exists for handles does not
exist on the shard's raw ordinal, so the hot path is exactly where ABA is *not* defended.

**Failure scenario (growth AIOOBE).** A new faction pushes the max ordinal past the current
`flagBits`/`landCount` capacity. The writer allocates a bigger `AtomicLongArray` and swaps the
reference. A reader that resolved the new ordinal but still holds the *old, smaller* array
reference indexes out of bounds → `ArrayIndexOutOfBoundsException` on the protection path (the
claim index solved growth via sharding + COW; these scalar arrays have no stated growth
publication).

**Fix.** Either (a) fold the per-faction hot scalars into the same COW publication unit as the
owner lookup (read owner and flags from one consistent snapshot), or (b) generation-tag the shard
owner too and re-validate the generation before trusting flag/relation lookups, treating a
generation mismatch as wilderness/deny. For growth, publish scalar arrays via a volatile reference
with capacity that only ever grows behind a release store; readers acquire the reference once per
decision.

---

## MAJOR-8 — Shutdown ordering is internally contradictory: intake is rejected (step 2) before chest-commit Ops are submitted through the writer (step 4)

**Section:** §3.5 steps (1) close scopes → (2) **stop intake (queue rejects)** → (3) drain queue →
(4) **force-persist open chest inventories through the writer** → (5) journal barrier → (6) close
pool.

**Flaw.** Step 2 makes the queue *reject* new Ops. Step 4 then wants to "force-persist open chest
inventories **through the writer**," which means submitting `ChestCommitOp`s — into the queue that
step 2 just closed. As written, the chest-commit ops are rejected by the intake stop that ran
before them, so open chests are **not** flushed on shutdown — reopening BUG-15, which §14 claims is
killed. Additionally, step 1 unregisters `ChestIntake` (the listener) *first*; if the force-close
relies on `InventoryCloseEvent` reaching that listener, the path is already gone.

**Failure scenario.** Server stop with two open faction chests. Step 1 kills the close listener,
step 2 closes intake, step 4's commit ops bounce off the rejecting queue → last few deposits lost.
Combined with FATAL-3's off-thread snapshot problem, the shutdown chest path is doubly broken.

**Fix.** Reorder: (1) stop *external* intake only (reject *command/event*-originated ops), (2)
drain, (3) submit an internal "final flush" batch (chest commits, any dirty state) that is exempt
from the external-intake gate, (4) drain again to empty, (5) journal barrier, (6) close scopes and
pool last. Snapshot chest contents on the owning region thread *before* closing regions (see
FATAL-3), not via the writer. Make the intake gate distinguish external submissions from
shutdown-internal flush ops.

---

## MAJOR-9 — Config reload does not rebuild incrementally-maintained derived sets, and the "baked tables" publication atomicity is unspecified

**Section:** §8.1 (Settings swap via `ReloadOp`; hot settings "baked into primitive tables at
parse"), §4(e) (eligible-power set "maintained incrementally… players enter on loss, leave on
reaching max/freeze").

**Flaw A — stale derived sets after reload.** The power-tick eligible set is maintained
incrementally against the *old* min/max/source-enabled predicate. A `/fa reload` that changes
`max-power`, freeze rules, or source gating does not, per the doc, rebuild this set. Players who
were removed from the eligible set at the old max are never re-added at a higher new max → they
never regen into the new headroom until reboot. Same hazard for any incrementally-maintained
predicate keyed on a tunable (raidable eligibility thresholds).

**Flaw B — non-atomic baked tables.** §8.1 swaps the `Settings` object via one volatile, but the
"baked primitive tables" (zone verdict `long[]`, interactable material bitsets, power scalars) are
described as *separate* structures. If they are swapped independently of the Settings reference, a
reader can observe new `Settings` with old `zoneVerdicts[]` (or, if the writer mutates the array in
place, a torn array mid-write). §2.5 also lists `Settings` and `MessageCatalog` as **two separate
volatiles** — a reload that adds a message key referenced by new config can be observed with new
Settings but old catalog → deny message fails to resolve.

**Failure scenario.** Admin raises `max-power` from 20 to 40 via `/fa reload`. Players at 20 stay
stuck at 20 (never re-entered the eligible set). Separately, a reader mid-reload evaluates a
protection verdict against a stale zone table → wrong safezone verdict for one event.

**Fix.** The `ReloadOp` must (a) rebuild every incrementally-maintained derived set from
authoritative state after swapping Settings, and (b) bake all derived tables **into the immutable
Settings object** so a single volatile store publishes config + all derived tables atomically.
Publish `Settings` and `MessageCatalog` behind one reference (or accept and document that message
resolution may lag one reload). Add a reload test that changes a threshold and asserts the derived
set/table reflects it.

---

## MAJOR-10 — Storage latency backpressures the single writer via the "release CRITICAL effect only after commit" coupling, and the two-lane × three-durability model has no defined atomic commit boundary

**Section:** §3 diagram ("confirm CRITICAL effects"), §5.1 (CRITICAL: "flush fires immediately and
the confirmation Effect released only after commit"; hot lane = fixed 64-B ring, cold lane = object
queue), §4(e) (power-tick journal into a preallocated SPSC ring).

**Flaw A — coupling.** To release a CRITICAL confirmation "only after commit" while preserving
ordering, the writer must track pending CRITICAL ops against storage's commit sequence. If a
CRITICAL commit is slow (MySQL network hiccup, lock wait), either the writer blocks (stalls *all*
ops — power ticks, moves, unrelated bank ops) or CRITICAL confirmations pile up unbounded. Combined
with FATAL-2, a slow DB → delayed confirmations → producers keep submitting → queue fills → game
thread stall. The SPSC journal ring is fixed-size; a 50k-player power tick that outruns the storage
drain rate forces the writer to block on ring space → same stall. "0 allocations (ring slots)"
quietly assumes storage always keeps up.

**Flaw B — no cross-lane atomic boundary.** A single logical CRITICAL op spans both lanes: a
faction *create* writes cold-lane rows (name, UUID) and hot-lane rows (ordinal counters, initial
claim); a chest commit is CRITICAL but its blob lives in the *cold* lane. The doc gives the hot
lane a 64-B ring and the cold lane an object queue, and a single `meta.last_applied_seq`, but never
defines a commit boundary that makes both lanes' records for one op durable together.

**Failure scenario.** kill -9 after the hot-lane records of a faction-create flush but before the
cold-lane name row → reload sees an ordinal/counter with no `factions` row (or vice versa) → torn
faction, constraint gap, or a claim pointing at a non-existent faction id. The crash drill (§13.7)
only asserts "no confirmed-op loss"; it does not assert cross-lane atomicity of a single op.

**Fix.** Give the writer a bounded, non-blocking path to storage and a **shedding/latency budget**:
if storage falls behind, degrade CRITICAL confirmation latency with a bound and surface it (never
block the writer indefinitely). Assign every op a single monotonic sequence and flush all of its
lane records in one JDBC transaction keyed on that sequence, advancing `last_applied_seq` only when
*both* lanes for ops ≤ seq are durable. Add a crash-drill assertion for a faction-create /
chest-commit torn exactly at the inter-lane boundary.

---

## MAJOR-11 — Incrementally-maintained counters (`landCount`, `memberCount`, `factionPowerCache`) have no runtime reconciliation; a single missed update or FP drift corrupts raidable/cap decisions permanently until reboot

**Section:** §2.2/§2.4 (counts are "maintained `int[]`… so *no* counting query ever happens";
`factionPowerCache` "recomputed incrementally… on member power change").

**Flaw.** The design sells "no counting query ever happens" as a feature — but that removes the
self-healing that a periodic recount provides. Any single reducer path that increments without a
matching decrement (a failed-partway claim op, an unusual leave/merge path) permanently desyncs
`landCount` from the authoritative per-faction claim list. `factionPowerCache` additionally
accumulates floating-point rounding over millions of `+= delta` operations, drifting from the true
sum. Both feed the raidable sweep (land ≤ maxLand, power thresholds), so drift silently
mis-flags factions raidable or not. Recovery only happens at reboot (load path recomputes from the
claims/players tables).

**Failure scenario.** Over weeks of uptime a rare merge/unclaim-all edge leaves `landCount[F]` one
low; F can now claim one chunk over its true cap, or never becomes raidable when it should. No
error, no query to catch it — the property tests assert invariants in-test but cannot catch a
production-only path.

**Fix.** Add a low-frequency writer-internal reconciliation sweep that recomputes `landCount`
(from the per-faction claim list length it already maintains), `memberCount`, and
`factionPowerCache` (from the ledger) and logs + corrects any drift. Represent `factionPowerCache`
as a running sum with periodic exact recompute, or as an integer fixed-point to avoid FP drift.
Keep it O(factions), off the game thread — it fits the existing raidable-sweep cadence.

---

## MAJOR-12 — Legacy-import trigger ("v2 tables absent") races schema creation and is not crash-resumable

**Section:** §5.4 ("if v2 tables are absent and the original plugin's tables are detected, a
one-shot `LegacyImporter` maps them"), §5.2 (`SchemaManager` emits all v2 DDL).

**Flaw.** The import *decision* is "v2 tables absent." But `SchemaManager` creates the v2 tables
(DDL) at boot. Order matters and is unspecified: if DDL runs before the importer's precondition
check, "v2 tables absent" is already false → import never runs (chicken-and-egg, silent data loss —
the server comes up on empty v2 tables and the legacy data is ignored). If DDL runs after, a crash
*during* import leaves v2 tables present but partially populated → next boot sees "v2 tables
present" → import skipped → half the legacy factions/claims permanently missing. `meta.imported_from`
records provenance but is not consulted as the guard.

**Failure scenario.** Admin migrates a live 500k-claim server; import dies at 60% (OOM, kill).
Restart: v2 tables exist, importer skips, 40% of claims/relations/banks silently gone.

**Fix.** Guard the import on an explicit, transactional completion flag in `meta`
(`import_state = none|in_progress|done`), not on table presence. Run the import inside a
transaction (or with idempotent upserts keyed so re-running resumes/overwrites), set `done` only on
full success, and refuse normal boot while `in_progress` (or auto-resume). Order DDL-then-import
explicitly and test a mid-import kill.

---

## MAJOR-13 — Feature-enablement (reconciler) and Op-admissibility (reducer) are decoupled: a disabled feature's listeners are removed but its Op path in the writer still mutates state

**Section:** §9 (FeatureUnit scope close removes listeners/tasks), §1/§4/§14 (all Ops handled by
one kernel reducer), §10.1 (public API "mutation entry points that submit Ops").

**Flaw.** Disabling a feature via `/fa reload` closes its `Scope` (unregisters listeners, cancels
tasks) — but the corresponding Op handlers live in the single centralized kernel reducer, which is
never disabled. So a Bank Op already in the queue, or one submitted by a *lingering post-commit
effect*, an *integration adapter*, or the *public `FactionsApi`* (which "submits Ops" regardless of
feature state), still reaches the reducer and mutates bank/economy state for a feature the admin
just turned off. The reconciler makes listeners reversible; it does not make the writer's op-set
reversible.

**Failure scenario.** Admin disables the economy feature at runtime. A queued `/f bank deposit`
Op (or a PAPI/other-plugin call through `FactionsApi.deposit`) still applies, moving money and
firing a bank ledger write for a disabled subsystem → inconsistent, surprising, and hard to
diagnose.

**Fix.** Gate op-admissibility on feature state in the reducer (reject ops belonging to disabled
features with a clear denial), or make features own their op-handler registration the same way they
own listeners (feature-scoped reducer fragments). Document and test that disabling a feature
rejects both its inbound events *and* its inbound ops (including via the public API).

---

## MAJOR-14 — WorldGuard region-sync mirroring is post-commit, opening a protection-gap window on freshly claimed chunks where `BuildIntake` fast-allows but the WG region does not yet exist

**Section:** §10.2 (WG "region-sync mode… claims mirrored as ProtectedCuboidRegions… driven by
post-commit effects"), §7.1 (`BuildIntake` "allow-fast-path when WG already enforced at NORMAL").

**Flaw.** In region-sync mode the plugin defers primary enforcement to WG and fast-allows. Mirror
creation is a *post-commit effect* on `runGlobal` — asynchronous to the claim commit. Between the
claim committing and the WG region existing, the chunk is claimed (so the plugin's own default-deny
may be bypassed by the fast-allow path) but WG isn't protecting it yet.

**Failure scenario.** Faction claims a border chunk in WG-sync mode; in the ~1-tick+ window before
the mirror effect runs, an enemy standing there breaks a block — `BuildIntake` fast-allows
(assuming WG enforces) and WG has no region yet → grief slips through on freshly claimed land.

**Fix.** Do not fast-allow a chunk until its WG mirror is confirmed present (track per-chunk sync
state; fall back to first-party enforcement until the mirror lands). Or create the WG region
synchronously/ordered-before releasing the claim confirmation. Add a test that builds on a
just-claimed synced chunk within the mirror window.

---

## MAJOR-15 — `FactionsApi.power(uuid)` (and other per-player reads) has no race-free published source for offline players

**Section:** §10.1 (read-only API "served from snapshots… `power(uuid)`"), §2.5 (`PlayerLedger`
readers = "writer only"; `PlayerSession` = online only).

**Flaw.** Per-faction scalars (bank, landCount, flags) are published via atomic arrays for display
reads. But per-*player* power lives in the `PlayerLedger` `double[] power`, marked writer-only, and
is snapshotted into sessions **only for online players**. An offline player has no session. So the
public API's `power(uuid)` for an offline player has nowhere race-free to read: either it touches
the writer-only `double[]` from a caller thread (a data race with no happens-before — the exact
class the design forbids elsewhere), or it must submit an op and block (slow, and PAPI calls are
"hot" per §10.2). §5.3 also claims "no cache-miss path exists afterward," which is inconsistent with
needing a writer round-trip to read offline power.

**Failure scenario.** Another plugin / PAPI placeholder reads `%fablefactions_player_power%` for an
offline player on the main thread → races the writer's power tick on the `double[]` → torn/garbage
double, or blocks the main thread on a writer round-trip.

**Fix.** Publish per-player power (and any other API-exposed per-player scalar) via a race-free
structure readable off the writer — e.g., an `AtomicLongArray` of `doubleToRawLongBits(power)`
indexed by player ordinal, updated by the writer with an opaque/release store, or a periodically
published COW snapshot for the API. Define the API contract for offline reads (eventual/last-known)
and test concurrent API reads against a running tick.

---

## Additional issues (lower severity, still worth fixing)

- **§9 scope close "failures suppressed-collected" + converge re-add = latent BUG-3.** If a
  listener's `HandlerList.unregister` throws during scope close, the failure is swallowed and
  `converge()` re-adds a fresh listener → accumulating duplicate handlers across reloads (the exact
  BUG-3 the design claims killed). The 5×-reload tester catches it only if the failing path is
  exercised. Fix: on unregister failure, retry/verify handler removal and fail loudly rather than
  proceeding to re-add.

- **§7.1 `AllyUnlockIntake` un-cancels at HIGHEST (`ignoreCancelled=false`).** Un-cancelling an
  event another protection plugin legitimately cancelled at HIGH/HIGHEST is a well-known
  anti-pattern that resurrects events other plugins killed (region flags, anti-grief). Prefer
  *not cancelling in the first place* for synced-ally chunks over cancel-then-uncancel. This is the
  same fragile mechanism the original used (logic-bug #21 territory).

- **§8.1 `overrides.yml` overlay silently shadows the file.** With `effective = overlay ?? file ??
  default`, any key ever written via GUI/API is thereafter served from the overlay — a subsequent
  admin hand-edit of that key in the YAML file is silently ignored. This is a confusing
  maintainability foot-gun. Fix: surface which keys are overlay-shadowed (boot warning / comment
  injection), or let a file edit with a newer mtime win, or provide `/fa config clear-override`.

- **§2.4 `RankTable` immutable arrays: role deletion leaves dangling `rankIdx`.** Deleting a role
  while members hold it must reassign those members (to default/next rank) atomically in the same
  Op; otherwise `PlayerLedger.rankIdx` dangles and authority checks read garbage. Not addressed in
  the feature map (§12 role ops). Add the reassignment invariant + test.

- **§2.2 runtime world load/unload vs the dense `worldIdx` registry.** `WorldRegistry` is described
  as boot-time dense. Worlds can be loaded at runtime (Multiverse `/mv load`); claiming there needs
  a new `worldIdx` + `WorldClaims` (8192 shards) allocated on the writer and published to region
  threads. Unload cleanup and ordinal reuse are unspecified, and the registry map is read off the
  writer (same publication concern as FATAL-4). Define runtime world lifecycle.

- **§7.1 protection matrix — likely-missing vectors.** `BlockDispenseEvent` (a dispenser just
  outside a claim firing water/lava/arrows across the border) and `EntityInteractEvent` (mobs
  toggling plates/farmland) are not in the intake matrix. The design claims "event-complete"
  (§14 logic-6); enumerate these or justify the omission.

- **§10.1 API mutation `CompletionStage<Result>` ordering vs the writer.** The API returns a stage
  completed when the op commits. Two API mutations submitted back-to-back from the same caller are
  linearized by the writer, but the *stages* complete on storage-confirm order, which for mixed
  STATE/CRITICAL durability may complete out of submission order — a caller chaining
  `.thenCompose` may observe effects in an unexpected order. Document the ordering guarantee.

---

## What holds up (credit where due)

- The single-writer linearization genuinely kills the pure RMW/TOCTOU classes (BUG-1/2/4/5/6/8/16/
  17/18) **for state the writer solely owns and reads** — that is most of the domain, and it is the
  right spine.
- Field-scoped journal with "no full-row save API" structurally removes BUG-1's clobber, provided
  the coalescer respects per-op sequence (verify this in implementation).
- Interned symmetric relation matrix (BUG-16/logic-21) and literal-component text pipeline
  (logic-7 injection) are genuinely structural kills with no syntax channel to exploit.
- Reserve→settle→compensate with post-commit confirmation is the correct shape for BUG-11/12 —
  *if* FATAL-2's drop path also unwinds the reserve.
- Schema-level uniqueness (`name_folded` UNIQUE, invite/merge PKs) is a sound back-stop for the
  TOCTOU classes even independent of the writer.
- The listener-descriptor gate (G5) and reload leak-freedom tester (5× reload → identical handler
  counts) are strong, testable defenses — assuming FATAL-1/8 and the scope-close-suppression issue
  are fixed so the tester exercises the real failure paths.
