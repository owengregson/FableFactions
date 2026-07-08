# Performance Critique — Proposal A (The Data-Oriented Core)

**Reviewer stance:** hostile, JVM + Minecraft-server internals. The *read plane* of this
design is genuinely strong — interned ordinals, primitive open-addressing, COW snapshots,
prerendered text. The design breaks on the **write plane, the tick plane, and the
durability plane**, and several headline numbers do not compose with each other at the
stated load (1000 players / 3M claims / 1M known players). This document names each flaw
by section, gives the load scenario that detonates it, and a fix that *keeps* the
architecture.

---

## Verdict summary

The single-writer + COW-read core is sound. But three things break as specified, and the
Appendix A/B numbers are internally inconsistent. Every fatal flaw has a fix that preserves
the core, so this is **sound-with-fixes** — but the fixes are not cosmetic; they are
re-designs of the bulk-op path, the power tick, and the durability tiering.

---

## FATAL-AS-SPECIFIED (break the stated load scenario)

### F1. Bulk claim mutations vs COW-per-claim + fixed single-writer + fixed journal ring
**Sections:** §2.2 (write path), §3.1 (writer), §5.1 (journal ring), §12 (merge/unclaim-all).

The write model is "copy the one affected shard (~6 KB), `shards.set(i, copy)`." The cost
model (§2.2) is explicitly justified as "claim ops are player-driven (≤ hundreds/sec) →
≤ ~1.5 MB/s transient copying, nothing." That assumption is false for the operations that
actually dominate claim churn:

- `/f disband` of a large faction → clear **every** claim it owns.
- `Ops.Merge` → reassign the owner ordinal of **every** claim of the merged faction (§12
  calls this "single atomic op on writer").
- `/f unclaim all`, admin `/f claim fill`, `/f claim square r`.

**Scenario:** a 100k-claim faction disbands. Because the shard-select hash
(`key * 0x9E3779B97F4A7C15L >>> 51`) deliberately *scatters* spatially-adjacent chunks
across all 8192 shards (§2.2), that faction's contiguous territory is spread across
~min(100k, 8192) = all 8192 shards. Consequences, all on the one writer, inside one Op:

1. **Allocation storm.** Naive one-COW-per-claim = 100k × 6 KB = **~600 MB** transient
   garbage in a single command → guaranteed promotion + a mixed/Full GC → a multi-ms-to-
   100ms **stop-the-world pause that freezes every region/main thread** (see F10). Even the
   "correct" batched version (rebuild each of 8192 shards once) rebuilds the *entire* 60 MB
   index — 30–200 ms of pure writer time during which the Op queue is not drained.
2. **Journal ring overflow.** 100k claim-clear records into a preallocated SPSC ring that
   Appendix B budgets at "~2 MB fixed" shared with the 65,536-slot MPSC queue → the ring is
   at most ~16–24k × 64 B slots. 100k records overflow it 4–6× → the **writer blocks** on a
   full ring waiting for the storage thread, which drains at DB-commit speed (seconds). For
   those seconds no player's claim/bank/chest Op is processed.
3. **Snapshot-swap storm.** Up to 100k volatile `shards.set` publishes (each a StoreLoad
   barrier), redundantly re-copying the same 8192 shards ~12× on average because the design
   never batches multiple claim mutations targeting the same shard.

**Fix:** (a) shard by *spatial region* (e.g., 32×32-chunk blocks → one shard), not by
scattered hash, so a faction's contiguous land concentrates in few shards and bulk ops
become O(shards-touched) with one COW per shard. (b) Make bulk claim ops a *single*
`BulkClaimOp` that computes the full set of affected shards, rebuilds each **once**, and
publishes once. (c) Give the journal ring a spill-to-heap overflow lane for bulk ops, or a
dedicated large-op journal encoding (one "faction disbanded, claims cleared" record, not
100k per-chunk records — the SQL side can delete by `WHERE faction_id=?`).

### F2. Power tick: eligible-set size, journal-ring overflow, and power_history explosion
**Sections:** §4e, Appendix A ("power tick 50k eligible → 3–6 ms"), §5.1.

Three compounding errors:

**(a) The 50k eligible figure is optimistic by 5–10×.** "Eligible = below max, unfrozen,
source-enabled." The entire *point* of power is that it drops on death and regenerates
slowly, so a large fraction of a 1M-known-player base sits below cap at any time. With
`REGEN_OFFLINE` enabled (the reference supports it; §3.11.2), **every offline player below
max is eligible every tick**. Realistic eligible set on a mature server is 200k–500k, not
50k. The "3–6 ms" becomes 20–60 ms of writer time every interval.

**(b) The SoA "cache-friendly scan" is actually a random gather.** `eligibleCursor` iterates
a *compacted list of ordinals* (free-list order), so `power[i]`, `online.get(i)`,
`powerBoost[i]` are **gather accesses by ordinal**, not a contiguous stream. 200k random
gathers into the SoA arrays ≈ 200k cache misses ≈ 10–20 ms in misses alone. The "12 B read +
clamp" model ignores this.

**(c) Journal ring overflow + power_history storage bomb.** The tick journals
`powerDelta(i, after, REGEN)` for **every** eligible player every tick (the `after != before`
guard is true for every regenerating player). At 200k eligible:
- 200k × 64 B = 12.8 MB of records into a ~16–24k-slot ring → overflow 8–12× per tick →
  **writer stalls for seconds each interval**; user commands (claims/banks) lag by seconds
  every 60 s.
- `power_history` is append-only and **not coalesced** (only `players.power` is
  last-write-wins). 200k inserts/tick × 1440 ticks/day = **~288 million rows/day** into
  `power_history`. Even at the stated 50k that is 72M rows/day. The table and its
  `(player_uuid, created_at)` index explode; the storage thread's `LIMIT/OFFSET` pagination
  reads (§5.5) degrade to full scans on a table with hundreds of millions of rows.
- The "flush every 250 ms **or 256 records**" policy (§5.1) fragments a 200k-record burst
  into ~780 tiny transactions, each an fsync round-trip on MySQL → the drain rate cannot
  keep up with the producer.

**Fix:** (a) **Do not journal routine regen to `power_history`** — record only material
events (death/kill/buy/admin) as the reference's *intent* is a raid ledger, not a
per-minute audit of every offline player. Keep `players.power` coalesced only. (b) Amortize
the regen sweep across the interval (process 1/Nth of the eligible set per sub-tick) instead
of one 200k burst, so the ring never sees more than a few thousand records at once. (c) Make
the flush batch size adaptive (thousands per txn during sweeps), not fixed at 256.

### F3. CRITICAL-durability immediate-flush on every claim contradicts the claim throughput
**Sections:** §5.1 (durability classes), §2.2 (hundreds of claims/sec), §7.4 (confirmation).

§5.1 lists **claim/overclaim as CRITICAL**: "the flush fires immediately and the op's
confirmation Effect is released only after commit." But §2.2 designs for auto-claim at
"hundreds/sec."

**Scenario:** 1000 players `/f claim auto` sprinting. Even at the modest 333 claims/sec
(§F5 below), CRITICAL means **333 immediate JDBC commits/sec**. On networked MySQL with
`innodb_flush_log_at_trx_commit=1`, each commit is an fsync round-trip (~1–10 ms) → a
**ceiling of ~100–1000 commits/sec on a good local SSD, far less on network/HDD**, and that
budget is *shared* with bank/chest/create/disband CRITICAL commits. The storage thread
saturates; the confirmation Effect (the auto-claim success feedback, dynmap marker, WG
mirror) is gated behind commit → **auto-claim visibly lags multiple seconds under load**,
and CRITICAL bank confirmations queue behind the claim flush storm.

**Fix:** demote routine claim/unclaim to `STATE` durability (the ≤250 ms crash window is
acceptable for land — the reference loses more on any crash). Reserve immediate-flush
CRITICAL for **money and chest contents** only, where the ≤250 ms window is genuinely
unacceptable. Batch claim commits like everything else. If overclaim-during-raid needs
stronger durability, group-commit the batch, not one-fsync-per-chunk.

---

## MAJOR ISSUES

### M4. Protection-decision cache-miss count and latency are understated 2–10×
**Sections:** §4c, §4b, §2.2, Appendix A.

- **"≤4 cache misses" is really 6–7** for a PvP hit in enemy territory: attacker session
  (1) + victim session (1) + `shards.get` cell (1) + `ClaimShard` object header (1) +
  `keys[]` (1) + `owners[]` (1, **separate array**) + `relations.pairs.get` into a ~1.2 MB
  table (1, guaranteed cold miss).
- **The keys[]/owners[] SoA split forces two arrays on every probe.** Because empty is
  encoded as `owners[i]==0`, you must touch `owners[]` on *every* probe even to detect an
  empty slot — so a wilderness lookup (the common case) touches both `keys[]` and `owners[]`
  lines. Reserve a sentinel key for "empty" and the wilderness (unsuccessful) search touches
  only `keys[]` = one line.
- **Probe count is wrong.** Knuth: linear probing at α=0.6 averages **1.75 probes for a
  successful** search and **3.6 for an unsuccessful** one. "avg 1.4 probes" corresponds to
  α≈0.44, not the stated 0.6. Critically, the dominant protection query — "is this chunk
  claimed?" for the majority-wilderness map — is the **unsuccessful case at 3.6 probes**,
  not 1.4.
- **Latency.** 6–7 cold misses × 50–100 ns ≈ 300–700 ns, not the Appendix-A "30–60 ns." The
  30–60 ns is a warm-cache microbenchmark; at 3M claims / 1000 players the working set
  (60 MB claim index + 1.2 MB relation table + 1000 sessions) exceeds L2/L3, so misses are
  real. The "≥20M lookups/s single-thread floor" (§15.4, Appendix A) is likewise a warm-
  cache artifact — cold, it is 3–8M/s.

**Fix:** sentinel-key empty encoding (halve wilderness misses); interleave key+owner into a
single `long[]` where possible or co-locate; state the latency budget as cold-cache; label
the JMH floor "warm-cache" and add a cold-working-set benchmark to CI.

### M5. Scoreboard / nametag cost is O(n²) and completely unaddressed
**Sections:** §2.5, §6.1 (`teamApis`/`TeamBudget`), and the omission across the doc.

`version-deltas.md` §3.6 explicitly warns: "beware one scoreboard per player × many teams
memory on big servers (design, not API)." Faction plugins color nametags by the *viewer's*
relation to the target (green member / red enemy). That is inherently **per-(viewer,target)
= O(n²)**. At 1000 players that is up to **1,000,000 team-entry assignments**, and a single
relation change (`/f ally`, `/f enemy`) flips the nametag color of every online member of two
factions **for every viewer**, producing a **scoreboard packet storm**. The proposal
mentions `TeamBudget` (16/64-char truncation) but gives **zero cost analysis, zero update-
strategy, and no memory line item** for per-player scoreboards. This is the single largest
un-budgeted cost in the "1000 players" scenario.

**Fix:** budget it explicitly. Use a *shared* scoreboard with team-per-faction (not
per-viewer) where relation coloring is coarse, or cap per-viewer coloring to nearby players
(view distance) and drive updates from post-commit relation/faction effects with
coalescing, not per-change fan-out.

### M6. Session field ownership violates the single-writer invariant → false sharing
**Sections:** §2.1 (PlayerSession, "fields written only by the writer"), §4a, §7.4.

The doc asserts session fields are "written only by the writer (via release stores), read by
any thread." That cannot hold for the transient move/combat tracking fields:
- `lastChunkKey` / `lastOwnerOrd` must update on chunk transition. If the **region thread**
  writes them (the natural place — §4a reads them there), that **breaks the single-writer
  claim** and puts writer-owned and region-owned fields in the *same object* → the writer's
  release-store to `factionOrd`/`chatTag` dirties the cache line holding `worldIdx`/
  `lastChunkKey` that the hot move handler is reading → **false sharing between the writer and
  the move fast path**. If instead you round-trip `lastChunkKey` through the writer, you add
  **one Op per chunk-cross per player** (1000s/sec) and the session's key lags reality until
  the Op applies → territory transitions double-fire.
- `combatTagUntil` is set by `DamageIntake` on the region thread (§7.4); `flyOn`,
  `warmupHandle` similarly. These are region-written, writer/region-read.

**Fix:** split the session into an immutable **writer-owned identity block** (factionOrd,
rankPriority, chatTag, bypass — release/acquire) and a **region-thread-local mutable tracking
block** (lastChunkKey, combatTagUntil, warmup, fly) on its own cache line (`@Contended` or
manual padding), owned by exactly the region thread that owns the player. Stop claiming these
go through the writer.

### M7. `factionPowerCache` incremental maintenance can't model time-based inactivity — a hidden O(n) sweep
**Sections:** §2.4 ("recomputed incrementally… raidable sweep O(factions) with zero
queries"), reference §3.2.3/§3.11.3 (F1 inactivity exclusion).

The reference's `computeMaxLand`/`getFactionPower` **exclude inactive members' power**
(inactive = `lastActivity` older than N days). Inactivity is a **wall-clock** transition: a
member crosses the threshold by *not logging in*, with **no power event** to hook. An
incrementally-maintained `factionPowerCache` (updated only on power *deltas*) therefore
**cannot** reflect inactivity transitions. So either (a) raidability/max-land silently
ignore inactivity (behavior divergence from reference F1, which the doc claims to preserve in
§12), or (b) the writer must periodically recompute each faction's active power by walking
its members — an **O(total members)** sweep, exactly the O(n) scan §2.4 claims to have
eliminated. This is an O(n) scan disguised as O(factions).

**Fix:** maintain *two* caches — raw power (incremental) and a periodic active-power sweep on
the inactivity granularity (hourly is fine), and state honestly that inactivity-correct
raidability is O(members)/sweep, not O(factions).

### M8. MoveIntake same-chunk early-return skips warmup-cancel and fly-threat checks
**Sections:** §4a (same-chunk `return`), §7.4 (warmup cancel-on-move "≥1 block", fly threat).

The fast path returns on same-chunk **before** any session lookup ("~92% of moves"). But
§7.4 specifies warmup **cancel-on-move ≥1 block** and fly **eligibility re-check on move**.
A player who moves one block *within* a chunk takes the early return → **warmup is never
cancelled and fly threat is never re-evaluated** for sub-chunk movement. To fix the
correctness you must check "does this player have an active warmup/fly?" on *every* move —
which requires a session touch on every move, **destroying the "0 session lookup on same-
chunk" claim** that Appendix A's `<5 ns` / 0.8 ms-per-second budget (§15.10) depends on.

**Fix:** keep a per-region-thread bitset of "players with active warmup/fly" and consult it
(a single bit test, no session deref) before the same-chunk return; only those players pay
the extra work. Then correct the movement-plane budget to include it.

### M9. Memory budget (Appendix B, 140 MB) undercounts by ~70–90 MB and assumes single-world
**Sections:** Appendix B, §2.1, §2.2, §6.3, §10.1.

Omitted or under-counted line items:
- **UUID↔ordinal map** for 1M known players (`UuidIntHash`, 3 parallel arrays) at LF 0.6 ≈
  1.67M slots × 20 B ≈ **33 MB** — not listed.
- **name↔UUID cache** for offline members (needed for `/f kick <offlineName>` and offline
  tab-complete per research §3.3) — 1M × ~40 B ≈ **40 MB** — not listed. (If instead names
  are resolved from SQL, that contradicts "memory is authoritative" for that operation and
  risks a main-thread `getOfflinePlayer(String)` Mojang call — see M12.)
- **MessageCatalog** parsed component trees: 27 groups × 8 locales of pre-parsed Adventure
  templates + per-session `chatTagNative` components (×1000) — several MB, not listed.
- **ClaimShard 60 MB assumes 3M claims in ONE world** at even shard fill. The shard count is
  **fixed at 8192 per world**. A world with 8k claims still allocates 8192 shards (each with
  a min-size table) → ~250 B/claim instead of 20 B/claim; a 50-world multiverse server pays
  8192-shard overhead per world. The 60 MB / 20-B-per-claim figure holds only at the single-
  world extreme.

Realistic steady state is **~200–230 MB**, not the "declared, measured 140 MB." The tester
heap assertion will fail on a real multi-world server or reveal the missing structures.

**Fix:** size shard count per-world from claim count (or use a single global sharded index
keyed by (worldIdx, chunkKey)); add the identity maps, name cache, and catalog to Appendix
B; re-measure.

### M10. COW-everything trades locks for GC pressure → "readers never wait" is false under bulk ops
**Sections:** Axiom A3, §2.2/§2.3 (COW), §15.1.

"Readers are wait-free; readers never wait." True with respect to *locks*. False with respect
to *GC*. The design's own bulk mutations (F1 disband/merge = 100s of MB transient; F2 tick
journal churn; whole-table relation COW × relation bursts) produce exactly the young-gen
allocation spikes and old-gen promotion that trigger STW young/mixed/Full collections. During
any STW pause, **every region and main thread — i.e., every "wait-free" reader — is
stopped**. The lock-freedom claim quietly relocates the stall from a lock to the GC, and the
bulk-op allocation profile makes those stalls frequent and large.

**Fix:** cap transient garbage per Op (batched single-COW per shard from F1), pool/reuse
shard backing arrays where shape is unchanged, prefer in-place backward-shift on the *live*
shard for single-claim deletes guarded by a seqlock rather than always allocating a new
shard, and state a GC-pause SLO (e.g., ZGC/Shenandoah recommendation) since throughput
collectors will pause on the bulk bursts.

### M11. Faction-chest serialization threading is unspecified and stalls the writer
**Sections:** §7.3, §5.5, §2.5.

The chest is a **live Bukkit `Inventory`**; reading `getContents()` must happen on a Bukkit
thread (region/main) — the writer "never touches the Bukkit API." But the `ChestCommitOp`
must serialize 54 ItemStacks (Base64 `BukkitObjectOutputStream` / `serializeAsBytes`), which
is reflection-heavy, allocation-heavy, and **not obviously thread-safe off the owning
thread**. If the writer serializes, it **blocks the single writer for milliseconds per
chest** (and the 10 s dirty timer × many open chests makes this periodic). If the region
thread serializes and hands bytes to the writer, that is unspecified and puts real CPU on the
region thread. Either way the doc's "writer does chest content commits" (§2.5, §7.3) glosses
a genuine threading tangle.

**Fix:** capture ItemStacks on the owning region thread, serialize on the **storage/async**
thread (not the writer), and have the writer only sequence the metadata (dirty flag,
refcount). Document the thread that runs `serializeAsBytes`.

### M12. No backpressure policy for the MPSC queue under normal-operation saturation
**Sections:** §3.1 (65,536-slot bounded MPSC), §3.5 (queue rejects on shutdown only).

The only queue-full handling described is at **shutdown** ("queue rejects, commands answer
'shutting down'"). Under a normal-operation burst (F1 bulk op fanning out sub-ops, F2 tick,
a plugin/admin script mass-submitting, 1000-player login storm after a crash), the 65,536
slots fill. Then the producer (a **region/main thread**) either **blocks** — stalling the
very thread the architecture promises never to block — or the Op is **dropped** (lost
mutation, failed command). Neither is specified.

**Fix:** define it. For player-initiated Ops, a bounded block with a hard timeout that
converts to a user-facing "server busy, try again" is acceptable; for writer-internal fan-out
(bulk sub-ops) the writer must never enqueue more than it can drain — use the single-BulkOp
approach from F1 so bulk work never round-trips through the queue.

---

## Secondary notes (real, lower severity)

- **§4c "explosion 0 alloc":** `blockList().removeIf(predicate)` with a predicate capturing
  the stack-local 8-slot cache allocates one lambda per call (~16–32 B). Trivial, but
  contradicts the absolute "0 allocations." Use a preallocated instance predicate + instance
  cache field.
- **§6.1 MethodHandle seams (`onlineCollection`, `viewHandles`):** MethodHandle invocation is
  only constant-folded/inlined by C2 when the MH is in a **`static final`** field. The doc
  says "cached MethodHandles" without that guarantee; instance-field MHs become real indirect
  calls, so the "monomorphic seam" claim (§15.7) does not automatically extend to MH-based
  seams. Pin them `static final`.
- **§2.4 `int[] landCount` / `double[] bank` "racy display reads":** plain (non-volatile,
  non-opaque) array reads across threads have **no visibility guarantee** — readers may see
  indefinitely stale values. The §2.5 table says "atomic/opaque"; the prose says plain
  arrays. Make them opaque `VarHandle` reads to get the visibility the design assumes.
- **§2.2 false sharing on the shard *reference* array:** `AtomicReferenceArray<ClaimShard>`
  packs ~8 refs per 64 B line; the writer's `shards.set(i,…)` invalidates the line neighbors
  that readers probe. Immutable shard *contents* are fine, but the ref-array cells ping-pong
  under high mutation. Minor vs F1 but real; a striped/padded publication array mitigates.
- **§5.5 deep `LIMIT/OFFSET` pagination** on `power_history`/`audit_log`/`bank_ledger`: MySQL
  OFFSET scans-and-discards → O(offset). Combined with F2's table growth this is a slow read
  lane. Use keyset (seek) pagination on `created_at`.
- **§5.3 boot load is synchronous and blocking.** "Single-digit seconds from H2" is scoped to
  H2; **MySQL over network** at fetch-size 1024 for 3M claims is latency-bound (thousands of
  fetches) → tens of seconds of blocked `onEnable`, during which the server is unresponsive
  and the watchdog may trip. State a streaming/async boot-load path or a startup budget for
  the MySQL case.
- **M12/name resolution:** if any command resolves an arbitrary offline name via
  `getOfflinePlayer(String)` on the command thread (main thread on non-Folia), that is a
  blocking Mojang web call — `version-deltas.md` §3.3 explicitly forbids it. The doc never
  states the offline-name path; make it read-lane/async-only.

---

## Numeric-claims verification

| Claim (section) | Verdict | Reality |
|---|---|---|
| "avg 1.4 probes at LF 0.6" (§2.2) | **Wrong** | Knuth: 1.75 successful / **3.6 unsuccessful** at α=0.6. Wilderness = unsuccessful. 1.4 ≈ α=0.44. |
| "~2 cache lines touched" per lookup (§2.2) | **Understated** | ClaimShard obj + keys[] + owners[] (separate arrays) = ≥3 lines cold; +session +relation on the decision. |
| "protection decision ≤4 cache misses, 30–60 ns" (§4c, App A) | **Understated 2–10×** | 6–7 misses; 300–700 ns cold at 3M claims. |
| "≥20M lookups/s single-thread" (§15.4, App A) | **Warm-cache only** | Cold 3M working set → 3–8M/s. |
| "claim ops ≤ hundreds/sec → ~1.5 MB/s copy" (§2.2) | **False for bulk** | disband/merge/fill/unclaim-all = 10^4–10^5 shard COWs per command. |
| "power tick 50k eligible → 3–6 ms" (§4e, App A) | **5–10× low** | Offline regen → 200k–500k eligible; random gather; 20–60 ms + ring overflow. |
| "power tick 0 allocations (ring slots)" (§4e) | **True but overflows** | 0 alloc, but 200k×64 B >> ~2 MB ring → writer blocks. |
| "claim/overclaim CRITICAL, flush immediately" (§5.1) | **Contradicts throughput** | Hundreds of fsync/s impossible on networked MySQL. |
| "1M known players ≈ 44 MB" (§2.1) | **~10% low + doubling slack** | Fields sum ~49 B/player = 49 MB; up to 2× at doubling boundary. |
| "3M claims ≈ 60 MB / 20 B per claim" (§2.2) | **Single-world only** | Fixed 8192 shards/world → 250 B/claim for small/multi-world. |
| "steady state ~140 MB" (App B) | **~200–230 MB** | Omits UUID↔ord (~33 MB), name cache (~40 MB), catalog components. |
| "movement plane ~0.8 ms/s = 0.004%" (§15.10) | **Body-only, warm** | Ignores dispatch cost; assumes 0 same-chunk session touch (broken by M8); 20 moves/s/player is low for combat. |
| "3M claims load in single-digit seconds" (§5.3) | **H2 only** | MySQL: tens of seconds, blocking boot. |

---

## Bottom line

Keep: single-writer linearization, interned ordinals, primitive open-addressing, COW read
plane, prerendered text, field-scoped journal, the explosion chunk-decision cache. These are
correct and beat the reference.

Redesign before this survives 1000 players / 3M claims:
1. **Bulk-op path** — region-clustered sharding + single batched COW + bulk journal encoding
   (F1).
2. **Power tick** — amortize the sweep, stop journaling routine regen, adaptive flush, cap
   `power_history` growth (F2).
3. **Durability tiering** — claims to STATE, not CRITICAL fsync-per-op (F3).
4. **Scoreboard cost model** — the missing O(n²) budget (M5).
5. **Session ownership split** — stop pretending region-written fields go through the writer
   (M6).
6. **Honest numbers** — cold-cache latencies, real eligible-set size, real memory budget.
