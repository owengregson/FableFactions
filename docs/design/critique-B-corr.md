# Hostile Correctness & Concurrency Critique — Proposal B (Frozen-Seam Architecture)

Reviewer stance: concurrency specialist, adversarial. I attacked `docs/design/proposal-B.md`
against every bug class catalogued in `docs/research/ref-bugs-concurrency.md` (C-BUG-n) and
`docs/research/ref-bugs-logic.md` (L-BUG-n). For each I asked: does the design make the bug
*structurally impossible*, or does it merely *assert* impossibility while leaving a reachable
window? Verdict up front: the single-writer core is a genuinely strong idea and kills the
lion's share of the RMW/lost-update catalog. But several load-bearing claims ("no
read-modify-write anywhere," "one copy of the truth," "no IO on the writer ever," "≈0 crash
loss for CRITICAL") are **overstated** — each has a concrete reachable counterexample below.
The most serious are re-entrancy of the single pipeline, the dual in-memory representation,
journal backpressure blocking the game thread, and a family of Folia-only read-plane hazards
that the test matrix never exercises.

Severity legend: **FATAL** (breaks a central correctness claim; must fix before build),
**MAJOR** (real user-visible defect or reintroduces a catalogued bug), **MEDIUM**, **LOW**.

---

## FATAL-1 — The single pipeline is re-entrant; `ensureOn`-inline during event dispatch corrupts state
**Sections:** §4.2, §4.3 (mutation pipeline steps 1–6), §3.3 SchedulePort `ensureOn`, §10.1
(RealmCommands facade), §14 C-1/C-5/C-6.

§4.3 step 2 fires a **cancellable Bukkit event** (`FactionDisbandEvent`,
`FactionChunkClaimEvent`, `FactionBankTransactionEvent`, …) *inside* `Realm.execute`, on the
global thread, **between** `decide` (step 1, which computes `decision.mutations` against the
current `state`) and `state.apply` (step 3). `Realm.submit` "ensureOn-inlines when already on
the global thread — zero hop on Paper." Nothing in the design forbids a synchronous nested
submission during that event dispatch.

**Failure scenario:** `/f disband` → `Realm.execute(DisbandFaction)` → step 2 fires
`FactionDisbandEvent`. A handler — a third-party plugin, *or one of your own* integration
listeners (`LwcInterop` bulk cleanup on claim change, `WorldGuardRegionSync`, a predefined-
faction reseed hook) — reacts by submitting another `RealmCommand` (e.g. unclaim, transfer,
reseed) through the public `RealmCommands` facade. Because we are already on the global
thread, `ensureOn` **inlines** it: a nested `Realm.execute` runs `decide→apply→journal→
effects` to completion, mutating `state` and the faction-id interning. Control returns to the
outer `DisbandFaction`, which now runs step 3 `state.apply(decision.mutations)` using
`decision` computed at step 1 **against the pre-nested state** — a stale snapshot. Result:
resurrected/duplicated claims, a double-freed faction id, or a lost update. This is precisely
the TOCTOU the proposal claims is "impossible" (§14 C-6: "one deterministic `Realm.execute`
step on one thread") — but the step is not atomic against its own re-entry. Single-writer
prevents *cross-thread* races; it does nothing about *same-thread re-entrancy*, and firing
Bukkit events mid-transaction is an open invitation to it.

**Related deadlock:** the mirror image. `RealmCommands` returns `CompletableFuture<Result>`
(§10.1). A third party (or a naive internal caller) that does `future.join()` **on the global
thread** when the command is *not* inlined (submitted from an event handler that the pipeline
chooses to defer) blocks the only thread that can complete it → hard deadlock of the server
tick. The API surface offers a synchronous-looking future with no documented "never join on
the main thread" contract and no timeout.

**Fix:** `Realm.execute` must be **non-reentrant by construction**. Detect "already executing
a command on this thread" and *enqueue* nested submissions to run after the current command
drains (a trampoline), never inline them. Do not fire cancellable events from inside the
transaction at all — resolve cancellation in a *pre-commit phase* that holds no
half-constructed mutation set, or make `decide` re-run/verify after the event returns. Give
the public future an explicit no-main-thread-join contract and a timeout.

---

## FATAL-2 — "One copy of the truth" is false: RealmState and the read plane are two representations kept in sync by hand
**Sections:** §4.1 (D1 owns `RealmState`; D2 "written by D1"), §4.3 steps 3–4
(`state.apply` then `readPlane.apply`), §4.4, §5b/§5c, §15 claim 4 ("no read-modify-write…
one copy of the truth"), §14 C-1.

The proposal's headline invariant is "there is exactly one copy of the truth and one mutator"
(§4.3) and "the DB is a projection of memory, not a peer" (§4.3, §15). But §4.1 lists **two**
in-memory authorities both written by D1: the authoritative `RealmState` (`Faction`
aggregates, claims, banks, …) *and* the denormalized read plane (`ClaimTable`,
`RelationTable`, `PlayerAffinity`, `FactionDirectory`). Step 3 mutates `RealmState`; step 4
separately mutates the read plane. **Decisions read `RealmState` (`Kernel.decide(cmd, state,
…)`, §4.3), protection reads the read plane (§5b/§5c).** These are different objects that must
be mutated in lockstep by two hand-written apply methods.

**Failure scenario:** A new `Mutation` variant (say a future `ClaimTransfer`) is handled in
`state.apply` but a branch is missed in `readPlane.apply` (or the two disagree on ordering of
a compound change). `RealmState` now says chunk (w,x,z) belongs to faction B; `ClaimTable`
still says A (or wilderness). A subsequent claim command `decide`s against `RealmState`
(consistent), but the `PvpListener`/`BuildListener` enforce against `ClaimTable` (stale) →
players can grief a chunk the authority believes is protected, or are denied on a chunk the
authority believes is free. This is C-1's "divergence between two copies" **reintroduced
inside the writer** — the very class the design says it eliminated. The DB-vs-memory
divergence is indeed killed; the RealmState-vs-readPlane divergence is a new, silent peer.
There is **no compiler enforcement** that every mutation type is handled in both applies
(unlike the ArchUnit/descriptor gates that pin other invariants) — it is exactly the
"code-review promise" §15 claims the design never relies on.

**Fix:** Either (a) make the read plane the *only* representation the writer holds (derive
`decide`'s inputs from the same structures protection reads — no second `RealmState`), or
(b) generate both applies from a single sealed `Mutation` visitor with an exhaustiveness
check (sealed interface + `switch` the compiler forces to cover), and add a periodic/asserting
"reconcile" invariant test that hashes `RealmState`-derived claims against `ClaimTable`. Make
the two-representation coupling a *build gate*, not prose.

---

## FATAL-3 — Journal backpressure blocks the game thread; "overflow architecturally unreachable" is wrong
**Sections:** §4.2 ("no IO on the writer, ever"), §6.2 (SPSC ring, 65 536 slots, "backpressure
= writer-side flush-now signal; overflow is architecturally unreachable"), §5e, §12
(`ClaimChunks`/`UnclaimChunks` batched; `land.max-per-command`; unclaim-all), §14 C-9, §15
claim 4.

The claim "overflow is architecturally unreachable because mutations are bounded by command
throughput" ignores that **a single command emits an unbounded batch**. `/f claim square`
with a large radius, `/f unclaim all` on a faction with tens of thousands of claims
(§6.3/§3.6 boot report shows 48 113 claims for 214 factions — large factions exist), admin
shape claims, or a faction disband that tombstones every claim, all run in **one**
`Realm.execute` step with **no yielding** and append every resulting `Mutation` to the ring
synchronously. A 255×255 `claim square` alone is ~65 000 mutations — one command nearly fills
the entire 65 536-slot ring.

Now stall the `fable-storage` consumer for a moment (MySQL latency spike, H2 fsync, a slow
`ORDER BY … LIMIT` history query sharing the single H2 connection, GC pause). The ring fills.
"Backpressure = writer-side flush-now signal" means the **writer (the global/main game
thread) must wait** for the consumer to drain before it can append the rest of the batch.
That is *blocking IO-bound work on the game thread* — **C-BUG-9 reintroduced through the back
door**, the exact defect §14 claims is "unreachable by construction." The only alternatives
are dropping mutations (silent data loss) or growing the ring unboundedly (OOM). The design
picks none explicitly.

Compounding it: §6.2's **CRITICAL → immediate flush** policy defeats batching under load. On
a busy war server, constant bank/claim CRITICAL ops each force a flush of the pending batch →
the storage thread thrashes on tiny transactions → throughput collapses → ring fills faster →
game thread blocks sooner. The batching design and the immediacy design fight each other
under exactly the load that matters.

**Fix:** Bound *command* fan-out, not just the ring: chunk large claim/unclaim/disband
operations across ticks (yield the writer between sub-batches, re-checking invariants), so no
single command can outrun the drain. Define an explicit overflow policy that is neither
"block the tick" nor "drop": e.g., spill the journal tail to an on-disk WAL segment (the §16
"WAL admits a later consumer" hook, but needed for *backpressure*, not just crash-loss).
Replace "immediate flush per CRITICAL" with "flush on a short timer *or* N CRITICAL ops,"
and make durability-at-reply a first-class option (see MAJOR-1) rather than a synchronous
stall.

---

## MAJOR-1 — Cross-system money atomicity has a crash window; Vault/event/apply ordering is contradictory (C-BUG-12 / L-BUG-9 only partially killed)
**Sections:** §4.3 (pipeline steps 1–6 with effects *last*; separate "reserve → commit →
settle" paragraph), §6.2 (CRITICAL "immediate flush" is *asynchronous*), §10.1
(`FactionBankTransactionEvent` fired pre-commit, cancellable), §14 C-11/C-12, L-BUG-9.

Two problems, both money-real.

**(a) The stated ordering is self-contradictory.** The pipeline draws effects (including
"integration fan-out," i.e. Vault) as **step 6, after** `state.apply` (3) and `journal.append`
(5). But the prose says Vault is "debited only *after* the kernel decision confirms" (i.e.
after step 1) with "any storage/journal failure path issues the compensating Vault credit."
These cannot both be true. If Vault runs at step 6, then for a **deposit** the bank is already
credited in memory *and journaled* before the player's wallet is debited; a Vault failure at
step 6 leaves bank credited, wallet untouched → **money created** (C-BUG-4/12 resurrected). If
instead Vault "reserve" runs before step 2's cancellable event, then a **listener cancelling
`FactionBankTransactionEvent`** (the design explicitly makes it pre-commit and cancellable,
§10.1) leaves the wallet debited with no bank credit and **no refund is specified for the
event-cancel path** (only the "storage/journal failure path" is) → **money destroyed**. The
ordering of {decide, event, Vault reserve, apply, journal, settle/compensate} is the whole
ballgame for economy correctness and it is left ambiguous/contradictory.

**(b) The crash window is not closed.** CRITICAL flush is "immediate" but §6.2 makes it a
*signal to the `fable-storage` thread*, which drains **asynchronously**. So between
`state.apply` (bank credited in memory), the Vault wallet debit (durable in the economy
plugin's own store, which typically writes through), and the storage thread actually
committing our bank row, there is a real crash window. Server crashes there → Vault durably
debited the wallet, our bank credit lost → money vanishes on crash. The design's own claim
"crash-loss window ≈ 0 for CRITICAL" is "≈ 0," not 0, and for a *cross-system* mutation any
nonzero window is money creation/destruction because the two systems have different durability
timing. C-BUG-12 is *mitigated* (compensation on the failure path) but not *impossible*.

**Fix:** Specify one exact order — decide → fire cancellable event → (if not cancelled)
Vault reserve/debit → `state.apply` → **synchronous** journal commit for money ops (block the
*command*, not the tick, on a durability ack for CRITICAL money mutations, or write the money
mutation to a synchronous WAL before acking) → settle. Define the compensating credit for
**both** the storage-failure path *and* the event-cancel-after-reserve path. Consider making
money mutations debit Vault last, after our own durable commit, and reconciling on boot.

---

## MAJOR-2 — Backward-shift deletion in a lock-free open-addressed table can return false "wilderness" to concurrent Folia readers → protection bypass
**Sections:** §4.4 `ClaimTable`/`RelationTable` ("linear probing, load factor 0.5, no
tombstone rot (deletes use backward-shift)"; "readers use `getAcquire`"), §4.5 (protection
reads inline on region threads), §13.4 (jcstress), §14 C-9, L-BUG-6.

Backward-shift deletion is correct and elegant for a **single-threaded** open-addressed map,
which is why it's the standard choice — but it is a known hazard for **lock-free concurrent
readers**, which is exactly the Folia case (region threads read `ClaimTable` while the global
thread deletes on unclaim). Backward-shift *moves* an existing entry from a later slot back
into the hole to preserve the probe invariant. A reader searching for key K whose cluster
spans the hole can be **passed over**: if the reader has already examined the slot into which
the writer subsequently shifts K's neighbor, and then reaches a now-empty slot, it concludes
"not found" for a key that is still present — it was relocated behind the reader's cursor.
Release/acquire on *individual element stores* does not fix this; the hazard is the *movement
of a live entry backward past a concurrent probe*, not a torn single-slot read.

**Failure scenario (Folia):** Faction A unclaims chunk C1 (a delete that backward-shifts C2,
still owned by A, into C1's slot). Simultaneously a region thread evaluates a block-break on
C2. Its `ClaimTable.owner(C2)` probe misses the relocated entry → returns 0 (wilderness) →
`AccessEngine.decide(BUILD, …, ownerFaction=0)` → ALLOW → **a still-claimed chunk is griefable
for the duration of the race.** Same structure applies to `RelationTable` (a relation delete
mid-probe can momentarily read NEUTRAL/ENEMY wrong).

This is not caught by the test plan: §1's Folia lane runs `suites: claims-smoke` only, and
§13.4's jcstress pins the table in isolation, not the delete-vs-probe interleaving under the
real backward-shift path. The one environment where lock-free reads actually run (Folia) is
the one that is only smoke-tested (see MAJOR-6).

**Fix:** For concurrent readers you need either tombstones + writer-side periodic rehash
(accept the "rot" the design rejected — it is the price of lock-free reads), or a
copy-on-delete table swap (as the design already does for *resize*), or confine reads to the
writer thread (impossible on Folia). Pick one and jcstress the **delete-vs-lookup**
interleaving specifically, not just publish/resize.

---

## MAJOR-3 — `FactionDirectory` array growth vs `PlayerAffinity.factionId` has a publication-ordering hole → AIOOBE / torn read on Folia
**Sections:** §4.4 (`FactionDirectory` "int-indexed parallel arrays," `AtomicIntegerArray
flagBits`, ids "never reused"), §4.4 `PlayerAffinity` (`volatile int factionId`), §5b/§5c.

Faction ids are dense ints assigned by the writer and never reused, so the
`AtomicIntegerArray`/`AtomicReferenceArray` backing `FactionDirectory` must **grow** as new
factions are created (allocate larger array, copy, swap the reference). There is no specified
happens-before between "publish the grown directory array" and "publish the new player's
`factionId`." A protection read does: `int fid = affinity.get(id).factionId; int bits =
directory.flagBits(fid)` (§5c passes `dir.flagBits(owner)`).

**Failure scenario (Folia):** Writer creates faction id N (needs directory length > N):
allocates new arrays, populates slot N, swaps the array ref, and sets the founding player's
`affinity.factionId = N`. A region thread reads the **new** `factionId = N` (volatile, freshly
published) but, lacking an ordering edge, still sees the **old** array reference (length ≤ N).
`directory.flagBits(N)` → `ArrayIndexOutOfBoundsException` thrown out of the protection
listener (uncaught — see MAJOR-4) → event proceeds uncancelled → protection bypass, plus log
spam. Even without OOB, a reader seeing new `factionId` against a not-yet-populated slot reads
default flag bits → wrong zone/pvp decision.

**Fix:** Order the publication: the directory array grow must *release* before the affinity
`factionId` that references it is published, and readers must `getAcquire` the array ref
*after* reading `factionId`. Better: size the directory generously and grow under a
copy-swap with the new element visible before any `factionId` can point at it, or use a
growable concurrent structure whose bounds check can't race the id. Add this exact
cross-structure interleaving to jcstress.

---

## MAJOR-4 — Protection reads dereference `PlayerAffinity` without null-safety; join-race and offline-owner lookups NPE → uncaught → protection bypass
**Sections:** §5b (`affinity.get(attacker.getUniqueId())` then `a.factionId` with no null
check), §5c, §7.1 ("fail-safe deny on internal error"), §4.4 (affinity population is
writer-owned), L-BUG-5, L-BUG-19.

§5b reads `PlayerAffinity a = affinity.get(attacker.getUniqueId()); int fa = a.factionId;`
and the true-attacker chain resolves projectile shooter / TNT source / AEC owner / **tamed-pet
owner**. Three ways `affinity.get(...)` returns null:

1. **Join race (Folia):** `PlayerJoinEvent` fires on the region thread; the affinity-populate
   `RealmCommand` hops to the global thread and executes *later*. Between join and populate,
   the player's entity exists and can be damaged (or damage others) → `affinity.get(id) ==
   null` → NPE.
2. **Offline participant:** a tamed wolf whose owner is offline, or a victim who just logged
   off, or an AEC owner who left — if affinity is keyed only for online players (§4.4 doesn't
   say otherwise), the lookup is null.
3. **Cross-plugin summoned entities** with a UUID never in the map.

The design says "fail-safe deny on internal error" (§7.1), but an **NPE/AIOOBE thrown from
the listener body is not a `Verdict` — it propagates out of the event handler**, Bukkit logs
it, and the event proceeds **uncancelled** → the opposite of fail-safe. The MONITOR/consolidated
listener shape in §5a/§5c has no try/catch that converts a thrown exception into DENY. This
directly reopens L-BUG-5 (ranged/relayed damage bypass) via the exception path and mirrors
L-BUG-19 (unguarded deref).

**Fix:** Every read-plane lookup on the hot path must return a defined default (factionless =
0) for absent/offline UUIDs — `affinity.getOrDefault(id, FACTIONLESS)` — never a raw null
deref. Populate affinity **before** the entity is interactable (synchronously in the join
handler on the owning thread, or gate damage until populated). Wrap each protection listener
body so any thrown exception maps to fail-safe DENY, and assert that in a test (throw-injection).

---

## MAJOR-5 — Memory-authoritative model is single-instance only; two servers on one MySQL silently clobber each other
**Sections:** §6.1 ("Memory is authoritative… After boot, the Realm never reads the DB"),
§6.2 ("coalescing… last-value-wins… legal because values are absolute states… never SQL-side
deltas"), §6.3 (MySQL pool from config), §16 (risks — does not mention this).

The entire correctness argument (§6.2: last-value-wins coalescing is "legal because… there
is no second writer to merge with") depends on **exactly one JVM owning the DB**. The
reference plugin, for all its bugs, read rows per operation and could (badly) tolerate a
shared DB. Proposal B cannot: if an admin points **two** server instances at one MySQL
(a proxy network, a test/prod mixup, a failover pair), each loads the DB into memory at boot,
each is a single-writer of its *own* memory, and each blind-`MERGE`/`ON DUPLICATE KEY`s
absolute values back — **neither ever reads the other's writes after boot**. The two
projections silently clobber each other; claims, banks, and power diverge and corrupt with no
error. This is a **regression in a capability the reference nominally had**, and it is an
unstated operational constraint that will cause a catastrophic data-loss incident the first
time someone misconfigures a network.

**Fix:** Acquire an exclusive advisory lock at boot (`SELECT GET_LOCK` on MySQL, a lock row /
`DB_CLOSE_DELAY` ownership marker on H2) and **refuse to boot** (or boot read-only) if another
live instance holds it. Document the single-instance constraint explicitly in §16 and the
build README. Heartbeat the lock and fence on loss.

---

## MAJOR-6 — The Folia lane runs only `claims-smoke`; the lock-free read plane is never exercised by the full suite
**Sections:** §1 support-matrix (`{"version":"26.1.2","platform":"folia","suites":
"claims-smoke"}`), §13.4 (jcstress on structures in isolation), §13.8 (integration matrix),
§4.5, §15 claim 5.

On **Paper**, the writer and all protection listeners run on the main thread, so the read
plane is read and written by a single thread — the VarHandle acquire/release machinery, the
resize race, backward-shift-vs-probe, and cross-structure publication ordering are **never
concurrent**. The *only* environment where any of the lock-free correctness matters is
**Folia** (region threads read while the global thread writes) plus the D4 async readers. Yet
§1 runs the full protection/PvP/GUI/chat/power suites only on Paper lanes and restricts Folia
to `claims-smoke`. **The concurrency surface the architecture is built around is the one the
matrix barely tests.** jcstress (§13.4) pins individual structures under synthetic
publish/resize, but not the composite hot read (affinity→factionId→directory→relations, the
delete-vs-probe interleaving, the array-grow-vs-id race — MAJOR-2/3/4). The result: every
Folia-specific hazard above ships untested.

**Fix:** Run the *full* protection/PvP/power/reload suites on the Folia lane, add jcstress
harnesses for the *composite* read path and for delete-vs-lookup / grow-vs-id, and add a
Folia soak test that hammers claims/unclaims/PvP concurrently across regions while asserting
protection verdicts and invariant hashes.

---

## MAJOR-7 — World identity keys orphan claims on world unload/reload → protection lost for reloaded worlds
**Sections:** §5a (`int worldId = worlds.id(to.getWorld()); // identity CHM`), §4.4
(`ClaimTable` per-world), §6.3 (`board PRIMARY KEY (world, x, z)` VARCHAR world), §12.

The hot path resolves world → int via an **identity** CHM (`worlds.id(to.getWorld())`), but
claims persist keyed by **world name** (`board.world VARCHAR(64)`). These desync the moment a
world is unloaded and reloaded (Multiverse, dynamic world managers, `/mv unload`+`/mv load`),
because the reloaded `World` is a **new object** with the same name. In-memory `ClaimTable`
was populated at boot against the *old* `World` identity; after reload, `worlds.id(newWorld)`
mints a **fresh** int id → the reloaded world's claims are unreachable → every chunk there
reads as wilderness → **protection silently gone for the entire world** until restart, and
auto-claim there writes duplicate `board` rows under a new id all mapping to the same world
name.

**Fix:** Key world identity on the stable `World#getUID()` (or the name), never on object
identity, and handle `WorldLoadEvent`/`WorldUnloadEvent` to rebind the id → `World` mapping.
Add a world-reload integration test.

---

## MAJOR-8 — Shutdown `drainAndFlush(timeout=10s)` is a data-loss cliff for CRITICAL mutations
**Sections:** §6.2 shutdown barrier step 4 (`journal.drainAndFlush(timeout=10s)` then step 5
close pool), §14 C-13 ("assert zero lost CRITICAL mutations"), §16 (bounded crash-loss).

A *clean* shutdown with a slow or momentarily-unreachable DB (MySQL failover, disk stall)
hits the 10 s timeout on `drainAndFlush`, then step 5 closes the pool, **discarding every
mutation still in the ring** — which can include CRITICAL bank/claim/membership ops that were
"immediately flushed" (signalled) but not yet committed. The design's own guarantee ("zero
lost CRITICAL," §14 C-13; "≈ 0 for CRITICAL," §6.2) fails on the exact scenario shutdown
barriers exist for. A fixed timeout turns a slow DB into silent money/claim loss on every
restart during an outage.

**Fix:** On shutdown, do not *drop* on timeout — spill the undrained journal tail to a local
durable file (`pending-mutations.wal`) and replay it on next boot before opening listeners.
The timeout should bound *waiting on the network*, not *whether data survives*. §16 already
contemplates a WAL consumer; shutdown is where it is mandatory, not optional.

---

## MEDIUM-1 — Config reload mutates HandlerLists and rebuilds read-plane bits concurrently with Folia region-thread event dispatch
**Sections:** §8 (atomic swap: `ReloadCommand` swaps `volatile ConfigSnapshot`, "rebuilds
derived read-plane values (pre-rendered tags, zone bits), converges scoped features"), §14
C-3 (Scope reconciler register/unregister), §4.5.

The reload runs on the global thread. Two hazards on Folia (both benign on Paper, where reload
and events share the main thread):

1. **HandlerList mutation vs dispatch.** The Scope reconciler calls
   `HandlerList.unregister`/register while region threads are concurrently baking/iterating
   handler arrays for in-flight events. Bukkit's handler baking is not contractually safe to
   mutate from another thread mid-dispatch → missed or double-delivered events, or CME during
   the reload window.
2. **Torn zone/flag rebuild.** "Rebuilds derived read-plane values (zone bits)" mutates
   `FactionDirectory` arrays in place while region threads read them per protection event → a
   verdict computed against *new* config `zoneBits` but *old* `flagBits` (or vice versa). §8
   claims "Every command/teleport freezes the values it needs… a mid-operation reload can
   never tear one operation," but **protection listeners are not commands** — they read the
   read plane live and are not covered by that freeze.

**Fix:** On Folia, quiesce (or snapshot-swap) before mutating handler registrations and
directory bits; build a *new* immutable derived-read-plane and publish it with a single
reference swap (as done for `ConfigSnapshot`), rather than in-place array edits protection
readers can observe half-done.

---

## MEDIUM-2 — Reload "failures suppressed-collected" leaves periodic tasks / features silently dead
**Sections:** §14 C-3 ("reconciler converges scopes… failures suppressed-collected"), §8,
§5e (PowerTick/TaxTick on `repeatGlobal`).

If a reload changes a periodic interval (power tick, tax), the reconciler cancels the old
`repeatGlobal` task and opens a new scope for the new one. "Failures suppressed-collected"
means an exception while *opening* the new scope is swallowed into a collected log line — but
the old task is already cancelled. Net: the power tick (or tax, or the raidable sweep) is
**silently not running** after a reload, and the server keeps ticking with a degraded feature
until someone notices power never regenerates. Suppressing failures during lifecycle
reconciliation is the wrong default for tasks whose absence is invisible.

**Fix:** Reconcile with all-or-nothing semantics: if any scope fails to open, roll back to the
pre-reload scope set (don't leave a half-converged state), and surface the failure as a loud
admin-visible error, not a collected log entry. Add a post-reload assertion that the expected
task/listener census matches (the reload-torture test §13.7 counts handlers — extend it to
count *tasks* and assert *presence*, not just "unchanged").

---

## MEDIUM-3 — Unbounded growth of directory / relation / id-interning structures under faction churn
**Sections:** §4.4 (`FactionDirectory` int-indexed arrays; ids "never reused within a run";
`RelationTable` effective pairs), §16 (RAM risks — mentions claims, not this).

"Ids are never reused within a run" is presented purely as a benefit (no ABA). Its cost is
unacknowledged: a long-running server with faction create/disband churn consumes a **new
directory slot forever** for every faction ever created. `AtomicReferenceArray<FactionProfile>`
and the parallel int arrays grow monotonically with *lifetime* faction count, not *live*
count; `RelationTable` accumulates entries for disbanded factions that never match again but
are never purged. Over weeks this is a slow leak that forces a restart — ironic for a design
whose §16 carefully budgets claim RAM.

**Fix:** Either compact/recycle ids at safe points (with a generation tag to preserve ABA
safety) or document a bound and add a periodic purge of dead-faction `RelationTable` entries
and a directory-compaction pass. At minimum, acknowledge the growth in §16 with a sizing
number like the claim budget.

---

## MEDIUM-4 — Migration/import is not shown to be crash-atomic; `world:x:z` re-parse is ambiguous
**Sections:** §6.3 (`MigrationV0Import`… "idempotent (guarded by `schema_version`)"; board
string PK → columns; relations_json → rows), §6.4.

Two migration risks:

1. **Crash atomicity.** If `schema_version` is bumped only after a multi-table import and the
   import is interrupted (crash, OOM, kill), the next boot re-runs the whole import over
   partially-imported data. "Idempotent" holds only if *every* insert is an upsert on a
   stable key — but claims/history/transactions imported as plain inserts would duplicate. The
   design says "idempotent (guarded by `schema_version`)" but the guard is coarse (one flag at
   the end), not per-step.
2. **Key re-parse.** Splitting the reference's `world:x:z` string PK on `:` is ambiguous if a
   world name contains `:` (uncommon but legal in some setups) → wrong world/coords → misplaced
   or dropped claims on import.

**Fix:** Wrap the import in a single transaction per table (or a resumable, per-row-idempotent
upsert keyed on the natural key), and split the legacy key from the **right** (`z`, then `x`,
remainder = world) or store the legacy world name length. Add an import round-trip test with a
seeded reference DB including an adversarial world name.

---

## MEDIUM-5 — The Realm is a god-object with a 6-site coordinated-edit tax and no compiler-enforced lockstep
**Sections:** §2 (`:core` owns "boot ordering, Realm wiring, persistence, listeners,
commands, GUI, integrations, config, i18n"), §4.1 (D1 owns *all* domain state), §4.3, §6.2,
§15 claim 6.

Adding any stateful feature requires coordinated edits across at least six sites: kernel
`decide`, the `RealmCommand` type, `state.apply`, `readPlane.apply` (FATAL-2), the `Mutation`
type + StorageActor projection, and `effects.dispatch`. The single-pipeline design that buys
the correctness invariants also concentrates all domain state in one writer/one dispatch and
imposes high change-coupling. Only some of the six sites are guarded by exhaustiveness (the
gates target seam/descriptor concerns, not mutation completeness). This is a maintainability
liability: the architecture's safety rests on developers remembering to touch all six, which
is exactly the "code-review promise" §15 disclaims.

**Fix:** Drive all six from one **sealed `Mutation`/`RealmCommand`** hierarchy with compiler-
enforced exhaustive handling at each consumer (sealed + exhaustive `switch`), so a new variant
*fails to compile* until every consumer (state, read plane, projection, effects) handles it.
That converts the coordination tax into a compile-time invariant, matching the rigor of the
seam gates.

---

## MEDIUM-6 — Domain events fired on the Folia global thread reach third-party handlers on the wrong region
**Sections:** §4.2 ("global thread is legal… for plugin-defined events"), §10.1 (all API
events fired on the Realm thread), §4.5.

Firing `FactionChunkClaimEvent`/`FactionDisbandEvent`/etc. on the global region thread is
legal *as dispatch*, but third-party handlers that touch the claimed chunk, the player entity,
or nearby blocks in their handler are then operating on the **wrong region** on Folia. The
design controls its *own* listeners but cannot control integration/third-party handlers, and
the API contract does not warn them. (Also: your own `DynmapLayer`/`WorldGuardRegionSync`
MONITOR handlers must be audited to touch only region-safe state.)

**Fix:** Document the event-thread contract in `:api` (handlers run on the global region
thread on Folia; do entity/world work by re-scheduling to the owning region). Audit first-
party MONITOR handlers for region-safety.

---

## LOW-1 — `hexChat` is the self-admitted "parse, not probe" exception; fingerprint robustness across 1.7.10→26.1.2 forks
**Sections:** §3.1 law 1 ("Probe, never parse"), §3.2 `hexChat` ("the one place a version
number is consulted"), §1.

The design's first law is violated in exactly one place by its own admission. If
`ServerFingerprint.parse()` mis-parses an odd fork version string (custom forks, "26.1.2",
snapshot builds) the hex-downsampling mode is chosen wrong → garbled colors on some servers.
Low impact (rendering only) but it is a crack in the flagship invariant; the fingerprint parser
must be proven robust across the whole declared range including forks.

**Fix:** Make hex-mode a *behavioral probe* too (detect client/serializer hex support via a
capability rather than a version compare), or fuzz the fingerprint parser across real fork
version strings and default safely on parse failure.

---

## LOW-2 — Confirmation messages/effects dispatched before durability (user-visible on crash)
**Sections:** §4.3 (step 6 effects after step 5 journal append; journal is async), §6.2.

Effects (step 6) — including "You deposited $10 000" / "Chunk claimed" — are sent after the
in-memory apply but the journal flush is asynchronous. A crash between apply and flush leaves
the player *told* an operation succeeded that did not persist. For bank ops this compounds
MAJOR-1 (wallet durably debited, message says success, bank credit lost). User-visible
"it said it worked" anomaly.

**Fix:** For CRITICAL money/claim confirmations, dispatch the success message from the
durability ack (after commit), not from the in-memory apply.

---

## What the design gets right (graft-worthy even if B loses)

- **Single-writer realm** genuinely eliminates the *cross-thread* RMW/lost-update core of the
  concurrency catalog (C-1/2/4/8/16, C-6/17/18/21 check-then-act) — provided FATAL-1
  (re-entrancy) and FATAL-2 (dual representation) are fixed. This is the right spine.
- **`java.sql` confined to `persist` by ArchUnit + listeners read-plane-only** makes
  C-BUG-9's main-thread-JDBC *structurally* unreachable for the ordinary path (the
  backpressure back-door FATAL-3 is the only leak) — a real improvement over "remember not to
  query in listeners."
- **Descriptor-floor / sticky-getstatic build gates** (§3.5) are a legitimately strong
  invention: they restore compiler-grade safety below the compile floor and turn the
  "silently dead listener" class into a build failure.
- **One `AccessEngine.decide` + event-complete vector matrix** (§5c, §7.1) is the correct
  answer to L-BUG-5/6; the pre-rendered placeholder-node text pipeline (§9) makes L-BUG-7
  injection structurally dead.
- **Reserve → settle intent** for external money (§4.3) and **deliver-then-delete-by-id-set**
  inbox (§14 C-10), **one canonical tunable + alias fold** (§8), and **effective-only
  RelationTable** (kills L-BUG-21 one-sided ALLY) are all correct fixes to the catalog — the
  problems are in *ordering/atomicity specification*, not intent.
- **Scoped lifecycle + reload-torture handler-count test** (§14 C-3, §13.7) is the right
  shape for C-BUG-3/14/15 (needs MEDIUM-2's presence-assertion + rollback to be airtight).

---

## Bug-catalog verification summary (does the design make each *impossible*, or just assert it?)

| Catalog bug | Claim | Reviewer verdict |
|---|---|---|
| C-1/2/4/8/16 (RMW/lost update) | "no RMW anywhere; one copy of truth" | **Cross-thread: killed. But** dual representation (FATAL-2) reintroduces divergence; re-entrancy (FATAL-1) reintroduces stale-apply. |
| C-5/6/17/18/21, L-11 (check-then-act) | "one deterministic step, no locks to misuse" | Killed for cross-thread; **re-entrancy (FATAL-1) makes the 'one step' non-atomic against itself.** |
| C-7/L-8 (chest dupe) | single shared inventory + refcount | Plausibly killed (Folia single-viewer narrowing acknowledged). Not deeply reviewed here. |
| C-3/15 (reload leak) | scoped lifecycle | Mostly killed; **MEDIUM-2 (suppressed failures) + MEDIUM-1 (Folia HandlerList race)** leave holes. |
| C-9 (main-thread JDBC) | ArchUnit + read-plane-only | Killed for ordinary path; **FATAL-3 backpressure re-opens a main-thread stall.** |
| C-10 (inbox) | deliver-then-delete-by-id | Killed. |
| C-11/12, L-9/14 (charge-then-lose) | reserve→settle + compensation | **Partial: MAJOR-1 ordering contradiction + async-flush crash window.** |
| C-13 (drain before close) | ordered shutdown barrier | **MAJOR-8: 10s timeout drops CRITICAL data on slow DB.** |
| C-14 (fly leak/wipe) | persisted field + PlayerLocal evict | Killed (given join/quit scope correctness). |
| C-19 (async Bukkit API) | ports route to correct thread | Killed for messaging; **MAJOR-4 join-race + Folia entity resolution (MEDIUM-6) under-addressed.** |
| C-20 (Folia delay 0 / region) | clamp ≥1 tick; no global fallback | Killed. |
| C-22 (transfer post-commit) | pre-commit cancellable events | Killed (given MAJOR-1 ordering fixed so cancel refunds). |
| C-23 (dead last_activity) | written join/quit/heartbeat + test | Killed. |
| L-1/2/18/22 (power/config) | one pipeline, source-gated clamp, canonical key | Killed. |
| L-5/6 (protection completeness) | true-attacker + event-complete | Killed **except** the NPE-bypass path (MAJOR-4). |
| L-7 (injection) | placeholder nodes + allowlist | Killed. |
| L-10/11/12/13/15/16 (logic) | uniform ClaimRules / first-party warmup / gated bank | Killed by design intent (not concurrency-reviewed in depth here). |
| L-17 (lock striping) | no locks | Killed (superseded by single writer). |
| L-19/20/21 | guarded / UUID cache / effective relations | Killed. |

**Bottom line:** the core is sound and kills most of the catalog, but four issues (FATAL-1
re-entrancy, FATAL-2 dual representation, FATAL-3 backpressure, and the MAJOR-2/3/4 Folia
read-plane family — untested per MAJOR-6) each reopen a bug class the proposal claims is
impossible. None are unfixable; all require the design to specify ordering/atomicity/
publication that it currently only asserts. Sound-with-fixes.
