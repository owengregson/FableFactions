# PvPIndex-Factions — Game-Logic & Math Bug Catalog

Adversarial audit of `command/`, `service/`, `engine/`, `integration/` in
`/Users/owengregson/Documents/pvpindex-factions`. Every entry below is a defect the
FableFactions clean-room rewrite must avoid **by design**. Severities: **critical** (dupe /
money creation / privesc / data corruption), **high** (protection bypass / feature broken /
fund loss), **medium** (exploitable balance / race), **low** (UX / latent).

Note on threading: the power tick, death handler, quit handler (`EnginePower`) and the bank
tax task (`EngineEconomy`) all run on **async** scheduler threads (`scheduleAsyncTimer` /
`runAsync`), while commands run on the **main** thread. Several "fixes" in the source assume
single-threaded access and are therefore wrong. This split is the root of the concurrency bugs.

---

## Summary table

| # | Severity | File | Bug |
|---|----------|------|-----|
| 1 | critical | `service/PowerServiceImpl.java:114-123` | DEATH/KILL ignore caller delta → death-streak (F2) & kill-scaling (F3) are dead code |
| 2 | high | `service/PowerServiceImpl.java:72,139-145` | Per-event clamp applied to ADMIN_* and BUY → admin set/add silently truncated, buy under-delivers |
| 3 | high | `engine/EngineEconomy.java:206-273` / `123-192` | Withdraw & deposit are non-atomic read-modify-write; race with async tax task → bank goes negative / money duplicated |
| 4 | high | `service/PowerServiceImpl.java:48-88` | `apply()` is a non-synchronized read-modify-write invoked from multiple async threads → lost power updates |
| 5 | high | `engine/EngineProtection.java:117-118` | PvP protection only checks `instanceof Player` damager → arrows/potions/tridents bypass safezone & PvP-flag |
| 6 | high | `engine/EngineProtection.java` (whole) | No handlers for pistons, liquid flow, buckets, ignite, containers/doors, hanging entities → wholesale griefing in claims |
| 7 | high | `util/MsgUtil.java:129-135` + `command/sub/CmdCreate.java:40` | Faction names/tags unsanitized + `replace()` doesn't escape MiniMessage → `<click:run_command>` / format injection |
| 8 | high | `engine/EngineTeamChests.java:39-70` | Team chest snapshots on open, persists whole inventory on close → concurrent viewers dupe items (last-write-wins) |
| 9 | high | `command/sub/power/CmdPowerBuy.java:80-97` | Player charged before `apply()`; freeze/disabled/clamp reduce or zero the grant with no refund → paid-for power lost |
| 10 | medium | `engine/EngineChunkChange.java:63-167` | Overclaim skips border check + buffer-zone config never enforced → teleport-claim anywhere in raidable enemy land |
| 11 | medium | `service/FactionServiceImpl.java:860-914` | Merge ignores member cap and land/power cap → both bypassed via merge |
| 12 | medium | `command/sub/warp/CmdWarpSet.java:61-79` + `command/sub/CmdSetHome.java:35` | Warp/home may be set at arbitrary coords / outside own claim → infiltration teleport into enemy bases |
| 13 | medium | `command/sub/CmdHome.java:42-48` / `command/sub/warp/CmdWarp.java` | `/f home` & `/f warp` have no combat/warmup/cooldown when Essentials absent → instant PvP escape |
| 14 | medium | `command/sub/warp/CmdWarp.java:118,127` | Warp cost withdrawn before validating world loaded / before teleport; no refund on failed teleport |
| 15 | medium | `command/sub/bank/CmdBankWithdraw.java` | Any member (lowest rank) can drain the entire bank; inconsistent with transfer (officer+) |
| 16 | medium | `command/sub/CmdFly.java:46-48` | Fly disabled mid-air with no fall-damage immunity; enable only checks current chunk (radius/threat mismatch) |
| 17 | medium | `engine/EngineChunkChange.java:65-66,215` | Per-chunk lock removed from map inside the critical section → striping broken if ever called off-thread |
| 18 | low | `service/FactionServiceImpl.java:1156-1165` | Promote/demote walks rank list by DB order, not sorted priority → skips/mis-orders custom ranks |
| 19 | low | `engine/EnginePlayerMove.java:42` | `event.getTo()` dereferenced without null check → NPE on null-destination move events |
| 20 | low | `command/sub/CmdKick.java:35` | `getPlayerExact` → offline members can never be kicked |
| 21 | low | `engine/EngineProtection.java:96-114` | WG-sync ally-unlock un-cancels at HIGHEST using one-directional relation → stale/asymmetric ALLY grants build |
| 22 | low | `engine/EnginePower.java:117-128` | Regen gates on `getMaxPower()` but service clamps to `getPowerMax()`; two divergent "max power" keys |

---

## Detailed findings

### 1. (critical) Death-streak and kill-scaling features are dead code — service overrides caller delta
`service/PowerServiceImpl.java:114-123`, interacting with `engine/EnginePower.java:165-232`.

`EnginePower.applyDeathPower` carefully computes an escalated loss
(`loss * pow(deathStreakMultiplier, streak)`, lines 165-180) and a scaled kill gain
(`gain * factor`, lines 217-228), then passes them as `baseDelta`. But
`PowerServiceImpl.sourceAmount()` **ignores `requested` for DEATH and KILL**:

```java
case DEATH -> -Math.abs(config.getPowerSourceDeathLossAmount());   // ignores baseDelta
case KILL  ->  Math.abs(config.getPowerSourceKillGainAmount());    // ignores baseDelta
```

`getPowerSourceDeathLossAmount()` defaults to `getPowerLossOnDeath()` (4.0) and
`getPowerSourceKillGainAmount()` defaults to `getPowerGainOnKill()` (2.0). So the actual power
change is always the flat config amount; the streak multiplier and kill-scale factor have **zero
effect**. Worse, the "Death streak ×N! You lost X power" message (EnginePower:191-199) reports
`Math.abs(deathResult.effectiveDelta())` — the un-escalated flat loss — while labeling it an
escalated streak, so the UI actively lies. The streak state (`setDeathStreak`/`setLastDeathAt`)
is still mutated, so it looks live in the DB but is inert.
Also there are **two independent config keys for the same quantity** (`power.loss-on-death` vs
`power.sources.death-loss.amount`); setting one and not the other silently diverges.

**Failure scenario:** Enable `death-streak.enabled`. Player dies 4× in the window; each death
still costs exactly 4.0 power, not 4·1.5³≈13.5, yet the chat says "×4". Feature advertised,
non-functional.

**Design lesson:** The power service must apply the *caller-supplied* effective delta. Compute
death loss / kill gain (including streak, scaling, multipliers) in **one** place and pass the
final number through; never re-derive it from config inside the service for event-driven sources.
Have exactly one source of truth per tunable.

### 2. (high) Per-event clamp is applied to ADMIN_* and BUY sources
`service/PowerServiceImpl.java:72` (`delta = applyEventClamp(delta)` runs for **all** sources)
and `:139-145`.

`applyEventClamp` clamps `|delta|` to `config.getPowerMaxChangePerEvent()` (default 0 = off).
It is applied unconditionally, including to `ADMIN_SET`, `ADMIN_ADD`, `ADMIN_REMOVE`,
`ADMIN_RESET`, and `BUY`.

**Failure scenario A (admin):** `max-change-per-event: 10`. Admin runs `/fa power set Bob 100`
while Bob has 0. `CmdAdminPowerSet` computes `delta = 100`; the clamp truncates it to 10, so Bob
ends at 10, not 100. `/fa power set` cannot set arbitrary values. `add`/`remove` likewise capped.
**Failure scenario B (buy):** see #9 — the buyer is charged for the full amount but only the
clamped fraction of power is applied.

**Design lesson:** The per-event volatility clamp is an anti-grief limiter for *automatic*
(death/kill/regen) sources only. Administrative and purchase operations must bypass it. Keep
min/max absolute bounds, but gate the per-event delta clamp on source type.

### 3. (high) Bank withdraw/deposit are non-atomic; race with the async tax task creates/destroys money
`engine/EngineEconomy.java:206-273` (withdraw), `:123-192` (deposit), `:86-99,390-434` (async tax).

Only `transfer()` uses `repos.factions().transaction(...)`. `withdraw()` and `deposit()` do a
plain read-modify-write: read `faction.getBank()`, check, `setBank(bank ± amount)`, `save`. The
tax task runs on an **async** timer and does the same read-modify-write on the same rows. There
is no locking, no optimistic version check, and the withdraw rollback path (`:252`) writes back a
**stale in-memory** `faction` object.

**Failure scenario:** Bank = 1000. Async tax reads 1000. Main thread withdraw of 500 reads 1000,
saves 500, credits player 500. Tax then computes `1000 - 50 = 950` and saves 950 over the top.
Net: bank shows 950, player pocketed 500 → 450 conjured. Symmetric interleavings let the bank go
**negative** (both a withdraw and the tax pass the `bank < amount` check on the same stale read).
Deposit has the same lost-update window, plus a separate money-loss bug: it calls
`vaultEconomy.withdraw(player)` (:166) *before* the DB `save`; if the save throws
`StorageException`, the player's wallet is debited and the bank is never credited — money
vanishes with no rollback.

**Design lesson:** All bank mutations (deposit, withdraw, transfer, tax) must be atomic against
each other — a single serialized transaction / conditional update per faction, or a per-faction
lock shared by the tax task. Order external (Vault) and internal (DB) effects so a failure of
either rolls back the other. Never persist a stale snapshot on rollback.

### 4. (high) `PowerService.apply()` is an unsynchronized read-modify-write called from multiple async threads
`service/PowerServiceImpl.java:48-88`; async callers at `engine/EnginePower.java:96,109` (quit/death
via `runAsync`) and `:65` (regen timer via `scheduleAsyncTimer`), plus main-thread `CmdPowerBuy`.

`apply()` reads `model.getPower()`, computes `after`, `setPower`, `save`, `record`. No lock.
Concurrent events for the same player (regen tick + death handler, or two deaths) interleave and
clobber each other's writes; the power-history log records deltas that don't reconcile with the
stored value.

**Failure scenario:** Player dies (async death loss reads power=20) at the same tick the regen
timer processes them (reads 20, +2 → saves 22). Death saves 16. Depending on order the loss or
the gain is silently dropped, and `power_history` shows a delta that never happened.

**Design lesson:** Serialize power mutations per player (per-UUID lock or single-threaded power
executor) and make read→clamp→write→history atomic. Prefer computing deltas against a freshly
re-read row inside the lock.

### 5. (high) Projectile / non-player damage bypasses PvP protection
`engine/EngineProtection.java:117-124`.

```java
if (!(event.getDamager() instanceof Player attacker)
        || !(event.getEntity() instanceof Player victim)) {
    return;   // <-- arrow, snowball, potion, trident, TNT, wolf, etc. all return here
}
```

For a bow/crossbow/trident/splash-potion the `getDamager()` is the *projectile*, not the shooter,
so the method returns before the safezone / PvP-flag checks. Same gap voids the friendly-fire
check (`:126-145`).

**Failure scenario:** Stand in a Safezone (or a faction with `pvp` flag off). An enemy shoots you
with a bow from outside — full damage; melee would have been cancelled. Ranged combat completely
ignores territory PvP rules, including in the server spawn safezone.

**Design lesson:** Resolve the true attacker: if `damager` is a `Projectile`, use
`((Projectile) damager).getShooter()` (and handle `TNTPrimed.getSource`, `AreaEffectCloud`,
tamed-pet owners). Apply the same territory/relation/flag logic to melee and ranged uniformly.

### 6. (high) Entire classes of block/entity protection are unimplemented
`engine/EngineProtection.java` handles only `BlockBreak`, `BlockPlace`, `EntityDamageByEntity`
(player-only), `EntityExplode`/`BlockExplode`, and `BlockSpread` (fire only). A repo-wide search
finds **no** handler anywhere for: `PlayerInteractEvent` (chests, furnaces, doors, buttons,
levers, pressure plates, item frames), `BlockPistonExtendEvent`/`BlockPistonRetractEvent`,
`BlockFromToEvent` (water/lava flow), `PlayerBucketEmptyEvent`/`BucketFill`, `BlockIgniteEvent`
(flint & steel), or `HangingBreakEvent`/`HangingBreakByEntity`. (`grep` for all of these returns
only `EngineProtection.java` itself.)

**Failure scenarios in another faction's claim:** open and empty their chests/furnaces
(`PlayerInteract` → no container protection); flood their base by placing water/lava with a
bucket (`PlayerBucketEmpty` does **not** fire `BlockPlaceEvent`, so the build guard never sees
it); griefer from wilderness runs a piston to push/pull their blocks across the border; let
water/lava flow across the border; set their wooden builds on fire with flint & steel; break
their item frames and paintings. All bypass the plugin.

**Design lesson:** Protection must be event-complete. Enumerate every griefing vector — container
& block interaction, buckets, ignite, piston (validate *both* source and destination chunk
ownership), liquid `BlockFromTo` across the border, hanging entities, vehicles, armor stands,
farmland trample, and explosion damage to entities — and route them all through one
`canModify(player/source-faction, targetChunk)` decision. Default deny in claimed land.

### 7. (high) MiniMessage/format & command injection via faction names, tags, MOTD, prefixes, warp names
`util/MsgUtil.java:129-135` (`replace()` does raw `String.replace`, no escaping) and `:77-83`
(`send()` deserializes the *entire* resulting string as MiniMessage). Names are only
length-validated: `command/sub/CmdCreate.java:40` checks `3..32` chars and **nothing else**;
`FactionServiceImpl.renameFaction/setFactionDescription/setFactionMotd` store the raw string.

User-controlled strings (faction name, description, MOTD, role prefix, warp name, player title)
are substituted into MiniMessage templates that are then parsed and shown to *other* players
(relation announcements, territory-enter titles `EnginePlayerMove:63-88`, invite/merge notices,
GUI/info headers). Because `replace()` doesn't escape `<`/`>`, any tag the attacker puts in the
name is interpreted.

**Failure scenario:** Create a faction named `<click:run_command:'/f leave'>hi` (or worse, a
target admins might click). Every viewer who receives a message containing `{faction}` gets a live
clickable that runs the embedded command **as themselves**. Even without clicks, `<newline>` +
color tags let an attacker forge fake system lines, rainbow spam, or oversized hovers. On Spigot
the legacy path (`LegacyOps.send`) serializes to Bungee components and preserves the click too.

**Design lesson:** Treat all user text as data. Validate names against a strict allowlist
(e.g. `[A-Za-z0-9_]{3,16}`, reject `<`, `>`, `&`, `§`, control chars). When interpolating *any*
user string into a MiniMessage template, wrap it as a literal (Adventure `Component.text` /
placeholder `TagResolver`, or escape via `MiniMessage.escapeTags`) — never string-concatenate raw
input into a to-be-parsed string. Also strip legacy `§`/`&` codes.

### 8. (high) Team-chest item duplication via concurrent access + persist-on-close
`engine/EngineTeamChests.java:39-70`.

`openChest` builds a **fresh** Bukkit inventory from a service snapshot and stores a per-player
session. Contents are written back only in `onInventoryClose` (`setChestContents` of that
player's whole inventory). Nothing prevents two members opening the *same* faction chest at once;
each gets a private copy of the same snapshot, and close order decides the final state
(last-write-wins).

**Failure scenario:** Chest holds 64 diamonds. A and B both `/f chest open vault` simultaneously.
Both see 64 diamonds. A takes all, closes → persists 64 in A's inventory removed. B still holds
the snapshot with 64, takes all, closes → persists B's copy over A's. Both walked away with 64;
128 diamonds now exist. (A server crash while open loses everything deposited since open.)

**Design lesson:** Back a faction chest with a **single shared live inventory** instance held by
the plugin (one per faction+chest), so all viewers mutate the same object and Bukkit's own
concurrency rules apply; persist on mutation/close of the shared inventory. Alternatively lock so
only one viewer at a time. Never snapshot-copy then blind-overwrite on close.

### 9. (high) `/f power buy` charges before applying; freeze/disable/clamp lose the buyer's money
`command/sub/power/CmdPowerBuy.java:80-97`.

Flow: compute `actualAmount = min(amount, maxPower - power)`, `cost = actualAmount * costPerPoint`,
`vaultEconomy.withdraw(player, cost)` (:91), **then** `powerService.apply(BUY, actualAmount)`
(:96). The result of `apply()` is never inspected. `apply()` can grant less than `actualAmount`
or nothing: (a) the per-event clamp (#2) caps the delta; (b) if the player is frozen and
`freeze.blocks-automatic` is true, BUY returns `FROZEN` with delta 0; (c) if
`power.sources.buy.enabled` is false (the command only checks `power.buy.enabled`), BUY returns
`SOURCE_DISABLED` with delta 0. In every case the wallet was already debited `cost`.

**Failure scenario:** `max-change-per-event: 3`, buy 5 power at 100/pt → charged 500, granted 3,
lost 200. Or: frozen player buys → charged, gets nothing.

**Design lesson:** Validate all preconditions (enabled sources, freeze, clamp headroom) and
compute the *actually applicable* amount **before** charging; charge exactly for
`result.effectiveDelta()`, or apply-then-charge and refund on any shortfall. Purchases must be
all-or-nothing.

### 10. (medium) Overclaim skips border adjacency + buffer-zone config is never enforced
`engine/EngineChunkChange.java:167` (`if (victimFaction == null && !isValidBorder(...))` — border
check skipped whenever overclaiming) and `config/FactionsConfig.java:269` (`getLandBufferZone`,
default 0) which is referenced **nowhere** in the engine (repo-wide grep: no non-config hits).

Two issues: (a) When overclaiming a raidable enemy, the connectivity/adjacency requirement is
bypassed, so a faction can seize *any* single enemy chunk deep inside their territory (a
checkerboard of holes) rather than eroding from a shared border. (b) There is no minimum-distance
buffer between different factions at all — the documented `land.buffer-zone` knob does nothing,
and `claim fill/circle/square` (`CmdClaim.java:73-89`) claim straight up to (and overclaim into)
a neighbor with no gap.

**Failure scenario:** Raidable enemy with 40 scattered claims: overclaim their spawn chunk and
home chunk directly, ignoring adjacency, without ever bordering them. Admins who set
`buffer-zone: 2` expecting a no-claim gap get none.

**Design lesson:** Decide the design explicitly and implement it consistently across single/
fill/circle/square/overclaim/auto paths: if a buffer zone is offered, enforce it (reject claims
within N chunks of a *different* faction's land); if overclaim should require border contact,
keep the adjacency check on the overclaim path too. Don't ship dead balance config.

### 11. (medium) Faction merge bypasses member cap and land/power cap
`service/FactionServiceImpl.java:860-914`.

`mergeFaction` migrates *all* sender members into the target (`:900-905`) and transfers *all*
sender claims (`:888-891`) with **no** check of `config.getMaxMembers()` or the target's
power-derived max land. `joinFaction` (`:952-957`) *does* enforce the member cap, so merge is a
clean bypass of both.

**Failure scenario:** Member cap 10. Two 8-person factions merge → 16-person faction. Two factions
each at their land cap merge → a faction holding 2× its allowed land (only later flagged raidable
by the periodic sweep, but the land is already theirs).

**Design lesson:** Merge must respect the same invariants as join/claim, or explicitly and
loudly document/limit the overflow (reject the merge if it would exceed caps, or trim). Route
membership and land changes through the same guarded service methods.

### 12. (medium) Warps/homes can be placed at arbitrary coordinates and outside owned territory
`command/sub/warp/CmdWarpSet.java:61-79` and `command/sub/CmdSetHome.java:35`.

`CmdWarpSet.resolveLocation` accepts explicit `x y z world` args, so an officer can create a warp
at coordinates they have never visited, in any world. The only gate is
`territoryGuard.canModifyTerritory(target)` — a **WorldGuard region** check, not a
faction-ownership check. `CmdSetHome` similarly only checks the WG guard, not that the location
is inside the faction's own claim.

**Failure scenario:** Scout an enemy base, note coords, `/f warp set raid <x> <y> <z> world`
(never entering their claim if no WG region blocks it), then `/f warp raid` to teleport the squad
inside — instant siege insertion. Same with `/f sethome` standing at a border.

**Design lesson:** Restrict warp/home placement to the faction's own claimed territory (config-
gated), reject coordinate-based placement or require line-of-presence, and re-validate ownership
at *use* time (a warp/home whose chunk was unclaimed/overclaimed should fail or relocate).

### 13. (medium) `/f home` and `/f warp` have no combat/warmup/cooldown fallback
`command/sub/CmdHome.java:42-48`, `command/sub/warp/CmdWarp.java:126-139`.

Both delegate teleport to `EssentialsInterop.teleport`; if it returns false (Essentials absent),
they immediately call `player.teleport(dest)` with **no** warmup, cooldown, movement-cancel, or
combat-tag check. There is no combat-tag system anywhere in the plugin (repo-wide grep: no
`combatTag`/warmup). Even with Essentials, warmup/combat rules are outsourced and not guaranteed.

**Failure scenario:** Losing a fight in your own claim, spam `/f home` → instant escape mid-combat
on any server without Essentials warmups. No cooldown means repeat abuse.

**Design lesson:** Implement first-party warmup (cancel on move/damage), cooldown, and a
combat-tag that blocks `/f home` and `/f warp` for N seconds after PvP. Don't depend on an
optional integration for a core balance rule.

### 14. (medium) Warp cost charged before destination validated and before teleport; no refund
`command/sub/warp/CmdWarp.java:118` (charge) vs `:127` (world-loaded check) and `:131-137`
(teleport). `vaultEconomy.withdraw` return value is ignored.

Money is withdrawn, *then* `dest.getWorld() == null` is checked and the teleport attempted. If the
world isn't loaded, or the Essentials-driven teleport later fails/cancels (warmup interrupted),
the player is out the fee with no teleport and no refund.

**Failure scenario:** Warp world unloaded → charged, "Warp world not loaded", money gone. Or
warmup cancelled by taking damage → charged, no teleport.

**Design lesson:** Validate destination and reserve/charge only on confirmed successful teleport
(charge in the success callback), or refund on any failure path. Check `withdraw()`'s return.

### 15. (medium) Any member can drain the faction bank
`command/sub/bank/CmdBankWithdraw.java` — only `requireFaction` + permission `factions.cmd.bank`;
no rank guard. Compare `CmdBankTransfer` which requires `requireOfficerOrAbove`, and the general
pattern where destructive faction actions are officer/owner-gated.

**Failure scenario:** A freshly recruited lowest-rank member runs `/f bank withdraw <all>` and
empties the treasury the moment they're invited.

**Design lesson:** Gate withdrawals (and transfers) behind a configurable minimum rank / role
permission; make deposit open but withdrawal privileged by default. Model per-role permissions
rather than a single `bank` node.

### 16. (medium) Fly toggle: no fall-damage protection on disable; enable check is single-chunk only
`command/sub/CmdFly.java:38,46-48`.

Disabling fly does `player.setFlying(false)` with no fall-damage grant → the player plummets and
takes fall damage the instant fly ends (or when they leave territory, if any mover disables it).
The enable-time territory check (`inOwnTerritory`) inspects only the *current chunk*; there is no
threat/enemy-proximity radius, and there is no mover that disables fly when the player walks out
of their claim or an enemy approaches (no such listener exists) — so fly effectively persists
into enemy land once enabled. Fly state is stored in an in-memory map
(`FactionServiceImpl.flyStateByPlayer`) and lost on restart, desyncing from `setAllowFlight`.

**Failure scenario:** Enable fly on your border chunk, fly across the map (no per-move revocation),
then when it is disabled you take fall damage — or you exploit that fly was never revoked over
enemy territory.

**Design lesson:** On any fly disable, grant brief fall-damage immunity (cancel the next fall
`EntityDamageEvent` / set `fallDistance = 0`). Continuously re-evaluate fly eligibility on move
against a *threat radius* consistent with the claim granularity, and persist fly state.

### 17. (medium/low) Per-chunk claim lock is removed inside its own critical section
`engine/EngineChunkChange.java:65-66` (`computeIfAbsent`) and `:215` (`claimLocks.remove` in the
`finally`, still inside `synchronized(lock)`).

Removing the lock object from the map while holding it means a thread that calls `computeIfAbsent`
*after* the removal mints a **new** lock and can enter the critical section concurrently with a
thread still holding the old lock reference. The "concurrent-claim race" fix (#4 in the class
Javadoc) is therefore broken. In practice claim is currently only called on the main thread
(commands + `EngineAutoTerritory`), so it doesn't bite today — but it is a latent double-claim /
double-overclaim race the moment any async path claims.

**Design lesson:** Use stable lock striping (fixed array of locks keyed by chunk hash) or a
`ConcurrentHashMap` you never remove from mid-use; or better, enforce the invariant in the DB
(unique constraint on `world,x,z` + conditional insert) so correctness doesn't depend on JVM locks.

### 18. (low) Promote/demote uses DB list order instead of sorted priority
`service/FactionServiceImpl.java:1156-1165`.

`ranks = repos.ranks().findByFactionId(...)` then `indexOf(target)` and `idx-1`/`idx+1` to pick the
next rank — assuming the list is priority-ordered. `findByFactionId` gives no ordering guarantee,
and custom roles have arbitrary priorities, so "the next rank" can be a lower-priority or skipped
rank depending on storage order.

**Failure scenario:** Faction with custom roles inserted out of priority order; a promote moves a
member to a *lower* rank or skips a tier.

**Design lesson:** Sort ranks by `priority` explicitly and step to the adjacent priority; don't
rely on repository iteration order for hierarchy math.

### 19. (low) `EnginePlayerMove` dereferences `event.getTo()` without a null check
`engine/EnginePlayerMove.java:42`.

`event.getFrom().getChunk().equals(event.getTo().getChunk())` — `getTo()` can be null on some
move events; `EngineAutoTerritory` guards it (`:52`) but this listener does not → `NullPointer`
spam in the log and a dropped territory notice.

**Design lesson:** Null-check `getTo()` first in every move handler.

### 20. (low) Offline members cannot be kicked
`command/sub/CmdKick.java:35` uses `Bukkit.getPlayerExact` (online only). `FactionService.kickMember`
supports UUIDs, so the limitation is purely the command's online-only resolution.

**Design lesson:** Resolve targets by offline UUID (name→OfflinePlayer) so leaders can prune
inactive members; guard against resolving the wrong player for unseen names.

### 21. (low) WG-sync ally-unlock trusts one-directional ALLY
`engine/EngineProtection.java:96-114,273-312`. The HIGHEST-priority un-cancel grants build access
if `getRelation(playerFaction, claimOwner) == ALLY`, read from the *builder's* relation JSON only.
ALLY is normally mutual, but stale/asymmetric data (or the enemy/neutral-only mirroring in
`setRelation`) can leave a one-sided ALLY that still unlocks building in someone's claim.

**Design lesson:** Require *mutual* ALLY (check both factions' maps) before granting cross-faction
build, and keep relations authoritative/symmetric in storage.

### 22. (low) Divergent "max power" config keys
`engine/EnginePower.java:119,124` gate regen on `config.getMaxPower()` while
`service/PowerServiceImpl.java:74-76` clamps to `config.getPowerMax()` (default equal, but
`power.constraints.max-power` can override independently). `CmdPowerBuy` uses `getMaxPower()` for
its cap too. If an admin sets one key, regen/buy caps and the hard clamp disagree (players stuck
below or churning apply-calls that clamp to no change).

**Design lesson:** One canonical max-power value consumed everywhere; derive any alias from it.

---

## Cross-cutting design mandates for the rewrite

1. **One source of truth per tunable.** The DEATH/KILL/buy/max-power bugs all stem from duplicate
   config keys and delta being recomputed in the wrong layer. Compute an effect once, pass it
   through, and inspect the returned effective delta.
2. **Atomicity for all economy & power mutations.** Serialize per-faction (bank) and per-player
   (power) writes; deposit/withdraw/transfer/tax and every power source share the same lock or
   transaction. Order Vault and DB effects so either failing rolls back the other.
3. **Charge only for what is delivered.** Purchases and warp fees must be all-or-nothing with
   refund-on-failure, computed from the actual applied result, after all gating checks.
4. **Event-complete protection.** Enumerate every grief vector (interaction/containers, buckets,
   ignite, pistons validating both ends, liquid `BlockFromTo`, hanging entities, projectiles via
   shooter resolution, explosion entity damage) through one `canModify` decision; default-deny in
   claims.
5. **Treat all user text as data.** Strict name validation + escape/placeholder every
   interpolation into MiniMessage; never concatenate raw input into a parsed string.
6. **First-party combat/warmup/cooldown** for `/f home` and `/f warp`; don't rely on Essentials.
   Restrict warp/home placement and re-validate at use.
7. **Uniform invariants across paths.** Member cap, land cap, buffer zone, adjacency, and rank
   hierarchy must hold on every path (single/fill/circle/square/overclaim/auto/merge/join), sorted
   by priority, not list order.
