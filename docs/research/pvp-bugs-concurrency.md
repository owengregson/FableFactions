# pvpindex-factions — Concurrency, Persistence & Lifecycle Bug Catalog

Adversarial audit of `engine/`, `scheduler/`, `data/`, `service/`, `bootstrap/` (plus the command dispatch
and main-plugin lifecycle that drive them). Purpose: enumerate defects so the **FableFactions** clean-room
rewrite avoids them **by design**. Line numbers are from the original source at
`/Users/owengregson/Documents/pvpindex-factions`.

---

## 0. The root design flaw (read this first)

Everything below is a symptom of two structural decisions:

**(A) Every persisted entity is a detached, full-row, attribute-map model.**
`FactionModel`/`PlayerModel` extend Jaloquent `Model`; `repos.X().save(model)` emits an upsert of **all**
registered columns (`INSERT … VALUES … ON DUPLICATE KEY UPDATE` / rewritten to `MERGE INTO … KEY(id)` for H2 —
see `DatabaseManager.H2CompatJdbcStore`). There is **no field-level update path anywhere**. Consequently any code
that does `find → mutate one field → save` **rewrites every other column from a possibly-stale snapshot**.

**(B) The same rows are mutated from multiple threads with no synchronization, no row locking, and no
read-modify-write atomicity.** Commands run **synchronously on the main/region thread** (`FactionCommandExecutor.onCommand`
calls `cmd.execute(...)` inline). Power ticks, tax, and death/quit handlers run on **async** threads
(`scheduleAsyncTimer`, `runAsync`). Both populations load their **own** copy of a row, mutate it, and full-row-save it.
`repos.X().transaction(...)` is used on a few paths but (i) does not `SELECT … FOR UPDATE`, and (ii) does not
coordinate with the many non-transactional saves, so it does not prevent lost updates.

> **Design lesson (global):** the rewrite must pick ONE authoritative in-memory state per entity (single-writer,
> or an actor/region-confined owner), do **field-scoped atomic updates** (either `UPDATE … SET col = col + ?`
> for counters like power/bank, or optimistic-concurrency/versioned rows), and never full-row-save a stale snapshot.
> Power and bank are running balances and MUST be relative deltas at the SQL layer, not read-modify-write of a snapshot.

---

## Severity summary

| # | Bug | File | Sev |
|---|-----|------|-----|
| 1 | Full-row save clobbers unrelated columns across threads (systemic) | many | critical |
| 2 | Non-atomic RMW on **power** balance | `PowerServiceImpl.apply` | critical |
| 3 | Listener leak on `/fa reload` → duplicated event handling | `EnginesBootstrapComponent.stop` | critical |
| 4 | Non-atomic RMW on **bank** balance; power-tick reverts bank | `EngineEconomy`, `EnginePower` | high |
| 5 | Per-chunk claim lock removed in `finally` defeats mutual exclusion | `EngineChunkChange.claim` | high |
| 6 | Land-cap / overclaim TOCTOU across different chunks | `EngineChunkChange.claim` | high |
| 7 | Team-chest concurrent access = item dupe/loss | `EngineTeamChests` + `TeamChestServiceImpl` | high |
| 8 | Death-streak two-copy update never persisted | `EnginePower.applyDeathPower` | high |
| 9 | Blocking DB I/O on main/region thread (pervasive; per-block on explode) | `EngineProtection`, `EnginePlayerMove`, commands | high |
| 10 | Inbox: delete-before-deliver + truncation loses notifications | `EngineNotifications.onJoin` | high |
| 11 | `CmdPowerBuy` charges money then ignores blocked/disabled power result | `CmdPowerBuy` | high |
| 12 | Bank deposit/withdraw money vanish on save failure (no refund) | `EngineEconomy` | high |
| 13 | In-flight async DB writes not drained before pool close | `Bootstrap.stop` / `DatabaseManager.close` | high |
| 14 | `flyStateByPlayer` never cleaned on quit (leak) + wiped on reload | `FactionServiceImpl` | medium |
| 15 | No shutdown/reload flush of open team chests | `EngineTeamChests` | medium |
| 16 | `setRelation` non-atomic on `relations_json`; partial mirror | `FactionServiceImpl.setRelation` | medium |
| 17 | Faction name uniqueness TOCTOU (index not UNIQUE) | `createFaction`/`renameFaction` | medium |
| 18 | `joinFaction` member-cap TOCTOU | `FactionServiceImpl.joinFaction` | medium |
| 19 | Async power/tax touch Bukkit API (`getOnlinePlayers`, `getPlayer`) off-thread | `EnginePower`, `PowerServiceImpl` | medium |
| 20 | `FoliaTaskScheduler.scheduleAsyncTimer` throws on delay ≤ 0; global-region fallback wrong region | `FoliaTaskScheduler` | medium |
| 21 | Invite accept/send TOCTOU (double-accept, invite lost if join fails) | `InviteServiceImpl` | medium |
| 22 | Bank `transfer` fires event AFTER commit → uncancelable | `EngineEconomy.transfer` | low |
| 23 | `last_activity` never written → inactivity exclusion is dead code | `PlayerModel` / callers | low |

---

## 1. Concurrency: non-atomic read-modify-write & lost updates

### BUG-1 (critical) — Full-row save clobbers concurrent column writes
**Files:** `service/FactionServiceImpl.java` (kickMember 321-323, changeMemberRank 1172-1173, assignRole 847-848,
transferOwnership 547-557, joinFaction 960-967), `engine/EnginePower.java` (checkRaidableTransitions 285-286),
`data/model/PlayerModel.java` / `FactionModel.java` (all-column upsert).

`save()` writes **every** column. The `players` row co-locates `power`, `last_activity`, `last_death_at`,
`death_streak`, `faction_id`, `rank_id`, `power_frozen`, notification prefs. The async power tick
(`EnginePower.run` → `PowerServiceImpl.apply`) writes `power` on a background thread. Meanwhile a main-thread
`/f promote` loads its own `PlayerModel`, sets `rank_id`, and saves the **whole row** — reverting any `power`,
`last_activity`, or `death_streak` the tick wrote in between (and vice-versa).

Same on the `factions` row: `is_raidable`, `money` (bank), `relations_json`, `name`, home coords all share one row.
`EnginePower.checkRaidableTransitions` (async) loads a faction at `findAll()` time, flips `is_raidable`, and
full-row-saves — **reverting a bank deposit/withdraw/tax** that committed after the `findAll()` snapshot. Reverse
direction: a bank op reverts a raidable transition.

**Failure scenario:** Player at 9.0 power gets promoted while the power tick raises them to 9.5; final stored power
is 9.0 (tick lost). Or: faction deposits 10 000 to bank; 200 ms later the raidable sweep saves the pre-deposit snapshot;
bank silently reverts to old balance. Money/power vanish with no error logged.
**Design lesson:** never persist a snapshot of a wide row to change one field. Use targeted `UPDATE … SET col=?`
(or `col = col + ?` for balances) or per-entity single-writer ownership. Split hot mutable counters (power, bank)
from cold config (name, home) so writers never contend on the same row image.

### BUG-2 (critical) — `PowerServiceImpl.apply` is a non-atomic RMW on power
**File:** `service/PowerServiceImpl.java:49-88` (`find` 50 → `before = model.getPower()` 55 → compute → `setPower(after)` 83 → `save` 84).
No lock, no transaction, no `SELECT FOR UPDATE`, no relative UPDATE. `apply` is invoked concurrently from at least
four contexts on **different threads**: the async power-tick timer (`EnginePower.run`), the async death handler
and async kill handler (`EnginePower.onDeath` → `runAsync`), and the main-thread `/f power buy`. Two overlapping
`apply` calls both read `before`, both compute `after`, and the later `save` wins → lost update.
**Failure scenario:** Player is killed (−loss on thread A) and the regen tick (+regen on thread B) run within the
same window; both read power=10; A writes 8, B writes 10.5; result is whichever saves last — the death penalty or
the regen is silently dropped. On MySQL (`pool size > 1`) these truly run in parallel; on H2 (`pool size = 1`) the
JDBC calls serialize but the **compute-in-Java-between-find-and-save** window still loses updates.
**Design lesson:** power mutation must be a single atomic step: `UPDATE players SET power = LEAST(max, GREATEST(min, power + ?)) WHERE id=?`
returning the new value, or funnel all power writes through one owning thread/queue keyed by player.

### BUG-8 (high) — Death-streak computed on a copy that is never saved
**File:** `engine/EnginePower.java:158-208`. `applyDeathPower` loads `deadModel`, computes `streak`, calls
`deadModel.setLastDeathAt(now)` and `deadModel.setDeathStreak(streak)` (178-179) — then **never saves `deadModel`**.
It instead calls `powerService.apply(deadId, …)`, which loads a **separate fresh copy**, changes only `power`, and
saves that copy (with the OLD `last_death_at`/`death_streak`). The streak increment is discarded on every death.
**Failure scenario:** death-streak escalation (`loss * multiplier^streak`) never triggers; `death_streak` is frozen
at 0 forever; `last_death_at` never advances, so the streak window never even starts.
**Design lesson:** one load, one mutation of all affected fields, one save — atomically. Never split a logical update
across two independently-loaded copies of the same row. (Same anti-pattern as BUG-1, isolated here as a hard functional loss.)

### BUG-4 (high) — Bank balance RMW is non-atomic; power tick reverts bank
**File:** `engine/EngineEconomy.java` deposit (139/173-175), withdraw (220/246-253), tax (403-419).
Deposit loads the faction at method start (line 139), then after Vault withdraw does
`faction.setBank(faction.getBank() + finalAmount); save` on that **stale** object. Tax runs on the async timer and
does the same RMW on its own copy. None of deposit/withdraw/tax share a transaction or lock; `transfer` (307-346)
uses `transaction()` but that does not lock rows nor coordinate with the non-transactional deposit/withdraw/tax.
Combined with BUG-1, `EnginePower.checkRaidableTransitions` also full-row-saves the faction and can revert bank.
**Failure scenario:** two members `/f bank deposit` simultaneously (or a deposit races the hourly tax): both read
bank=1000, one writes 1100, the other writes 1300; 200 currency units created or destroyed. Or the raidable sweep
reverts a fresh deposit entirely.
**Design lesson:** bank is a running balance → relative SQL update inside a real transaction with row locking, and
never carried on a wide snapshot that other subsystems also save. Keep the balance out of the frequently-full-row-saved faction row.

### BUG-5 (high) — Per-chunk claim lock is removed in `finally`, defeating exclusion
**File:** `engine/EngineChunkChange.java:64-66, 214-216` (and unclaim 229-230, 271-272).
`claimLocks.computeIfAbsent(key, …)` fetches a lock object, `synchronized(lock)` guards the body, but the `finally`
does `claimLocks.remove(key)` **while still holding it**. Thread A removes the key on exit; a later Thread C then
`computeIfAbsent`s a **new, different** lock object for the same chunk and proceeds — while Thread B, which had
obtained the *original* lock before the removal, is still inside the critical section. Two threads now execute the
"guarded" claim for the same chunk under different monitors → no mutual exclusion.
**Failure scenario:** two players overclaim the same enemy chunk in the same tick window; both pass the
"already claimed?" and raidability checks and both `claimChunk`, or the board row is written twice / audit-logged twice.
**Design lesson:** never both key a lock map on the contended resource AND remove the entry inside the guarded region.
Use a striped/interned lock that outlives the critical section, or serialize claims per faction through a single owner.

### BUG-6 (high) — Land-cap / overclaim TOCTOU across different chunks
**File:** `engine/EngineChunkChange.java:158-163` (`currentLand = countByFactionId; if currentLand >= maxLand`).
The per-chunk lock (even if it worked, BUG-5) only serializes claims of the *same* chunk. Two members auto-claiming
two *different* chunks each independently read `currentLand` and both pass the cap. There is no faction-scoped
serialization of the land budget, and `computeMaxLand` recomputes from a live, concurrently-changing power sum.
**Failure scenario:** faction with 1 slot left; two players walk into wilderness (auto-claim on) in the same tick;
both claims succeed → faction exceeds max land / becomes raidable-invariant-violating.
**Design lesson:** enforce aggregate limits (land, members, allies) with an atomic conditional write
(`INSERT … WHERE (SELECT count(*) …) < cap`) or a per-faction lock, not check-then-act on separate reads.

### BUG-7 (high) — Team-chest concurrent access duplicates/loses items
**File:** `engine/EngineTeamChests.java:39-70` + `service/TeamChestServiceImpl.java:107-125`.
`sessions` is keyed by **player UUID**, not by chest. Two players open the same faction chest → two independent
`Bukkit.createInventory` copies of the same DB contents. On close, each player's inventory is serialized and
`setChestContents` full-blob-overwrites the row (last writer wins). No locking, no per-chest session, no merge.
**Failure scenario:** A and B open faction chest "vault" (64 diamonds). A takes 64, closes → row = empty. B (whose
snapshot still shows 64) closes → row = 64 diamonds again. A kept 64 AND the chest still has 64 → 64 diamonds duped.
Symmetric ordering loses items instead.
**Design lesson:** single shared live inventory per (faction, chest) with viewer refcounting; persist on last-viewer-close;
or a per-chest lock + re-read-merge. Never let two clients hold divergent copies of the same container.

### BUG-16 (medium) — `setRelation` non-atomic on `relations_json`, partial mirror
**File:** `service/FactionServiceImpl.java:388-414`. Reads source + target JSON, mutates maps in Java, saves source
(398) then conditionally saves target (406/413) — two separate full-row saves, no transaction. Two concurrent
relation changes on the same faction serialize the whole map from stale snapshots → one clobbers the other. If the
source save succeeds and the target save fails, the mirrored relation is left half-applied.
**Design lesson:** relations belong in a proper join table with atomic per-edge upsert/delete inside a transaction,
not a serialized JSON blob on a hot full-row-saved entity.

### BUG-17 (medium) — Faction name uniqueness is check-then-act with no UNIQUE constraint
**File:** `service/FactionServiceImpl.java` createFaction (147-165), renameFaction (440-446).
`idx_factions_name` is created as a plain `CREATE INDEX` (`DatabaseManager.createIndexes` 200), **not UNIQUE**.
Uniqueness is enforced only by a prior `findByName` read. Two `/f create Foo` (or a create racing a rename) both
pass the check and both insert. `findByName` later returns "the first matching" nondeterministically.
**Design lesson:** enforce uniqueness in the schema (UNIQUE index, case-folded) and handle the constraint violation;
do not rely on read-then-write for invariants.

### BUG-18 (medium) — `joinFaction` member-cap TOCTOU
**File:** `service/FactionServiceImpl.java:953-967`. Cap is checked (954-957) *outside* the transaction that adds the
member (960-967). Two simultaneous accepts/joins both pass and both insert → faction exceeds `maxMembers`.
**Design lesson:** enforce the cap atomically at write time (conditional insert / per-faction lock).

### BUG-21 (medium) — Invite accept/send TOCTOU
**File:** `service/InviteServiceImpl.java` acceptInvite (78-112), sendInvite (48-69).
`acceptInvite` deletes the invite (100) then calls `joinFaction` (101); if the join fails afterward the invite is
already gone and the player cannot retry. Two concurrent accepts both see the invite present, both delete (idempotent),
both call `joinFaction`. `sendInvite`'s duplicate-check (58) → save (68) is not atomic, so duplicate invites can be created.
**Design lesson:** perform the accept (delete invite + add member) as one atomic transaction with a re-check of the
invite row inside it; make send an atomic upsert keyed on (faction, invitee).

---

## 2. Folia / threading correctness

### BUG-19 (medium) — Async power/tax paths call main-thread-only Bukkit API
**Files:** `service/PowerServiceImpl.java` (`broadcastStaff` 240 iterates `Bukkit.getOnlinePlayers()`;
`notifyPowerChange`/`notifyBlockedByFreeze` call `Bukkit.getPlayer`/`getOfflinePlayer` 165,191,199,215),
`engine/EnginePower.java` (`run`/`tickPower` 82,120 and `checkRaidableTransitions` 305,322 run on the **async** timer).
These execute from the async scheduler. `Bukkit.getOnlinePlayers()` / world & entity access are not contractually
thread-safe off the main/region thread; on Folia this is a region-threading violation and can throw or read torn state.
The design assumes "async is safe for everything," which it is not for the Bukkit API surface it touches.
**Design lesson:** compute on async, but marshal every Bukkit-API touch (player lookup, messaging, online iteration)
back onto the correct owning thread via the scheduler. Keep a clear async-DB / sync-API boundary.

### BUG-20 (medium) — `FoliaTaskScheduler.scheduleAsyncTimer` unsafe delay math; wrong-region fallback
**File:** `scheduler/FoliaTaskScheduler.java:49-56` and `35-41`.
`runAtFixedRate(plugin, …, delayMs, periodMs, MILLISECONDS)` with `delayMs = delayTicks * 50`. Folia requires
initialDelay ≥ 1; any caller passing `delayTicks = 0` (a common "run immediately, then repeat" idiom) throws
`IllegalArgumentException` and the timer never starts — silently on a background path. Separately, `runSyncForPlayer`
falls back to `getGlobalRegionScheduler().run(...)` when the entity task can't be scheduled (37-40); if that task
touched the player's location/entity it would run on the **wrong region**. (Current callers only send chat, so it is
latent, but the abstraction invites region violations.)
**Design lesson:** clamp delays to ≥ 1 tick; document that `runSyncForPlayer` tasks must be entity-safe only;
provide distinct region-correct primitives for location/teleport work.

### BUG-22 (low) — Bank `transfer` fires its event AFTER commit → uncancelable, inconsistent
**File:** `engine/EngineEconomy.java:348-355`. `FactionBankTransactionEvent(TRANSFER)` is dispatched only after the
money already moved and the transaction committed, and `isCancelled()` is never checked — unlike deposit (154) and
withdraw (235) which honor cancellation. A listener that vetoes transfers is ignored, and the audit listener records
a transfer that a policy plugin intended to block.
**Design lesson:** fire cancelable domain events *before* the mutation, on the correct thread, and honor the result uniformly.

---

## 3. Persistence: blocking I/O on the game thread & lost writes

### BUG-9 (high) — Synchronous DB I/O on the main/region thread, everywhere
**Files:** `command/FactionCommandExecutor.java:76` (commands run inline on the caller thread), and hot listeners:
`engine/EnginePlayerMove.java:41-93` (every chunk crossing: `players.find` + 2× `resolveTerritory` + `countByFactionId`
+ `findByFactionId` power sum), `engine/EngineProtection.java` (BlockBreak/Place 68-90, PvP on every hit 116-178,
BlockSpread on every fire tick 194-222, and **`isProtectedFromExplosion` 224-249 runs a `findByChunk` + `factions.find`
PER BLOCK** via `blockList().removeIf` 182/187), `engine/EngineChunkChange.claim` (invoked from `EngineAutoTerritory.onMove`
on every border crossing), `engine/EngineTeamChests` open/close.
None of these cache; all block the game thread on JDBC.
**Failure scenario:** a single TNT/creeper explosion of 300 blocks issues ~600 blocking DB queries on the region
thread mid-tick; two players sprinting across claim borders issue dozens of queries/sec each → TPS collapse / region
stall. On H2 (`pool size = 1`) a slow query on the async tick also blocks the game thread waiting for the one connection.
**Design lesson:** the board/claim map and faction/player lookups used by listeners must be served from an in-memory,
concurrency-safe cache hydrated at load and updated on mutation. The game thread must never issue a JDBC call.
Explosion handling must be O(chunks-touched) against the cache, not O(blocks) against the DB.

### BUG-10 (high) — Join inbox: delete-before-deliver + truncation loses notifications
**File:** `engine/EngineNotifications.java:76-87`. On join (async) it `findByPlayerId` (77), then
`deleteByPlayerId(ALL)` (80), then delivers only `subList(0, maxEntries)` (83-86). Entries beyond `maxEntries` are
**deleted but never delivered**. Delete precedes the scheduled delivery, so if the player quits or delivery fails,
even the kept entries are gone. And any inbox row written by another thread (e.g., tax notifier) *between* the find
and the delete is deleted without ever being in the read list → silently lost.
**Design lesson:** deliver-then-delete only the exact rows delivered (delete by id set), cap by *reading* a limit,
and never bulk-delete a superset of what you processed. Make read+delete atomic or idempotent.

### BUG-11 (high) — `/f power buy` charges money then ignores blocked/disabled result
**File:** `command/sub/power/CmdPowerBuy.java:96-108`. It `vaultEconomy.withdraw(player, cost)` (96), then
`powerService.apply(BUY, …)` (102) and **ignores the returned `Result`**, always printing "You purchased…". But
`apply` can no-op: `sourceEnabled(BUY)` requires `isPowerBuyEnabled() && isPowerSourceBuyEnabled()`
(`PowerServiceImpl.109`) while the command only checked `isPowerBuyEnabled()`; or the player is `power_frozen` and
`isPowerFreezeBlocksAutomatic()` → returns `FROZEN`; or `NO_CHANGE`. In all these the money is already gone and no
power is granted. Also, `actualAmount`/`cost` are computed from a power snapshot read at 71-84; a concurrent tick can
raise power so `apply` clamps to max and grants less than paid for (overcharge).
**Design lesson:** never take payment before the effect is confirmed. Reserve → apply → settle, refunding on any
non-applied result; compute cost from the same atomic operation that grants the power.

### BUG-12 (high) — Bank deposit/withdraw lose money on the DB failure path
**File:** `engine/EngineEconomy.java`. Deposit: `vaultEconomy.withdraw(player, finalAmount)` succeeds (166) then
`repos.factions().save(faction)` (175); if that save throws `StorageException`, the catch (187) only prints "internal
error" — the player's wallet was debited but the bank was never credited → money destroyed. Withdraw: debits bank +
saves (246-247), deposits to wallet (249); on failure it re-saves a rollback (252-253) that can *itself* throw,
leaving the bank debited with no wallet credit. No compensating Vault refund exists on either path.
**Design lesson:** wrap wallet + bank mutation so both commit or both roll back; on any DB failure after a Vault debit,
issue a compensating Vault credit. Treat the external economy as a participant needing explicit compensation, not
"best effort."

### BUG-23 (low) — `last_activity` is never written with a real timestamp
**File:** `data/model/PlayerModel.java` (`last_activity` column 23) — the only writers set it to `0L`
(`FactionServiceImpl.createFaction:194`, `PlayerModel` ctor 41). No login/heartbeat updates it. So
`PowerServiceImpl.isExcludedByInactivity` (161) and `EngineChunkChange.computeMaxLand` (297-299) — both gated on
`last > 0` — are permanently dead: inactive members are never excluded from power/land, defeating the F1 feature.
**Design lesson:** if a column drives logic, ensure exactly one place keeps it current (e.g., on join/quit), and add
a test asserting the gated behavior actually fires.

---

## 4. Lifecycle, leaks & shutdown

### BUG-3 (critical) — `/fa reload` leaks every listener → duplicated event handling
**Files:** `bootstrap/EnginesBootstrapComponent.java:126-135` (`stop` only cancels the power timer + tax scheduler),
`Bootstrap.java:118-176` (`reloadServices` stops & restarts services→engines→commands→hooks),
`command/sub/admin/CmdAdminReload.java:38` (invokes it).
`start()` registers ~10 Bukkit listeners (protection 60, auditLog 63, playerMove 74, chat 77, powerEngine events
via `EnginePower.start` 66, notifications 88, autoTerritory 92, teamChests 96, updateNotifier 110, gui 120) but
`stop()` calls **no** `HandlerList.unregister*`. Each `/fa reload` registers a fresh set while the old instances stay
registered and referenced by Bukkit's handler lists (also a heap leak of the old engines + their maps).
**Failure scenario:** after one reload every death applies power loss twice, every chat line gets two faction tags,
every claim fires two events / two audit rows, every move runs the territory query twice. N reloads → N+1× handling.
**Design lesson:** engine registration must be symmetric — track every registered listener/task and unregister it in
`stop()` (`HandlerList.unregisterAll(listener)` + cancel tasks), or rebuild the plugin on reload rather than re-running
`start()` over a live listener set. Idempotent, reversible lifecycle is mandatory.

### BUG-13 (high) — In-flight async DB writes are not drained before the pool closes
**Files:** `Bootstrap.java:94-109` (`stop` → components stop in reverse), `bootstrap/EnginesBootstrapComponent.stop`
(cancels the *repeating* timers but does nothing about already-dispatched `runAsync` tasks — e.g., death/quit power
writes queued at 96-110 of `EnginePower`), `bootstrap/InfrastructureBootstrapComponent.stop:56-62` →
`DatabaseManager.close:116-120` (just `HikariDataSource.close()`).
There is no barrier that waits for outstanding async work to finish before the pool is torn down. On shutdown a
death that occurred a moment earlier can run its `applyDeathPower` against a closing/closed pool → `StorageException`,
lost power write. Repeating-timer cancellation also does not await an in-progress tick.
**Failure scenario:** server stop during combat: the last few kills/deaths never persist their power changes; players
log back in with pre-combat power. On MySQL, half-applied multi-save operations (e.g., `setRelation` mirror) can be torn.
**Design lesson:** shut down in order — stop accepting new work, **quiesce/await** the async executor (bounded timeout),
flush any dirty in-memory state, *then* close the pool. Provide an explicit shutdown flush for anything with buffered writes.

### BUG-14 (medium) — `flyStateByPlayer` leaks per-player and is silently wiped on reload
**File:** `service/FactionServiceImpl.java:51,424`. `flyStateByPlayer.put(...)` on every `/f fly` toggle; there is
**no** `PlayerQuitEvent` removal anywhere (only `EnginePower.onQuit` — a no-op — and `EngineAutoTerritory.onQuit`
handle quit). The map grows for every distinct player until restart. It is also in-memory only and non-persistent, so
`/fa reload` (which constructs a **new** `FactionServiceImpl` in the services component) drops all fly state — players
keep flying client/permission-side with the service believing fly is off, or lose fly unexpectedly.
**Design lesson:** any per-player runtime map must be evicted on quit (and ideally bounded); state that must survive a
reload has to be persisted or preserved across the service rebuild.

### BUG-15 (medium) — Open team chests are never flushed on shutdown or reload
**File:** `engine/EngineTeamChests.java` (no `stop()`/flush; not touched by `EnginesBootstrapComponent.stop`).
Persistence relies solely on `InventoryCloseEvent`. On a hard shutdown/crash, or on Folia where close may not fire for
every viewer, in-progress edits are lost. On reload the old (now leaked, BUG-3) `EngineTeamChests` still owns the open
`sessions`; when the inventory later closes, the stale listener persists via a service wired to the pre-reload state.
**Design lesson:** on `stop()`, force-close and persist all open sessions; make container persistence not depend on an
event that may never arrive; route close-persist through the current (not a leaked) engine instance.

---

## 5. Cross-feature interaction notes (for the rewrite)

- **Power ↔ Bank ↔ Raidable share the `factions` row.** Because raidable is recomputed and full-row-saved on the async
  power timer, it structurally races bank writes (BUG-1/4). In the rewrite keep balances, computed flags, and config
  on separate tables/owners so no two subsystems ever save the same row image.
- **Move listeners are doubled.** `EnginePlayerMove.onMove` and `EngineAutoTerritory.onMove` both run at MONITOR on
  every `PlayerMoveEvent`, each doing independent DB work; combined with BUG-9 they double the per-move query load.
  Consolidate chunk-crossing detection into one cache-backed handler.
- **Audit / power-history / bank-tx writes ride the caller's thread.** `AuditServiceImpl.record` (22-39) and
  `EngineEconomy.recordTransaction` (364-383) synchronously insert on whatever thread fired the event — main thread
  for claim/relation/kick audits. These append-only writes should be queued to an async writer.
- **`transaction()` is not isolation.** It does not `SELECT … FOR UPDATE`; concurrent non-transactional saves still lose
  updates. The rewrite should not treat "wrapped in transaction()" as making an RMW safe.
- **H2 single-connection pool serializes but does not make Java-side RMW atomic.** Do not let the H2 config mask the
  races; MySQL (`pool size > 1`) exposes them fully.

---

## Appendix — load-bearing constants / formulas observed

| Concern | Value / formula | Source |
|--------|------------------|--------|
| Power tick period | `max(1, powerTickIntervalSeconds) * 20` ticks | `EnginePower.start:63-65` |
| Tax period | `max(1, taxIntervalHours) * 60*60*20` ticks | `EngineEconomy.startTaxScheduler:91-92` |
| Folia timer delay/period conversion | `ticks * 50` ms | `FoliaTaskScheduler:51-52` |
| Power clamp | `after = max(min, min(max, before + delta))`; ignore if `|after-before| < 1e-5` | `PowerServiceImpl:76-81` |
| Death loss w/ streak | `loss = baseLoss * pow(deathStreakMultiplier, streak)` (streak persisted incorrectly — BUG-8) | `EnginePower:165-180` |
| Kill scale | `gain *= clamp(victimPowerBefore/killerPower, minFactor, maxFactor)` | `EnginePower:216-228` |
| Event clamp | `delta = clamp(delta, -maxAbs, maxAbs)` when `maxAbs > 0` | `PowerServiceImpl:139-145` |
| Max land | `min(maxLand, (int)(totalPower / landPerPower))`, or `maxLand` if `landPerPower <= 0` | `EngineChunkChange.computeMaxLand:304-308` |
| Money rounding | `round(value*100)/100` | `EngineEconomy.roundMoney:462-464` |
| Invite TTL | active while `now <= createdAt + max(1, inviteTtlHours)*3600_000` | `InviteServiceImpl.isActive:223-226` |
| Death grace | no loss while `now - startedAt < powerGracePeriodSeconds*1000` | `EnginePower.applyDeathPower:142` |
| Team chest size | 54 slots | `EngineTeamChests.CHEST_SIZE:24` |
