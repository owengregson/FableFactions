# Critique C — Performance / JVM / server-internals review of Proposal C ("The Deterministic Kernel")

Reviewer stance: hostile, performance-only. I read the proposal in full and cross-checked the hot
paths against `version-deltas.md` and `ref-engines.md`. The read-plane idea is genuinely good and
cures the reference plugin's actual disease (per-event JDBC in `EngineProtection`/`EngineChunkChange`,
research §3.1/§3.2). But the **write plane's headline numbers do not survive contact with the stated
load (1000 players / 1M–5M claims / up-to-1M member rows)**, and at least one structural gap is likely
fatal to the throughput pin as written. Below: named section, the flaw, the load scenario that breaks
it, the fix.

---

## Verdict

**sound-with-fixes.** The bones (immutable snapshot reads, primitive claim atlas, pre-rendered tags,
WAL+projector) are correct. But four load-bearing performance claims — N-3 "zero-allocation hot path,"
N-4 "O(1) at millions," N-5 "the writer cannot be the bottleneck," and the §13 pin "≥100k intents/s /
p99 publish < 5ms" — are **measured at a scale and configuration that excludes the dominant per-intent
cost at real load**. Fixable without abandoning the model, but the numbers as written are marketing.

---

## FATAL flaws (break a headline claim at the stated load)

### F1 — `MemberArena` is described as a flat `MemberRecord[]` with no COW-sharding → every member mutation is O(total members)
**Sections:** §4.1 (state shape), §5e (power tick), §3.6 (throughput math).

The proposal is meticulous about copy-on-write **sharding** for `ClaimAtlas` (4096 shards) and
`MemberDirectory` (256-shard COW). It then describes `MemberArena` as "**dense `MemberRecord[]`**"
with *no* mention of sharding or COW. Under I-1 the arena is immutable, so a single power change /
death / pref toggle / join / leave must produce a **new** arena. A flat immutable array has no
structural sharing: changing one record copies the entire backing array.

- At 1M live member records the arena is a 1M-entry reference array = **8 MB copied per single member
  mutation**, plus a new `MemberRecord`.
- Load scenario: hard PvP server, 10 deaths/s (§3.6). `RecordDeath` mutates victim power + streak +
  lastDeath and killer power → 8 MB array copy per death → **~80 MB/s memcpy + promotion churn** just
  from deaths. `PowerTick` at 1000 online is one 8 MB copy/min (tolerable), but per-event deaths are
  not.
- This directly refutes the "10 µs service time" behind N-5. An 8 MB memcpy is ~1–2 ms, i.e.
  **100–200× the assumed per-intent cost**. Effective single-writer throughput for member-touching
  intents falls from 100k/s to well under 1k/s.

Either the arena is *actually* sharded like the atlas (then say so — and the M1 undercounting applies
to it too), or it is flat as written and the throughput pin is only achievable on a toy member set.
**Fix:** shard `MemberArena` with the same COW discipline as `ClaimAtlas`/`MemberDirectory`, or make it
a persistent index tree so a single-record change rebuilds only a root-to-leaf path. State this
explicitly and re-run the pin at 1M members.

### F2 — "One atomic intent per logical operation" forces O(N) writer-blocking mega-intents that periodically blow p99 and can storm `REJECTED_BUSY`
**Sections:** §3.5–§3.7, §4.2 (`TaxSweep`, `PowerTick`, `SwapConfig`, `UnclaimAll`, `DisbandFaction`), §9.1.

Publication is end-of-batch and a batch drains up to 1024 intents before the single writer publishes or
services anything else (§3.2). Any single intent that touches many rows blocks the entire mutation
pipeline for its full duration, and nothing that shares its batch (or arrives during it) publishes
until it finishes:

- **`TaxSweep`** (§4.2, once/24h) taxes every normal faction and, via `NotifyFaction`, emits
  `InboxQueued` for every offline member (research §3.8.5, §3.14). At 10k factions × ~20 members ×
  ~50% offline that is **~100k `Effect` objects allocated, journaled, and projected inside one
  reducer step** — hundreds of ms to seconds on the single writer. During it, player intents queue
  toward the 65,536 ring; a long enough sweep fills the ring → `submit` returns `REJECTED_BUSY` →
  players see `general.busy` during the nightly tax. The reference does this incrementally per faction
  on a timer; folding it into one deterministic intent concentrates the whole server's tax into one
  blocking step.
- **`SwapConfig`** (§9.1) must re-render `tagLegacy`/`tagMini` for every faction whenever
  `chat.tag-format` changes → 10k `Faction` copies + 10k MiniMessage renders in one intent.
- **`UnclaimAll` / `DisbandFaction`** touch every claim / relation edge of the faction in one intent
  (and disband needs the F5 scrub below), again O(N) on the writer.

The §13 pin "p99 publish < 5 ms under 10k intents/s synthetic flood" holds in steady state but is
**violated on a fixed schedule** by these mega-intents. **Fix:** chunk sweeps into many bounded
intents (`TaxSweepPage(cursor, limit)`, `PowerTickPage`), cap effects-per-intent, and let the writer
publish between pages. Determinism is preserved (the pages are still ordered, seq-numbered intents).

### F3 — The "500× headroom / cannot bottleneck" proof rests on a service time that is 10–200× too low at real state size, and the pin config hides it
**Sections:** §3.6 (arithmetic), §13.7 (pins), N-5.

The M/M/1 argument is: λ ≈ 200/s, service ≈ 10 µs ⇒ ρ ≈ 0.002 ⇒ no waiting. The math is arithmetically
fine but the **10 µs input is wrong for the expensive intents at scale**:

- A single-chunk claim is not 12 KB (see M1) but ~44–54 KB of copy; a member mutation is up to 8 MB
  (F1); `TaxSweep`/`PowerTick`/`SwapConfig` are O(N) (F2).
- The §13 "synthetic flood" JMH pin almost certainly runs on a small state (no 1M-member arena, no
  5M-claim atlas), with fsync disabled or on tmpfs (M2), and without the five real effect subscribers
  attached. So it validates the *mechanism*, not the *loaded system*. **The load scenario for the pin
  is not the load scenario for the claim** — which makes N-5 unfalsifiable as presented.

**Fix:** re-run the pins at target state size (5M claims, 1M member rows) with real fsync and all
subscribers wired, and publish the per-intent-type cost distribution, not a single mixed number.

---

## MAJOR issues

### M1 — `ClaimAtlas` per-claim allocation is undercounted ~4×, and hash-sharding is worst-case for the dominant *batch* claim workload
**Sections:** §4.1 ("copy ONE shard ≈ 12KB"), §5a, §3.6.

To present an immutable atlas in which one shard changed, the pointer path from atlas root to that
shard must be rebuilt. As described ("new `ClaimAtlas` record referencing 4095 old shards + 1 new") the
root is a flat `Shard[4096]` per world → **a 4096-ref = 32 KB array rebuild on every single-chunk
claim**, on top of the shard copy. At 4–5M claims the shard itself is `long[]`+`int[]` at 0.55 load
factor ≈ 18–22 KB, not 12 KB. So a single auto-claim (§3.6, ~100–150/s) is **~50–55 KB of garbage, not
12 KB** — the "zero garbage" / N-3 story is off by ~4× on the most common mutation.

Worse, `mix(chunkKey) & 0xFFF` **deliberately destroys spatial locality**. The dominant claim command
is spatial: `/f claim square 8` = 256 chunks (§3.6 says "ONE intent, ≤200 chunks"). After the mix those
256 keys scatter across ~256 distinct shards out of 4096, so one square-claim copies **up to ~256
shards (~3–5 MB) + rebuilds the 32 KB index** — for a command a player can spam. A creeper/TNT read
path benefits from the 1-element memo (§5c, genuinely clever), but the *write* path is penalized
exactly where claims are spatial.

**Fix:** (a) replace the flat `Shard[4096]` with a shallow 2-level index (e.g. 64×64) so a single-shard
change rebuilds ~128 refs (~1 KB) not 4096; (b) shard **spatially** (`(chunkX>>k, chunkZ>>k)`) instead
of by hash, so a square/fill claim lands in 1–4 shards and copies KBs not MBs. Point lookups stay
2 hops; batch claims stop being pathological.

### M2 — `fsync` runs on the writer thread and publication precedes durability
**Sections:** §3.2, §6.1.

§6.1: "group-commit `fsync` every batch or 50ms," and §3.2 hands the batch to the journal "**same
thread**." So the single writer blocks on a disk `fsync` per batch. On a busy host (contended SSD,
spinning disk, or — common on rented Minecraft hosts — a networked/overlay filesystem) fsync latency
is 1–50 ms with hundreds-of-ms tails. That is **injected directly into the writer's critical path**,
capping throughput and blowing the p99 < 5 ms publish target independent of CPU cost. "fsync per batch"
also means at low load (small batches) you fsync very frequently → the system is fsync-bound, not the
CPU-bound 100k/s the pin reports.

Second problem: §3.2 publishes the snapshot **before** the journal write. A reader can therefore
observe (and act on in-game: protect a chunk, spend power, move an item) state that is **not yet
durable**. A `kill -9` in that window reverts the state on replay while the in-game consequence already
happened — that is exactly the "crash safety" N-6 markets against.

**Fix:** move journal append+fsync to a dedicated durability thread with group commit; publish the
snapshot only **after** the batch is fsynced (or explicitly document the "≤50 ms of visible-but-not-yet-
durable state" window as accepted, and stop claiming torn-free crash safety). Keeping fsync off the
writer is the bigger win; the writer should hand a filled buffer to the durability thread and move on.

### M3 — Single MPSC ring: 1000-way producer CAS contention (Folia) + likely false sharing
**Sections:** §3.2 (`new MpscRingBuffer<>(65_536)`), §3.4.

On Bukkit/Paper most intents originate on the main thread, so producer concurrency is low. **On Folia,
events fire on many region threads** (§3.4 routes decisions inline per region and mutations via
`IntentBus.submit` from any thread) → genuine multi-producer contention on a **single** ring tail. 1000
producers CAS-ing one atomic cursor is a retry storm that limits submit throughput and adds tail
latency to the game threads (the very threads you promised never block). A hand-rolled
`MpscRingBuffer` without `@Contended` padding also suffers classic false sharing between the shared
producer cursor and the consumer cursor, and between `snapshotRef` and any co-located hot writer field
— cache-line ping-pong on every submit and every publish.

**Fix:** use JCTools `MpscArrayQueue` (padded, `getAndAdd`/`lazySet`-based) or shard ingress into N
rings drained round-robin by the writer; pad `snapshotRef` and the ring cursors (`@Contended` /
manual 128-byte padding). Prove it with a Folia-shaped bench (many producers), not a single-producer
JMH loop.

### M4 — Effects escape to five async consumers → per-effect allocation (not "zero"), and the "reused EffectBatch" conflicts with fan-out
**Sections:** §3.2 ("reused `EffectBatch`"), §1 (5 sinks: journal, projector, feedback, integrations,
api-bridge), §4.3.

Every mutation allocates its `Effect` records (`PowerChanged`, `ClaimSet`, `Notify` with a `String[]`
args array, etc.), and each is handed to ~5 downstream SPSC queues that hold the reference across
thread boundaries. You therefore **cannot pool the `Effect` objects** — they must be freshly allocated
per mutation and survive long enough to cross threads (young-survivor → promotion pressure). So the hot
*write* path allocates in proportion to effect count; N-3's "zero-allocation" is a *read*-path property
only, and the proposal's own steady-state framing ("§5: zero garbage") overstates it whenever mutation
rate is nontrivial (deaths, auto-claim, tax).

The "reused `EffectBatch`" claim is also in tension with multi-consumer handoff: if the same `List` is
shared to five consumers you cannot clear/reuse it until all five drain → either you copy per consumer
(5× allocation) or the writer stalls waiting on the slowest consumer. And a slow subscriber (DiscordSRV
reflection notifier doing network IO, dynmap marker rebuild) with a bounded queue **back-pressures the
single writer**; with an unbounded queue it leaks. The proposal never states the queue bound or the
writer's behavior on a full subscriber queue.

**Fix:** decouple subscribers behind their own bounded, drop-or-coalesce queues that **never** block
the writer (feedback/integration are best-effort; storage+journal are the only must-not-drop sinks);
document the bound and the overflow policy; accept per-effect allocation and size the young gen for it.

### M5 — Inactivity-excluded power sum cannot be O(1)-incremental (it is time-dependent) → the reference's O(members) scan reappears on the ~100/s claim path
**Sections:** §4.4 ("aggregates incrementally maintained," `powerCacheSum`), §5e; research §3.2.3
(`computeMaxLand`), §3.11.3 (`getFactionPower`).

The reference excludes members whose `now - lastActivity > inactiveDays` from a faction's power sum.
That membership flips **with the passage of wall-clock time, with no intent firing**. You cannot keep
`powerCacheSum` correct incrementally, because a member silently crossing the inactivity threshold
mutates the effective sum without any event to hook. So either:

- (a) recompute the sum on read = **O(members) scan** — exactly the reference cost you claimed to
  delete (§4.4) — and overclaim checks call `computeMaxLand` for *both* attacker and victim on **every**
  claim/overclaim attempt, at ~100–150/s auto-claim (§3.6); or
- (b) snap inactivity to epoch/tick boundaries and accept the max-land/raidable numbers being stale
  between ticks — a behavioral deviation from the reference's real-time computation, currently
  undocumented in the deviation register.

**Fix:** pick (b) explicitly (recompute excluded-sums only in `PowerTick` from the online/lastActivity
state, register it as a deviation), and maintain a per-faction `activePowerSum` that is recomputed only
when a member's activity epoch changes — never per claim check.

### M6 — No GC strategy for a multi-GB, pointer-rich, long-lived immutable graph on a Java 8 floor
**Sections:** §3.6, §4.1, N-6; version-deltas Risk #6 (Java 8 host floor even on 1.7.10).

The whole `KernelState` (5M-claim atlas + up-to-1M member records + 10k factions with sub-arrays) is a
**live** immutable graph. The write path also generates young garbage at high rate: per-intent
intermediate `KernelState`s (a 1024-batch discards 1023 of them, §3.2), plus atlas/arena/faction copies
(M1/F1), plus escaping effects (M4). At the 100k/s pin that is multi-GB/s allocation.

- On modern JDKs (G1/ZGC) young garbage is cheap *if it dies young* — but the giant live set makes G1
  mixed/remembered-set and marking costs scale with live objects, and COW constantly creates
  young→old cross-generational refs (write-barrier/card-marking on every store).
- **The declared host floor is Java 8** (version-deltas Risk #6, §11). Java-8-era servers commonly run
  Parallel or CMS. A Parallel-GC **full GC** over a multi-GB heap with millions of live objects is a
  multi-second stop-the-world pause that freezes the *whole server* (all threads hit the safepoint,
  including the main thread) — a far worse tail than anything the reference's per-event JDBC produced.

The proposal never names a collector, heap sizing, or the STW implications of holding the entire world
as a live immutable graph. **Fix:** specify G1/ZGC minimums and heap sizing per state tier; keep the
truly large structures primitive/SoA (see M7) to bound object *count*, not just bytes; document that
the Java 8 floor is for *legacy MC hosts with small states*, and gate the large-state configurations to
modern JVMs; measure full-GC pause at 5M claims / 1M members before claiming N-6.

### M7 — "Dense" member/faction arenas are reference-dense (AoS), not data-dense (SoA) → cache-miss-per-element on every scan path
**Sections:** §4.1 (`FactionArena` "dense `Faction[]`", `MemberArena` "dense `MemberRecord[]`").

Only `ClaimAtlas` is genuinely primitive-packed (`long[]`/`int[]`). `Faction[]` and `MemberRecord[]`
are arrays of **references** to objects scattered across the heap. Every scan that iterates them —
`PowerTick` (1000 members), `TaxSweep`/`checkRaidableTransitions` (10k factions), `/f list`, `/f top`,
the disband scrub (F5 below) — pointer-chases one cache miss per element *plus* misses into each
element's sub-arrays (`relEff`, `ranks`). 10k factions × ~2–3 misses ≈ ~1–3 ms of pure memory latency
per full-faction sweep, on top of the work. This undercuts the "cache-hostile layouts are impossible
here" framing for everything except the atlas.

**Fix:** store the hot per-member fields (`power`, `powerAsOfTick`, `factionIdx`, `lastActivity`,
`prefsBits`) as **parallel primitive arrays** (SoA) indexed by member ordinal; keep only cold/rare
fields boxed. Same for the faction fields the sweeps touch (`bank`, `powerCacheSum`, `landCount`,
`flagBits`, `raidable`). This also shrinks the live object count for M6.

---

## Additional findings (lower severity but real)

- **M8 — Disband requires an O(all-factions) relation scrub.** `Faction` stores only forward edges
  (`relOut`/`relEff`, §4.1); there is no reverse index. Removing a disbanded ordinal from every other
  faction's `relEff` requires scanning all factions' arrays = O(factions × avg-relations) inside the
  `DisbandFaction` intent (writer-blocking, per F2). Combined with **ordinal reuse via the free-list**
  (`FactionArena` "dense array + free-list"), a missed scrub entry becomes a phantom relation the *next*
  faction to reuse that ordinal inherits — a correctness landmine with an O(N) fix. **Fix:** keep a
  reverse-edge index, or tombstone ordinals instead of reusing them.

- **M9 — `computeMaxLand` inactivity math aside, the coalescing in §3.5 implies a queue scan.**
  "If the writer finds a newer `PowerTick`/`TaxSweep` already queued it drops the stale one" — an MPSC
  ring is not searchable; either you scan it (O(depth)) or keep a side "pending" flag. Specify the
  flag; a scan on every drain is O(n).

- **M10 — Vault calls on the command (main) thread.** §4.6 deposit does "Vault `withdraw` on the caller
  thread"; Bukkit command dispatch is the main thread. A DB-backed economy plugin makes this
  **main-thread IO**, contradicting the zero-main-thread-IO framing. Unavoidable with the sync Vault
  API (parity), but stop marketing it away.

- **M11 — `ownerAt < 100ns at 5M`** (N-4/§13) is optimistic for *cold random* access. At 5M entries the
  `keys`/`owner` arrays total ~70–110 MB; a random chunk lookup during exploration misses L2/L3 and
  pays ~100 ns *per probe* to main memory (avg ~1.5 probes at 0.55 LF) → 100–300 ns cold, not < 100 ns.
  The pin likely measures hot repeated keys. Point-lookup design is still good; the number is soft.

---

## Numeric-claim audit

| Claim (section) | Stated | Reality | Verdict |
|---|---|---|---|
| Per single claim copy (§4.1, §3.6) | "~12 KB" | 18–22 KB shard + 32 KB index rebuild ≈ 50 KB | **~4× undercount** |
| `/f claim square 8` cost (§3.6) | "ONE intent, ≤200 chunks" (implied cheap) | up to ~256 hash-scattered shard copies ≈ 3–5 MB | **understated (M1)** |
| 5M claims memory (§4.1) | "≈ 80 MB" | 9.1M slots × 12 B ≈ 109 MB (atlas only) | **~30% undercount** |
| 1M members memory (§4.1) | "≈ 120 MB" | ~150 MB incl. directory + ref array | slight undercount |
| Member mutation cost (§5e, §3.6) | "~200 ns / 10 µs service" | up to 8 MB array copy ≈ 1–2 ms if arena flat (F1) | **100–200× off** |
| Reducer throughput (§3.6, §13, N-5) | "≥ 100k intents/s, 500× headroom" | true only for cheap intents at toy scale; expensive intents 100 µs–2 ms | **overstated** |
| Publish p99 (§13) | "< 5 ms under 10k/s flood" | steady-state yes; blown periodically by mega-intents (F2) + fsync (M2) | **conditional** |
| Power tick (§5e) | "~1 ms once/min" | ~2–4 ms if arena flat-copies 8 MB; cadence fine | soft |
| Boot 5M claims (§6.3) | "~10 s @ 500k rows/s" | JDBC + shard build realistically 100–300k rows/s → 20–60 s | **optimistic** |
| `Verdicts.decide` (N-3) | "20M ops/s, 0 B/op" | plausible for the isolated pure function | **holds** |
| Reads under flood (N-2) | "p99.9 < 2× solo" | wait-free `AtomicReference.get`; plausible | **holds** |
| `ownerAt` (N-4) | "< 100 ns at 5M" | 100–300 ns cold random; < 100 ns hot | **soft** |

---

## Strengths worth grafting even if C loses

1. **Immutable-snapshot read plane for protection/relation/PAPI/chat.** This is the correct cure for
   the reference's real pathology (per-event JDBC in `EngineProtection`/`EngineChunkChange`, research
   §3.1/§3.2). Wait-free reads that never touch the writer are the single best idea here — keep it in
   any proposal.
2. **Pre-rendered chat tags at mutation time** (§5d). Eliminates per-message MiniMessage parsing and
   the injection class simultaneously. Genuinely zero-parse on the chat hot path. Adopt regardless.
3. **Primitive SoA claim atlas for point lookups** (`long[]`/`int[]`, in-house chunk key). The lookup
   side is excellent (fix the *mutation* side per M1). The in-house key (never Paper `getChunkKey`) is
   the right call per version-deltas §3.9.
4. **1-element explosion memo** (`lastKey/lastVerdict`, §5c). Collapses a 300-block creeper from 600
   lookups to ~6 by exploiting spatial clustering. Cheap, correct, high-value — graft it.
5. **WAL + checkpointed batched projector** keeps SQL writes off every hot path and batches them
   (research shows the reference wrote per-operation). Right direction — just get fsync off the writer
   thread (M2).
6. **Boot-time capability probing into one final `PlatformProfile`, no per-event re-probe** (§7.1).
   Correct and cheap; the hot paths read a final field. No perf objection.
7. **Deterministic journal replay** (N-1). Perf-neutral but a real operational asset for reproducing
   incidents; keep as a debugging capability even if the storage authority model changes.

---

## Bottom line

The read plane is right and several primitives (pre-rendered tags, atlas point lookups, explosion memo,
capability profile) should survive into whatever ships. The write plane, as specified, does **not** hit
its own throughput and latency pins at 1000 players / 1M–5M claims / 1M member rows, because (1) the
member arena as written copies O(N) per mutation, (2) determinism forces O(N) mega-intents through a
single writer, (3) the pin config excludes the costs that dominate at scale, and (4) fsync sits on the
writer with publish-before-durability. All four are fixable inside the same architecture — shard the
arena (SoA), page the sweeps, shallow/spatial atlas index, fsync off-writer after publish, JCTools
padded ingress, and a named GC with heap tiers — but they must be fixed and the numbers re-measured
before N-3/N-4/N-5 and the §13 pins can be believed.
