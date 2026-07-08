# Critique of Proposal B (Frozen-Seam) — Performance / JVM / Server-Internals

**Reviewer stance:** hostile performance review. Scope: hot-path allocation, lock
contention / false sharing, cache layout, main-thread IO, O(n) disguised as O(1),
snapshot-swap storms, GC at 1000 players / 1M claims, megamorphic seams, scheduler
misuse, chat/scoreboard cost, boot-probe cost, single-writer math, and verification of
every numeric claim.

The design is architecturally strong on *correctness* (the single-writer / no-RMW story
is genuinely good). But the document repeatedly sells **correctness machinery as
performance machinery**, and the numbers that back the perf claims are cherry-picked or
wrong. The two worst problems are a **lock-free-reader data race introduced by a
cache-optimization** and an **O(all-registered-players) sweep pinned to the main thread**.
Both detonate under exactly the load a factions plugin exists to survive: overclaim wars
and large populations.

---

## FATAL-1 — §4.4 `ClaimTable`: backward-shift deletion is not safe for the wait-free readers, and it produces *wrong ownership* under unclaim/overclaim churn

> "power-of-two capacity, linear probing, load factor 0.5, **no tombstone rot (deletes use
> backward-shift)**. Writer mutates in place with `VarHandle.setRelease` element stores;
> readers use `getAcquire`… Semantics are a single-writer restriction of the well-understood
> non-blocking hash map family."

This is the load-bearing hot structure, and the claim is false. **Backward-shift deletion
and lock-free linear-probing readers are mutually exclusive.** Every production non-blocking
open-addressed map (Cliff Click's, `boost::concurrent`, etc.) uses **tombstones precisely
because backward-shift breaks concurrent readers.** Release/acquire on *individual element
stores* does not give you atomicity across the multi-slot move that a shift performs.

Concrete failure: keys A and B collide; A lands at slot `i`, B probes to `i+1`. Writer
unclaims A and backward-shifts B from `i+1` into `i` (write B→`i`, then clear `i+1`). A
reader looking up B, interleaved:

1. reader reads slot `i` **before** B is written there → sees A (or in-progress empty), keeps probing;
2. writer writes B→`i`, clears `i+1`;
3. reader reads slot `i+1` → empty → **stops probing → concludes B is unclaimed (wilderness, owner 0).**

No per-element `setRelease`/`getAcquire` prevents this — there is no single release edge
covering both slots. The reader misses a *still-claimed* chunk and returns owner 0.

**Load scenario that breaks it:** an overclaim war or `/f unclaim all` deletes hundreds of
entries per second while region/main threads run protection checks against the same
per-world table. The transient "wilderness" reads mean **build/interact/PvP protection is
silently bypassed on genuinely-claimed land** for the duration of the churn — the precise
grief window the whole protection engine exists to close, reintroduced by a memory
micro-optimization. jcstress will not "pin" this; at best it will *find* it (and only if the
harness models the shift, which single-writer restrictions often skip).

**Fix (pick one):**
- Tombstones + amortized writer-side compaction (the standard answer; costs the "no
  tombstone rot" bragging right, buys correctness).
- Per-world **immutable snapshot swap** on structural change: readers hold a stable array
  pair; the writer publishes a new pair via one release store. Deletes/inserts rebuild or
  copy-on-write the affected region. Reads stay wait-free and *always consistent*. Costs
  churn allocation, but claims mutate far less often than they are read.
- A persistent HAMT / CTrie keyed on the packed long — wait-free reads, structural sharing,
  no shift hazard.

Until this is resolved, the "wait-free O(1) hot path" novelty claim (§15.5) is unproven and
the jcstress suite is testing a structure that is incorrect by construction.

---

## FATAL-2 — §5e / §6.2: the power tick is O(all registered players) on the single global writer (= the main thread on Paper), and it overflows the journal ring

> "5 000 players ≈ 5 000 arithmetic ops + one journal batch: **sub-millisecond on the global
> thread**." … "`MutationJournal`, 65 536 slots … **overflow is architecturally unreachable
> because mutations are bounded by command throughput.**"

Three compounding errors.

**(a) The population is wrong.** Power regen is applied to online *and* offline players
(`REGEN_ONLINE` / `REGEN_OFFLINE` — pvp-engines §3.7.1/§3.11.2; the reference does
`players().findAll()`). The tick therefore iterates the **entire registered-player table**,
not the concurrent 5 000. A mature factions server has hundreds of thousands to millions of
registered `PlayerRecord`s resident (you loaded them all at boot for offline regen,
inactivity exclusion, and `findByFactionId`). The "5 000 ops" figure is off by the
offline-population factor — typically **40–200×**.

**(b) It is on the writer, which on Paper is the main thread.** §4.2 pins the writer to
`runGlobal` = the main server thread on Paper. So this unconditional O(all-players) scan runs
*inside the 50 ms tick budget*, serialized against every claim, bank op, and PvP-triggered
power change. Measured cost of the bare iteration alone (my calc): **~2 ms at 1M records,
~5 ms at 5 ns/record** — a periodic main-thread spike every interval, before any mutation
work. The reference ran this async (buggily); this design *promotes* it onto the main thread
and calls that a feature.

**(c) The journal ring overflows and stalls the writer.** With offline regen producing a
nonzero delta, the tick emits one `PowerSet` mutation per below-max player. The SPSC ring is
65 536 slots. My calc: **200k players → overflow by 134k; 1M players → overflow by 934k.**
"Bounded by command throughput" is false — *one* `PowerTick` command emits N mutations. When
N > 65 536 the writer must block on the storage drain (fsync-bound H2, pool size 1) →
**multi-hundred-ms to multi-second global-thread stall every power interval**, with every
claim/PvP/bank command queued behind it. "Sub-millisecond" and "overflow unreachable" are
both refuted by the design's own constants.

**Fix:**
- **Lazy offline regen:** store `powerAsOf(timestamp)` and compute
  `min(max, stored + rate·elapsed)` on read/login. The offline sweep disappears; the tick
  touches only online players (genuinely ~thousands of ops).
- If a sweep is kept, run it on a **background thread over an immutable snapshot** and feed
  results back as **backpressured, chunked** command batches (≤ ring/2 each), never as one
  monolithic command on the writer.
- The raidable sweep (below) has the same shape and needs the same treatment.

---

## MAJOR-3 — §5e step 3 / §3.11.3: raidable sweep recomputes Σ member power per faction on the writer, every tick

`nowRaidable = currentLand > maxLand`, and `maxLand = f(getFactionPower)` where
`getFactionPower = powerBoost + Σ member power (with inactivity exclusion)`. Done "in the
same pass" as regen means, per interval, an **O(total members across all factions)** pass on
the writer — *recomputing the exact aggregate the regen pass just changed*. At 10k factions ×
~20 members that is another ~200k member-power sums on the main thread every interval,
stacked on FATAL-2.

The proposal describes no **incremental faction-power aggregate**; `getFactionPower` in the
spec is a live Σ. Without a maintained per-faction total (updated as each power delta lands),
raidable evaluation cannot be O(factions).

**Fix:** maintain an incremental `factionPowerTotal[factionId]` and
`factionLand[factionId]` in `FactionDirectory`, updated on each power/claim mutation; the
raidable check becomes O(factions) reads with no per-member scan.

---

## MAJOR-4 — §4.2 / §4.3: "no IO on the writer, ever" is violated by synchronous Vault settlement

> "there is **no IO on the writer, ever** (persistence is an async projection)."

The reserve→commit→**settle** pipeline (§4.3) debits Vault "in the effects dispatch stage"
— step 6, on the global/main writer thread. **Vault economy backends routinely do
synchronous SQL** (`vault.withdraw`/`deposit` hitting the economy plugin's database). So
`/f deposit`, `/f withdraw`, `/f power buy`, warp fees, and claim costs each perform
**main-thread blocking IO through the integration seam** — a slow economy plugin stalls the
writer and therefore the whole server tick. The design is scrupulous about its *own* SQL
(StorageActor) and then reintroduces main-thread IO via Vault. The invariant as written is
false.

**Fix:** perform Vault I/O on an async worker; model settlement as a two-phase command
(reserve on writer → async Vault call → settle/compensate command back on writer). The
atomicity story gets harder, but "no IO on the writer" cannot coexist with synchronous Vault
calls on the writer.

---

## MAJOR-5 — §4.4 / §15.5: the "24 B/claim, 5 M ≈ 120 MB" memory claim is cherry-picked *and* internally inconsistent with the stated layout

Two problems.

**(a) The 5 M figure contradicts the design's own "power-of-two capacity."** At LF 0.5, 5 M
entries need capacity ≥ 10 M, and the next power of two is **2²⁴ = 16 777 216** → 16.77 M ×
12 B = **201 MB, not 120 MB** (my calc). The 120 MB number silently assumes capacity =
exactly 2× entries, which **violates the stated power-of-two rule** — the very rounding they
specified inflates the array by ~68% at 5 M. (1 M rounds to 2²¹ = 25.2 MB ≈ their 24 MB, so
the error only shows at scale, which is where it matters.)

**(b) It counts only the cheapest structure.** The read+write plane also holds, at 1 M
players + 1 M claims (my rough calc):
- `PlayerAffinity` CHM: ~**100 MB** (CHM node + UUID key + affinity object per online... and
  the authoritative record for offline).
- authoritative `PlayerRecord[]` for **all registered players** (resident for offline regen):
  ~**180 MB** at 1 M records incl. name strings.
- `RelationTable`, `FactionDirectory` arrays, and `FactionProfile` member lists (which
  duplicate member references) on top.

Realistic read+write-plane heap at that scale is **~300 MB–1 GB+**, not "120 MB." Presenting
the 25 MB claim array as "millions of claims are a RAM line item" is the wrong denominator;
the dominant cost is holding every offline player resident — which is *also* the root of
FATAL-2. Fixing offline regen to be lazy removes both the heap and the tick cost.

**Fix:** state the honest heap budget; stop holding all offline `PlayerRecord`s resident
(lazy-load on login); and either drop the power-of-two constraint (accept modulo-index cost)
or budget for the real rounded array size.

---

## MAJOR-6 — §4.4: false sharing in `PlayerAffinity` between the hottest read and the hottest writes

`PlayerAffinity` = `volatile int factionId`, `volatile int flags`, `volatile long powerBits`
in one 32-byte object → **all three fields share a single 64-byte cache line.** On the
protection/relation hot path readers read `factionId` (§5b). The writer writes `powerBits`
on every power event and `flags` (which holds the **combat-tag bit**) on every PvP hit. In
active combat the writer stores to `flags`/`powerBits` continuously, invalidating the line
that every region/reader core needs for `factionId` — **cache-line ping-pong on the exact
object read by every protection decision for a fighting player.**

This is textbook false sharing: co-locating a hot-read immutable-ish field (`factionId`,
changes on join/leave) with two hot-write fields (`flags` combat-tag, `powerBits`) that
mutate on every hit.

**Fix:** split the hot-write mutable power/combat state into its own object (or its own
padded slot / `@Contended`), separate from the stable `factionId`. Protection reads then hit
a line the writer rarely touches.

---

## MODERATE-7 — §8 / §12 "fly": `fly.disable-on-threat` is an unbounded O(online players) scan on the move path

The design adds "continuous move-driven re-evaluation with enemy-proximity radius" and
"a real enemy-proximity revoke on the move listener," but describes **no spatial index.** A
naive enemy-within-N-blocks test iterates online players computing distance + relation. At
1000 players, a flying player near a border can trigger **up to ~1000 distance + relation
probes per evaluated move.** Even bounded to chunk-crossings that is a hidden O(n) on the one
listener that already fires 20k+ times/sec server-wide.

**Fix:** evaluate only on chunk-cross, and use a **bounded** neighborhood query
(`World#getNearbyPlayers(loc, r)` / a per-region player grid), not a full online scan — and
cache the result for a few ticks. Note `getNearbyEntities` itself allocates a list per call,
so throttle it.

---

## MODERATE-8 — §3.1 / §3.3: the "monomorphic seam devirtualizes to zero cost" thesis is partly true, partly moot, and partly wrong

Three points.

- **Mostly moot:** the *hottest* paths (§5a move, §5b relation, §5c explosion) don't call
  ports at all — they read concrete `ClaimTable` / `RelationTable` / `ConcurrentHashMap` /
  `AccessEngine.decide` (a static). So the elaborate one-impl-per-port monomorphism story is
  largely irrelevant to hot-path throughput; it is a correctness/maintainability property
  dressed as the "performance story of the seam" (§3.1 law 3). The genuine hot-path costs
  (FATAL-1, FATAL-2, MAJOR-6) are elsewhere and are underweighted.
- **Partly wrong:** several ports resolve to **instance-field** `MethodHandle` chains
  (`FeedbackPort` "three MethodHandle slots"; `TextPort`/`PlayersPort` boot handles). Only
  `static final` MethodHandles are constant-foldable and inline; **instance-field
  `MethodHandle.invoke` does not inline** and is one of the slower JVM dispatch paths. §3.5
  claims "call sites hold `static final MethodHandle` fields," but per-backend resolved
  handles are necessarily instance state. This is fine where cold (feedback/title), but the
  document should not claim these are free.
- **CHA caveat:** interface devirtualization via CHA is guarded and **deopts if a second
  implementor ever loads.** The tester jar, mocks in `:tester`, or any future second backend
  loaded in the same JVM will invalidate the assumption the whole thesis rests on.

**Fix:** demote the seam-monomorphism claim from "the performance story" to "a
non-regression"; keep hot handles `static final`; verify no port interface has a second
implementor class-loaded in production (the §15.1 self-check helps here).

---

## MODERATE-9 — §5c / §9: explosion `removeIf` lambda allocation + ThreadLocal memo, and unthrottled deny-message bridging

- `blockList().removeIf(this::isProtectedFromExplosion)` — `this::method` is a **capturing
  lambda allocated per call** = one allocation per explosion. Factions' core endgame is TNT
  cannons firing **many explosions per tick**; that is a steady lambda-alloc stream on the
  region/main thread, plus the "per-thread reusable `LongByteScratch`" is a **ThreadLocal**
  (many region threads on Folia) whose `get()` is a map probe per explosion, and whose reuse
  is only correct if cleared every explosion. The "0 bytes on the protection path" budget
  (§13.9) explicitly excludes this path, but the doc should own the per-explosion alloc.
- **Deny messages have no throttle.** §9: "on modern the JSON bridges to the native
  Adventure once per rendered message." A griefer holding left-click on a claim border
  generates a deny per block-break attempt (several/sec/player); if the native `Component`
  isn't memoized on the `RenderedMessage`, each re-bridges JSON→Component, and either way
  each sends a packet. N griefers on a border = continuous bridging + packet spam on the
  main thread.

**Fix:** hoist the explosion predicate to a cached field (stateless method ref stored once);
memoize the native `Component` on the `RenderedMessage` after first bridge; add a per-player
deny-message rate limit (e.g. one per ~1 s per verdict).

---

## MODERATE-10 — §6.2: StorageActor coalescing + H2 pool-size-1 CRITICAL immediate-flush compounds the writer stall

"Coalescing by key (last-value-wins)" requires the storage thread to **build a per-key map
every 100 ms** over the drained batch. Under a FATAL-2 megabatch that map is hundreds of
thousands of entries — an allocation storm on the single `fable-storage` thread, which then
cannot hold the 100 ms cadence, which backs up the ring, which stalls the writer. Separately,
CRITICAL mutations "trigger an immediate flush," and H2 runs **pool size 1** with file-mode
fsync; a burst of individually-committed CRITICAL ops (overclaim war, one claim per command)
serializes flushes on that one connection. The write path's throughput ceiling is one
fsync-bound thread; the design has no batching cap that survives the power tick.

**Fix:** cap drained-batch size independent of ring depth; presize the coalescing map; make
CRITICAL flush coalesce within a short window rather than per-mutation; consider H2
`WRITE_DELAY` / async fsync with an explicit durability note (ties to the accepted
crash-loss window).

---

## MINOR / verification notes

- **§6.4 boot load** streams 1 M claims into a table that **starts small and resizes ~21
  times**, rehashing ~1 M then ~2 M entries in the final doublings — hundreds of ms of
  avoidable rehash on a single-threaded boot. **Fix:** `SELECT COUNT(*)` and pre-size each
  per-world table. Also: a mid-game plugin `/reload` re-runs the whole 10–30 s single-thread
  load on the main thread = server freeze; document "no hot reload."
- **§4.4 `FactionDirectory` ids "never reused within a run"** → the int-id space and the
  `AtomicReferenceArray`/`AtomicIntegerArray` grow monotonically. A create/disband loop
  inflates the id space and forces array reallocation+copy; unbounded within a long run.
  **Fix:** free-list disbanded ids, or compact periodically.
- **§8 reload snapshot swap** re-renders **all faction chat tags** (MiniMessage parse per
  faction) on the writer during `/fa reload`; §13.7's "100× reload" test does this 100×. At
  10k factions that is 10k parses per reload on the main thread. Bounded and rare, but not
  free — batch off-thread and swap the rendered array.
- **§5b NPE on the hot path:** `affinity.get(uuid).factionId` assumes affinity is always
  populated. Citizens NPCs, disguised/fake players, or a player mid-join return `null` →
  NPE per PvP event → Bukkit swallows it → protection dies for that vector. This is exactly
  the "silent listener death" the design claims to eliminate. **Fix:** null-guard to a
  factionless default.
- **§4.3 large batch claims fire one cancellable Bukkit event per chunk on the writer.**
  `land.max-per-command` bounds it, but `/f fill` at the cap still fires
  hundreds–thousands of synchronous `FactionChunkClaimEvent`s (each walking dynmap/audit/WG
  MONITOR handlers) on the main thread in one command. Keep the cap tight and note the cost.
- **§3.6 boot probes:** dozens of `Class.forName` misses on legacy servers each construct a
  `ClassNotFoundException` with a filled stack trace (~µs each). Total is still only a few ms
  — **not** a real problem; the boot cost that matters is the data load, not the probes.
  (Verification: the probe budget claim is fine; the "< 2s + data load" caveat is doing the
  real work and data load is the unbounded term.)

---

## What is worth grafting even if B loses

- **Storage-as-projection + single-writer + no read-modify-write** genuinely erases the
  concurrency-bug catalog. Keep it — just get the O(n) sweeps and Vault IO *off* the writer.
- **Packed `long→int` open-addressed `ClaimTable` with an in-house chunk key and pure-math
  claim membership** (never `getChunk`) is the right read structure — with tombstones or
  snapshot-swap instead of backward-shift.
- **Effective-only `RelationTable`** making one-sided ALLY unrepresentable in the hot path is
  elegant and fast (O(1), no allocation, correct).
- **Consolidating the reference's two MONITOR move listeners into one and removing all DB
  work from move** is a real, correct win.
- **Explosion memo (O(distinct chunks) vs the reference's per-block JDBC)** is a large,
  legitimate improvement — just fix the lambda alloc.
- **Pre-rendered chat tags** (no per-message MiniMessage parse) and **cached per-(key,locale)
  `RenderedMessage`** are the right idea — extend the caching to the native Component bridge.
- **Descriptor-floor / sticky-getstatic build gates** are excellent (a correctness, not perf,
  contribution) and should survive regardless of which proposal wins.

**Bottom line:** the seam and the single-writer discipline are sound engineering, but the
performance case rests on numbers that are cherry-picked (memory), self-contradictory
(power-of-two 5 M = 201 MB not 120 MB), or refuted by the design's own constants (65 536-slot
ring vs an O(all-players) tick). Two issues are load-bearing and must be fixed before this is
buildable at 1000 players / 1M claims: the **backward-shift reader race** (protection bypass)
and the **main-thread O(all-players) power/raidable sweep** (TPS collapse + journal stall).
Neither is cosmetic; both live in the exact scenarios the plugin is for.
