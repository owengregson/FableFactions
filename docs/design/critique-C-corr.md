# Critique C — Correctness & Maintainability (concurrency specialist)

Target: `docs/design/proposal-C.md` ("The Deterministic Kernel").
Cross-checked against `docs/research/ref-bugs-concurrency.md` and `docs/research/ref-bugs-logic.md`.

**Verdict: sound-with-fixes.** The single-writer / immutable-snapshot / effect-projection
skeleton genuinely makes the *in-kernel* RMW races (concurrency BUG-1/2/4/8/16/17/18/21,
logic #3/#4/#17) unexpressible — that part of the thesis holds and is the design's real
strength. But the proposal's headline claim that *every* catalogued bug class is "made
impossible" is **false as written** for the paths that touch the outside world: Vault
economy, chest inventories, and player-inventory persistence all live outside the pure
reducer, and the escrow/WAL machinery that is supposed to cover them has concrete crash and
backpressure windows that reintroduce **money loss, money duplication, and item duplication**
— i.e. the exact BUG-7/11/12 it claims to kill. Several other load-bearing claims (kernel
mutation is "unexpressible", replay is "byte-identical", the writer "cannot bottleneck") are
overstated and hide real hazards. None require abandoning the architecture; all need explicit
fixes and the retraction of "impossible" → "narrowed to a bounded window".

Findings are ordered most-severe first. Each: section · flaw · failure scenario · fix.

---

## FATAL FLAWS

### F-1. Escrow sagas are not exactly-once across the Vault boundary — crash windows create *and* destroy money (§4.6, §14 rows 11/12, N-7)

The proposal asserts (§4.6) "Charge exactly for delivery — kills catalogued bugs 9/11/12
(logic) and 11/12 (concurrency)" and (§14) "every non-delivery path emits a compensating
`EscrowRefund`; conservation is a property test." This is only true when nothing crashes.
Vault is a **non-transactional external participant with no idempotency key and no
outcome-query API**, so a crash between the external Vault mutation and the local durable
settlement cannot be reconciled — and the compensating effect is itself not durable across
that crash.

* **Withdraw → money DUPLICATION.** Order (§4.6): reducer debits bank + records escrow
  (journaled) → `PayoutRequested` → Vault adapter credits the player wallet (durable in
  Vault) → enqueues `SettleEscrow(ok)`. `kill -9` *after* the wallet credit but *before*
  `SettleEscrow` is journaled. Boot: escrow is still open → boot policy "refund-to-bank"
  re-credits the bank. Net result: the withdrawn amount now exists in **both** the wallet
  **and** the bank. This is money creation — worse than reference BUG-12, which only lost
  money.
* **Deposit → money LOSS, with no crash even needed.** Order (§4.6/§3.7): pre-validate →
  fire event → Vault `withdraw` on the caller thread → **then** enqueue `CreditBank`. There
  is **no escrow record created before the Vault charge** — the escrow only comes into
  existence when the reducer processes `CreditBank`. Two ways the wallet is debited with no
  bank credit and no compensation record anywhere:
  1. `kill -9` between Vault withdraw success and `CreditBank` fsync → permanent loss (no
     escrow, no journal entry to replay).
  2. **No crash required:** deposit is a command, so `CreditBank` rides the *player lane*,
     which "may reject" when the 64Ki ring is full (§3.2/§3.5). Under load the enqueue
     returns `REJECTED_BUSY` *after* Vault already debited the wallet → money destroyed with
     a "busy" message. The proposal never says post-charge intents use the never-reject
     system lane; if they do instead, see F-4 (unbounded-lane OOM).
* The conservation property test (§13.2, `Σ wallet Δ + Σ bank Δ + open escrows = 0`) **cannot
  catch any of this** — it validates the kernel's internal bookkeeping, not the divergence
  between the kernel's model and the real Vault ledger, which is where the money actually
  lives.

**Fix:** (a) Journal an escrow reservation *before* any external mutation, on both directions
(`OpenEscrow` intent → durable → then Vault op → `SettleEscrow`); the deposit must reserve on
the wallet side first, not charge-then-hope. (b) Make the post-charge settlement enqueue
un-droppable (reserve ring capacity before charging, or use the system lane, with F-4's bound
addressed). (c) Boot reconciliation cannot be a blanket "refund-to-bank" — it must be
per-escrow-type (a *buy* escrow refunds the wallet, not the bank) and, for withdraw, must
treat the Vault outcome as *unknown*: either make the Vault op idempotent by `escrowId` with a
dedup log, or reconcile against Vault's balance, or accept and **document** an at-most-once /
at-least-once residual instead of claiming exactly-once. (d) Retract "made impossible" → "loss
bounded to ≤ one in-flight external op per crash."

### F-2. Kernel immutability is convention, not construction — an in-place array store silently races the lock-free read plane (§3.3, §4.1, I-1, N-2, N-7)

The central thesis (§0, §14 row-block, N-7): "this architecture contains zero shared mutable
domain state … those bug classes are not avoided — they are **unexpressible**" and (§3.3)
"kernel types are records/frozen arrays, so there is nothing to mutate." **Java has no frozen
arrays.** Every hot structure is backed by mutable primitive arrays: `ClaimAtlas` shards
(`long[] keys; int[] owner;`), `MemberDirectory` (`long,long→int` open-addressed),
`MemberArena` (dense `MemberRecord[]`), `Faction.relOut/relEff` (`int[]`/`byte[]`). The
`AtomicReference` publish (§3.3) only establishes happens-before for state reachable **and
frozen at the moment of `set()`**. A single `shard.owner[i] = factionIdx` performed in place
by a rule class — instead of copy-then-set — is a data write with **no happens-before edge**
to the lock-free readers on 1000 region threads.

* **Failure scenario:** a refactor in `ClaimRules` mutates a shard array in place to "save an
  allocation." Region threads calling `Verdicts.decide` → `claims.ownerAt` read a torn/stale
  `owner` value under the Java Memory Model — no exception, no crash, just a protection
  decision made against ownership that either never existed or is half-written. Intermittent,
  unreproducible, and it defeats I-1 without touching any Bukkit/JDBC import.
* The build firewall (§2) checks **imports and dependency groups**, and ArchUnit checks
  architecture; **neither detects `IASTORE`/`AASTORE` on a field reachable from the
  snapshot.** So the one property the entire read-plane safety rests on — that no shared
  backing array is ever mutated after publication — is enforced by review vigilance, which
  §0 and N-7 explicitly claim to have eliminated.

**Fix:** Downgrade the "unexpressible / compiled-in" language to "enforced by discipline +
test." Add a real enforcement seam: a bytecode gate that flags `*ASTORE` on any array field of
a type reachable from `KernelState` outside a whitelisted `*.Builder` (boot-only). Consider
wrapping the safety-critical arrays behind accessor types that only expose copy-on-write
mutators, or use `VarHandle`-published segments. At minimum, a jcstress/opaque-read race test
on `ClaimAtlas` mutation-under-concurrent-read must be in `check`, not just the single-threaded
`ownerAt` JMH pin (§13.7).

### F-3. Faction ordinal reuse + by-ordinal references → disband must scrub all inbound edges or you get phantom relations and phantom land ownership (§4.1, §4.4)

`FactionArena` is a "dense `Faction[]` by ordinal + **free-list**" (§4.1) — ordinals are
reused. But faction identity is referenced *by ordinal* from at least three places:
`ClaimAtlas.owner` (int = faction ordinal), every other faction's `relOut`/`relEff`
(`int[]` of ordinals), plus `InviteTable`, `MergeTable`, `EscrowTable`. Nothing in §4.4
states that `DisbandFaction` scrubs **all inbound** references before the ordinal is freed and
reused.

* **Failure scenario (relations):** Faction A (ordinal 42) is allied/enemied by 40 other
  factions. A disbands → ordinal 42 returns to the free-list. `CreateFaction` reuses 42 for a
  brand-new faction B. Every stale edge in the 40 other factions' `relEff` arrays now points
  at B → B silently inherits A's alliances and enmities (protection bypass: former allies can
  build in B's land; former enemies can overclaim it).
* **Failure scenario (land):** A's claims in `ClaimAtlas` still carry `owner == 42`. If the
  ordinal is reused before every claim is set to wilderness, B "owns" all of A's old land the
  instant it's created.

**Fix:** Either (a) never reuse ordinals (monotonic ids + tombstoned slots, at the cost of
array growth), or (b) make `DisbandFaction`/`MergeCompleted` an atomic reducer step that
scrubs *every* inbound reference — all claims → wilderness, all other factions' relation
edges, invites, merge requests, escrows — before freeing the ordinal, and add a property test
"no dangling ordinal reference after disband, across reuse." Note this makes disband
inherently O(faction size) — see M-3.

---

## MAJOR ISSUES

### M-1. Publication and player acknowledgment precede durability — the WAL is fsynced *after* `snapshotRef.set()` (§3.2, §6.1, N-6)

Writer loop order (§3.2): reduce → **publish snapshot (`snapshotRef.set`)** → hand batch to
journal (buffered) → hand to projector. The group-commit fsync (§6.1, "every batch or 50ms")
therefore happens *after* the snapshot is already visible and *after* `Notify` effects can
tell the player "success". This inverts WAL discipline (durable-then-ack).

* **Failure scenario:** player runs `/f claim`, the snapshot publishes, the enter-title/
  "claimed!" `Notify` fires, a second player reads the snapshot and starts building — then
  `kill -9` inside the ≤50ms unsynced window. On reboot the claim never happened. The plugin
  acknowledged and acted on state that was never durable. Combined with F-1, an escrow-linked
  op is acked to the player while the Vault side is already durable and the kernel side is not.

**Fix:** Move `fsync` before `snapshotRef.set()` for any batch that produced externally
observable effects (all of them, in practice) — publish and Notify only after durability. If
the added fsync latency on the publish path is unacceptable, split into "durable-publish" for
externally-visible ops vs "fast-publish" for idempotent internal ones, and explicitly document
which effects are acked-before-durable.

### M-2. Journal-content vs replay-mechanism contradiction; config swaps are not captured → replay is not deterministic (§4.5, §6.1, §9.1, N-1)

Two incompatible replay stories coexist:
* §6.1 + §5's absolute-value effects (`PowerChanged(before, after)`, `BankChanged(…balance)`,
  `ClaimSet(…prevOwner)`) say the **journal stores effects**, and boot "replays the WAL into
  the projector" — an *effect-fold* that never re-runs the reducer.
* §4.5 + N-1 say replay re-runs the **pure reducer** over envelopes ("all nondeterminism
  captured at enqueue", "`state₀ + journal ⟹ stateₙ` byte-identical"), which requires the
  journal to store **intent envelopes**, not effects.

These can't both be the journal format. The contradiction has teeth:
* **Config breaks the effect-fold path.** The reload effect is `ConfigSwapped(diffSummary)`
  (§4.3) — a **diff summary, not the full `ConfigImage`.** `ConfigImage` is part of
  `KernelState` (§3.3), so replaying the journal cannot reconstruct the config field from a
  diff summary. If replay re-reads the config *files*, those files may have changed since the
  journal was written → a journal spanning any reload replays to a **different state**. The
  "journal is the complete history" (§4.3) and N-1 both fail across any config change.
* **If replay is the reducer-fold path,** power math uses `Math.pow(deathStreakMultiplier,
  streak)` and `victim/killer` division (§4.4). `Math.pow` is **not** guaranteed
  bit-identical across JDKs/platforms; only `StrictMath` is. "Byte-identical canonical state
  hash" is then unachievable across the version matrix the plugin explicitly targets. §4.5
  never mentions `StrictMath`.
* Even on the effect-fold path, "byte-identical" is wrong wording: two logically-equal states
  with different open-addressing layouts (different insert/delete history) hash differently; a
  *canonical* (sorted) hash is O(n log n) over millions of rows, not "byte-identical".

**Fix:** Pick one replay model and make the journal match it. If effect-fold: `ConfigSwapped`
must carry the **full serialized `ConfigImage`** (or a content hash + embedded copy), and N-1
should be restated as "identical canonical state hash" (define the canonicalization). If
reducer-fold: journal intent envelopes, mandate `StrictMath` for every reducer float op, and
add a cross-JDK replay-hash test to the live matrix (§13.8), not just same-JVM (§13.3).

### M-3. The single writer stalls on O(faction-size)/O(atlas) intents; there is no faction→claims reverse index (§3.6, §4.4, §5, N-5)

The throughput proof (§3.6, N-5) assumes uniform ~2µs/intent and "< 200 intents/s". Several
intents are inherently O(faction size) or O(atlas) and run **inside the single writer**,
freezing *all* mutations server-wide for their duration:
* `DisbandFaction` / `UnclaimAll` must find and wilderness every claim owned by a faction. The
  `ClaimAtlas` is keyed chunk→owner with **no owner→chunks reverse index** (§4.1 lists none).
  Finding a faction's claims is an O(all shards × all worlds) scan — up to 5M entries. The
  proposal criticizes the reference's O(all rows) sweeps (BUG-9, §14 row 9) yet reintroduces
  an O(5M) in-memory scan for disband/unclaimall, plus an effect *per* removed chunk
  (thousands) → journal + projector spike.
* `MergeCompleted` moves *all* sender claims and members (§4.3) — O(sender size), copying every
  affected shard.
* `ImportBaseline` streams the entire legacy DB through the writer.

Any of these blows the "p99 intent→publish < 5ms" pin (§3.6, §13.7) and, because the writer is
the *only* mutation path, turns into `REJECTED_BUSY` for every other player's command for the
stall duration.

**Fix:** Maintain a faction→chunks index in kernel state (more COW structure to keep correct —
see the maintainability note M-8) so disband/unclaimall are O(faction land) not O(atlas); chunk
large structural intents into bounded sub-batches that yield between publishes (e.g., disband
emits N `ClaimRemoved` batches of ≤1024) so the writer stays responsive; and correct the N-5
arithmetic to acknowledge these O(faction-size) worst cases rather than asserting uniform 2µs.

### M-4. Subscriber / projector backpressure is unspecified — a slow consumer either stalls the writer or diverges, and a DB outage grows the journal without bound (§1, §3.2, §3.5, §6.1)

Effects fan out to SPSC queues for `StorageProjector`, dynmap, WG-sync, Discord, LWC, bStats,
and third-party `api.subscribe` listeners (§1, §10.1). §3.5's backpressure section covers only
the *inbound* intent lanes; nothing states the bound or overflow policy of these *outbound*
queues.
* If a queue is bounded and the writer blocks on full → one slow subscriber (Discord webhook,
  WG region API, a buggy third-party `EffectListener`) **stalls the single writer → every
  mutation server-wide freezes.** Exposing the raw effect stream via `api.subscribe` (§10.1)
  hands a third-party plugin the writer's liveness.
* If unbounded / drop → effect loss = integration divergence (WG-sync drift is a *protection*
  inconsistency between FableFactions and WorldGuard, not cosmetic).
* Specifically for the projector: if the DB is slow/down, the checkpoint (`ff_meta.journal_seq`)
  cannot advance, so "journal segments older than the checkpoint are deleted" (§6.1) never
  fires → the WAL grows unbounded → **disk full → crash.** There is no cap on journal growth
  when the projector can't keep up.

**Fix:** Specify bounded per-subscriber queues with an explicit policy: integrations may drop
with a resync marker (and a bounded catch-up read of the effect log), but must never block the
writer; run third-party `EffectListener`s on their own dispatch thread, never the writer.
Define journal-growth backpressure: if the projector falls too far behind, either apply
inbound-mutation backpressure deliberately (players see busy) or spill/alert — but bound the
disk.

### M-5. `MemberRecord` eviction contradicts I-2 (no DB reads) and breaks replay determinism (§4.1, I-2, N-1)

§4.1: factionless + max-power + default-prefs members are "evicted from memory and
reconstructed from **defaults/DB** on next contact."
* **Violates I-2** ("Nothing ever reads the database to make a game decision after boot"). If
  reconstruction reads the DB, the reducer would need IO (it's pure — impossible), so the read
  must be platform-side; but the DB is a 250ms-lagged projection (§6.1), so a fast
  evict→reconnect can read stale data.
* **Breaks replay.** Eviction is a memory-only optimization; it is not an intent/effect in the
  journal. On effect-fold replay (M-2), nothing evicts, so the replayed state retains the
  member while the live run evicted it → the two states differ → N-1 hash mismatch.
* **Silent cold-field loss.** Reconstruction "from defaults" discards `deathStreak`,
  `lastDeathAt`, `joinedAt`, `nameLast`, `lastActivity`. `PlayerConnected(uuid, name,
  localeHint)` (§4.2) carries none of these, so an evicted-then-returning player loses history
  that the eviction predicate ("max power, default prefs, factionless") does not actually
  guarantee is default (a factionless max-power player can still have a nonzero death streak /
  real `lastActivity` that F1 depends on).

**Fix:** If eviction stays, make it a journaled decision (`MemberEvicted`/`MemberRehydrated`
effects) so replay reproduces it, feed the full record through the rehydration intent rather
than reconstructing from defaults, and re-word I-2 to "no DB reads *for domain decisions*;
cold-member rehydration is a defined, journaled, deterministic path." Otherwise drop the
eviction optimization — 120MB for 1M members (§4.1's own figure) is not worth breaking two
stated invariants.

### M-6. Player lifecycle ordering assumes global order; the MPSC only guarantees per-producer order (§3.2, §3.4, §4.5)

The lazy-accrual model (§4.5) and `NotifyFaction` online/offline split depend on the kernel
"online set" being correct, fed by `PlayerConnected`/`PlayerDisconnected` on the **system lane
(MPSC)**. MPSC preserves FIFO **per producer thread only**. On Bukkit both events fire on the
main thread (one producer) → safe. On Folia, if join and quit for the same player can be
enqueued from **different** region/login threads, a fast relog can be reordered to
`Connected`→`Disconnected` in the queue.

* **Failure scenario:** flaky client relogs within a tick; reducer processes `Connected` then
  `Disconnected` → player marked offline while actually online → online-regen stops, accrual
  treats them as offline, faction messages route to inbox instead of chat, and session-derived
  state settles wrong.

Confidence is medium — it depends on Folia's threading of `PlayerJoin`/`PlayerQuit`, which the
proposal never verifies.

**Fix:** Attach a per-player monotonically increasing **session epoch** captured at the event
source (login sequence), carry it in the intent, and have the reducer reject any lifecycle
transition older than the last applied epoch for that UUID. Add this to the §13.8 live-matrix
Folia checks.

### M-7. Chest and player-inventory persistence diverge on crash → item dupe, uncovered by the WAL (§7.5, §12 chest row, §14 rows 7/15, N-6)

The WAL crash-safety story (N-6, §6.4) does **not** extend to team chests, because a chest
edit is not an effect until `CommitChestContents` fires on last-viewer-close (§4.2, §7.5).
Meanwhile the player's *own* inventory (where withdrawn items land) is persisted by the
**server**, independently and typically more often than the FableFactions commit.

* **Failure scenario:** player opens faction chest (64 diamonds), takes all → items now in
  their player inventory. `kill -9` before the last-viewer-close `CommitChestContents` is
  journaled. Server player-data save had already persisted the player's inventory (+64). On
  reboot the chest replays its **last committed blob** (still 64) → 64 diamonds duped. This is
  BUG-7/8 reproduced through the crash window; §14 rows 7/15 claim it "made impossible", but
  the guarantee only holds for graceful shutdown (§6.4 force-close), not `kill -9`.

**Fix:** Journal chest mutations incrementally (or commit on every meaningful inventory change,
not only on close) so the WAL actually covers chest state, and reconcile chest contents against
a token that survives crash. Retract "impossible" → "safe on graceful shutdown; crash window
bounded to the last uncommitted chest edit." Acknowledge that perfect chest/player-inventory
atomicity is impossible while player inventories are saved by the server outside the journal.

### M-8. The design has substantially more mutable session state than the "zero mutable state" thesis admits — with the exact BUG-14 lifecycle risk (§3.1, §4.6, §7.5, D-6, D-11)

§0/§14 claim "zero shared mutable domain state." True for *domain* state — but the platform
side carries plenty of per-player mutable state that carries the *same* leak/wipe risks the
catalog warns about: GUI sessions, chest sessions + **viewer refcounts**, teleport warmups,
and (D-6) **combat-tags**. §3.1 lists GUI/chest/warmup but not combat-tags, and §4.6 only
*checks* the combat tag during warmup — it never says where it is *set* or *evicted*.
* If the combat tag is **kernel state** (set per hit), every PvP hit becomes an intent. Big
  fights are hundreds of hits/sec, not the "~10 deaths/s" the §3.6 budget counts — the intent
  budget and the N-5 "cannot bottleneck" arithmetic are then wrong.
* If the combat tag is **platform state** (a `Map<UUID,Long>`), it is exactly the
  `flyStateByPlayer` shape from concurrency **BUG-14**: it must be evicted on `PlayerQuit` or
  it leaks unboundedly. The proposal specifies quit-cleanup for *none* of combat-tags,
  warmups, or GUI/chest refcounts.
* Chest **viewer refcounts** on Folia are mutated from potentially multiple region threads;
  "D-13 exclusive-open mode" is the escape hatch but is one line with no mechanism.

**Fix:** Enumerate every per-player platform map in a single `SessionRegistry`, give each a
mandatory `onQuit` eviction (and an ArchUnit/test gate asserting each is torn down), and decide
+ document where the combat tag lives with its cost accounted for in §3.6. Soften §0/§14 to
"zero mutable *domain* state; bounded, lifecycle-managed session state on the platform side."

### M-9. Escrow-command TOCTOU: the Vault charge happens on the caller thread against a stale snapshot, and only the "faction vanished" rejection is refunded (§3.7, §4.6)

§3.7 claims optimistic pre-validation is "TOCTOU-free because **only the reducer's answer
allocates resources**." For escrow commands this is **false**: the Vault charge is a real
resource allocation performed on the caller thread, against the pre-validation snapshot,
*before* the reducer runs. The reducer re-validates authoritatively — but §4.6 only specifies a
compensating `EscrowRefund` for the single case "if the faction vanished". Any *other* reducer
rejection of an escrow-backed intent (player was kicked between snapshot and reduce, bank
frozen, faction at some cap, config toggled the feature off via a racing `SwapConfig`) must
*also* refund, or the money is lost.

* **Failure scenario:** player `/f bank deposit 1000`; pre-validation passes; Vault debits
  1000; before `CreditBank` reduces, an officer kicks the player. Reducer rejects `CreditBank`
  (not a member). If that path doesn't emit `EscrowRefund`, 1000 is gone.

**Fix:** Make it an invariant that **every** reducer rejection of an escrow-linked intent emits
the compensating refund (drive it structurally: the reducer returns `Rejected` + the escrow id,
and the effect layer always refunds on rejection of an escrow-bearing intent), and reword §3.7
to exclude external-effect commands from the "only the reducer allocates" claim.

---

## MINOR ISSUES / SMELLS

* **Runtime escrow leak, no timeout (§4.6).** A `PayoutRequested` whose Vault adapter hangs or
  whose provider vanishes leaves the bank debited and the escrow open **until the next reboot**
  triggers boot-policy resolution. No runtime escrow expiry/timeout is specified. Fix: TTL on
  open escrows with a periodic sweep intent.

* **Derived aggregate ↔ lazy accrual coupling (§4.4/§4.5).** `powerCacheSum` is incrementally
  maintained, but online members accrue *lazily* between settlements, so the cached sum is
  stale relative to `Σ powerAt(member, now)`. Any raidable/max-land decision that reads the raw
  cache instead of the accrual-adjusted sum is wrong for up to a tick interval. The "recompute
  == cache" property test (§4.4) must define recompute *in terms of `powerAt` at the same
  tick*, or it will pass while the live path is wrong. This incremental-maintenance-of-derived
  state is precisely the drift class the immutable-value thesis is meant to avoid, reintroduced
  by hand.

* **Migration invariant clashes (§6.3).** The legacy DB can violate the *new* invariants: the
  reference had non-unique names (BUG-17), but the new schema adds a fold-cased UNIQUE index —
  importing two folded-equal faction names will violate it (abort or silent drop). Legacy
  asymmetric ALLY (logic BUG-21) has no representation in the new symmetric model. §6.3 doesn't
  say how the importer reconciles either. Fix: define dedup/rename-on-collision and
  asymmetric-relation downgrade policy, and test the importer against a corrupt reference dump.

* **Config-reload listener reconciliation window (§9.1).** `SwapConfig` updates kernel config
  (verdicts see it immediately) but the platform `FeatureReconciler` converges listeners
  *after* the `ConfigSwapped` effect propagates. Enabling a new protection listener via reload
  leaves a window where the config says "protected" but the listener isn't registered yet →
  griefing vector open for that window. Bounded and small, but real; document the ordering
  (reconcile-then-publish-config, or accept the gap).

* **`AckInbox` two-phase delivery is now at-least-once (§6.4, §14 row 10).** The BUG-10 fix
  (deliver exact ids, then `AckInbox` deletes only those) is correct against *loss*, but if the
  player quits between the `Notify` and the `AckInbox` enqueue, the ids aren't acked →
  **re-delivered next login = duplicate messages.** Better than loss, but the proposal should
  note the delivery guarantee is at-least-once, not exactly-once.

* **Boot-failure fail-safe unstated (§6.3).** If schema migration or journal replay fails, the
  proposal doesn't say the plugin refuses to enable. A plugin that boots with partial state
  would serve wrong protection decisions. Fix: fail-closed on any boot error.

* **WAL write-failure / fsync-hang handling unspecified (§6.1).** The single writer's
  `journal.append`/`fsync` has no defined behavior on `IOException` or a hung fsync (slow
  disk). A hang stalls the whole mutation pipeline with no timeout; a failure has no policy.
  Fix: define it (fail-closed + alert vs bounded retry).

* **God-object / bespoke-COW surface (§4.1, §4.4, maintainability).** `KernelState` has ~18
  specialized fields, each a hand-rolled COW structure (sharded open-addressed atlas, COW
  open-addressed directory with deletes/tombstones, sorted parallel relation arrays, several
  tables). COW open-addressing *with deletes* is notoriously subtle (probe chains, tombstones,
  resize). The "~9k kernel LOC, smaller than the reference" estimate (§15) is optimistic given
  ~12 correctness-critical persistent data structures whose immutability the entire read plane
  depends on (see F-2). This is a large, under-budgeted correctness surface concentrated in one
  module. Prefer proven persistent-collection primitives where the hot-path budget allows, and
  budget the test effort accordingly.

---

## STRENGTHS WORTH GRAFTING (even if this proposal loses)

* **Single-writer + immutable snapshot genuinely eliminates the in-kernel RMW race family.**
  For *pure domain* state (power, bank, claims, relations, name uniqueness, member/land caps),
  concurrency BUG-1/2/4/5/6/8/16/17/18/21 and logic #3/#4/#17 really are unexpressible — there
  is no reader/writer pair and no `find→mutate→save`. This is the correct structural answer to
  the catalog's root flaw (A)+(B) and should be preserved in any winning design.

* **One validation function shared by pre-flight and reducer (§3.7).** Running the *same*
  `ClaimRules.validate` optimistically on the caller thread and authoritatively in the reducer
  is the right TOCTOU-free pattern for in-kernel ops (the escrow caveat M-9 aside) — single
  source of truth, responsive UX, authoritative commit.

* **Reducer-owned symmetric effective relation edges (§4.1).** Deriving effective ALLY/TRUCE
  only when both sides wish it, maintained atomically, cleanly kills the asymmetric-relation
  class (logic #21) — graft this regardless of architecture (with F-3's disband-scrub added).

* **Checkpoint written in the same transaction as the projected rows (§6.1).** `ff_meta.
  journal_seq` advancing atomically with the data flush makes the DB a provably-consistent
  prefix and makes replay-from-checkpoint sound. This is the one piece of the persistence story
  that is correct as written.

* **Effects carrying absolute post-values (`after`, `balance`, `prevOwner`) (§4.3, §6.2).**
  Idempotent, seq-guarded projection with no `col = col + ?` is a clean way to make storage
  replay-safe — keep it.

* **Config-as-snapshot-state, reload-as-ordered-intent (§3.3, §9.1).** Serializing config swaps
  through the same pipeline eliminates torn in-flight config reads for the *in-memory* decision
  path (a real reference hazard). Keep the idea; fix the journal-capture gap (M-2) so it also
  survives replay.

* **Escrow saga *shape* (§4.6).** Two-phase reserve/settle with compensation is the right model
  for external economy — it just needs the durability ordering (F-1/M-1), pre-charge
  reservation, per-type boot policy, and honest "bounded window, not exactly-once" framing.
