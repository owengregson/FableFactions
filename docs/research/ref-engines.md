# FableFactions Spec — Engines, Bukkit Events & Schedulers

Clean-room behavioral spec for the `engine`, `event`, and `scheduler` packages of `the reference implementation`.
An implementer who has never seen the source must be able to reproduce every behavior below exactly.

Package roots (original): `com.reference.factions.{engine,event,scheduler}`.

---

## 0. Shared vocabulary / cross-cutting facts

### 0.1 Special faction IDs (constants on `FactionModel`)
| Constant | Value (string) |
|---|---|
| `WILDERNESS_ID` | `"WILDERNESS"` |
| `SAFEZONE_ID` | `"SAFEZONE"` |
| `WARZONE_ID` | `"WARZONE"` |

`FactionModel.isNormal()` = true only for real player factions (not wilderness/safezone/warzone).
`PlayerModel.isInFaction()` = player has a non-null/non-wilderness faction id.
`PlayerModel.isOverriding()` = admin "override" toggle (bypasses build protection).

### 0.2 `Relation` enum (ordinal order, lowest→highest hostility)
`MEMBER(0) < ALLY(1) < TRUCE(2) < NEUTRAL(3) < ENEMY(4)`.
Helpers: `isAtLeast(o)` = `ordinal<=o.ordinal`; `isAtMost(o)` = `ordinal>=o.ordinal`; `isFriendly()` = MEMBER||ALLY; `isNeutralOrBetter()` = `!=ENEMY`; `isHostile()` = ENEMY.
`displayName()`: Member/Ally/Truce/Neutral/Enemy. `colorTag()`: MEMBER `<green>`, ALLY `<aqua>`, TRUCE `<yellow>`, NEUTRAL `<gray>`, ENEMY `<red>`.

### 0.3 `FactionFlag` enum (id, display, default)
| Flag | id | default |
|---|---|---|
| `PVP` | `pvp` | `true` |
| `FRIENDLY_FIRE` | `friendly-fire` | (false) |
| `EXPLOSIONS` | `explosions` | (false) |
| `FIRE_SPREAD` | `fire-spread` | (false) |
| `OPEN` | `open` | `false` |

`FlagService.getFlag(faction, flag)` returns the effective boolean for that faction. Only PVP/FRIENDLY_FIRE/EXPLOSIONS/FIRE_SPREAD are consulted by the protection engine. `OPEN` is used elsewhere (join logic), not in these packages.

### 0.4 Relation lookup (lightweight JSON parse — replicate exactly)
Relations are stored on `FactionModel` as a JSON string `getRelationsJson()` shaped `{"<factionId>":"ALLY",...}`.
Two engines (`EngineProtection`, `EngineChunkChange`) parse it **without Gson** to avoid a dependency inside the engine:
```
token      = "\"" + otherFactionId + "\":\""
start      = json.indexOf(token)      // <0 → NEUTRAL
valueStart = start + token.length()
valueEnd   = json.indexOf('"', valueStart)   // <0 → NEUTRAL
Relation.valueOf(json.substring(valueStart, valueEnd))   // bad value → NEUTRAL
```
`null` json → NEUTRAL. In `EngineChunkChange.getRelation`, SAFEZONE/WARZONE ids → NEUTRAL up front, and if the other faction row does not exist → NEUTRAL. `EngineProtection.getRelation` is the same parse but without the safezone/warzone/missing-row guards (callers already filtered those).

### 0.5 Messaging (`MsgUtil`)
- `MsgUtil.send(sender, miniMessageString)` — sends a MiniMessage-formatted string.
- `MsgUtil.sendKey(player, key, defaultMiniMessage[, k, v, ...])` — resolves the message `key` from the message config (falling back to the default string), does placeholder replacement, sends.
- `MsgUtil.message([player,] key, default)` — resolve raw string only.
- `MsgUtil.replace(str, k, v, ...)` — replaces `{k}` tokens.
- `MsgUtil.factionInfoHover(baseComponent, factionName, ...hoverLines)` — builds a message with a hover tooltip (used by move enter-claimed notice).
- `MsgUtil.inviteListEntry(player, factionName, inviterName)`; `MsgUtil.stripTags(str)`.
Placeholders use `{name}` syntax. All colors are MiniMessage tags (`<red>`, `<gray>`, etc.).

### 0.6 Data access is synchronous & throws `StorageException`
All `repos.*` calls (`players()`, `factions()`, `board()`, `inbox()`, `bankTransactions()`, `powerHistory()`) are backed by Jaloquent and can throw `StorageException`; every engine wraps them and logs. Board queries used here:
`board().findByChunk(world,x,z) → Optional<BoardEntry>`, `board().countByFactionId(id) → int`, `board().claimChunk(world,x,z,factionId)`, `board().unclaimChunk(world,x,z)`.
`players().find(uuidString) → Optional`, `players().findOrCreate(uuidString)`, `players().findByFactionId(id) → List`, `players().findAll()`, `players().save(pm)`.

---

## 1. `scheduler` package — platform-neutral scheduling

### 1.1 `TaskScheduler` (interface)
| Method | Contract |
|---|---|
| `runAsync(Runnable)` | run off-thread ASAP |
| `runSync(Runnable)` | run on primary/global-region thread ASAP |
| `runSyncForPlayer(Player, Runnable)` | run on the thread owning that player's region (Folia entity scheduler); Bukkit falls back to main thread |
| `runSyncLater(Runnable, long delayTicks)` | run on primary thread after delay (20 ticks = 1s) |
| `scheduleAsyncTimer(Runnable, long delayTicks, long periodTicks)` | repeating async task; returns `CancelableTask` |

### 1.2 `CancelableTask` (interface)
Single method `cancel()`.

### 1.3 `PlatformDetector` (final, static)
- Static initializer: `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` succeeds → `FOLIA=true`; `ClassNotFoundException` → false.
- `static boolean isFolia()`.
- Purpose: caller chooses `FoliaTaskScheduler` vs `BukkitTaskScheduler` at startup. `FoliaTaskScheduler` must only be **instantiated** when `isFolia()` (Java lazy class loading keeps Folia-only classes from loading on Paper/Spigot).

### 1.4 `BukkitTaskScheduler` (Paper/Spigot)
Wraps `Bukkit.getScheduler()`:
- `runAsync` → `runTaskAsynchronously(plugin, task)`
- `runSync` → `runTask(plugin, task)`
- `runSyncForPlayer(player, task)` → `runTask(plugin, task)` (ignores player)
- `runSyncLater` → `runTaskLater(plugin, task, delayTicks)`
- `scheduleAsyncTimer` → `runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks)`; returns `bukkitTask::cancel`.

### 1.5 `FoliaTaskScheduler` (Folia)
Uses region schedulers; **ticks are converted to milliseconds at 50 ms/tick**:
- `runAsync` → `getAsyncScheduler().runNow(plugin, s->task.run())`
- `runSync` → `getGlobalRegionScheduler().run(plugin, ...)`
- `runSyncForPlayer(player, task)` → `player.getScheduler().run(plugin, s->task.run(), null)`; **if that returns null** (player left before scheduling), fall back to `getGlobalRegionScheduler().run(...)`.
- `runSyncLater(task, delayTicks)` → `getGlobalRegionScheduler().runDelayed(plugin, ..., delayTicks)` (delay still in ticks here).
- `scheduleAsyncTimer(task, delayTicks, periodTicks)` → `delayMs=delayTicks*50`, `periodMs=periodTicks*50`, `getAsyncScheduler().runAtFixedRate(plugin, ..., delayMs, periodMs, MILLISECONDS)`; returns `scheduled::cancel`.

---

## 2. `event` package — custom Bukkit events

All extend `org.bukkit.event.Event`, use a static `HandlerList HANDLERS` with `getHandlers()`/static `getHandlerList()`. Fired synchronously via `Bukkit.getPluginManager().callEvent(...)`. Cancellable events check `isCancelled()` right after firing and abort if true.

| Event | Cancellable | Fields (getters) | Notes |
|---|---|---|---|
| `FactionCreateEvent` | ✅ | `FactionModel getFaction()`, `UUID getCreatorUUID()` | Fired on faction creation (in service layer). |
| `FactionDisbandEvent` | ✅ | `FactionModel getFaction()` | |
| `FactionJoinEvent` | ✅ | `FactionModel getFaction()`, `UUID getPlayerUUID()` | |
| `FactionLeaveEvent` | ❌ (not cancellable) | `FactionModel getFaction()`, `UUID getPlayerUUID()` | Fired on leave **or kick**. |
| `FactionChunkClaimEvent` | ✅ | `getFaction()`, `getPlayerUUID()`, `String getWorldName()`, `int getChunkX()`, `int getChunkZ()`, `Optional<FactionModel> getOverclaimedFromFaction()` | Two ctors: with/without `overclaimedFromFaction`. `getOverclaimedFromFaction()` = `Optional.ofNullable(...)`, present only for overclaims. |
| `FactionChunkUnclaimEvent` | ✅ | `getFaction()`, `getPlayerUUID()`, `getWorldName()`, `getChunkX()`, `getChunkZ()` | |
| `FactionBankTransactionEvent` | ✅ | `getFaction()`, `UUID getPlayerUUID()`, `Type getType()`, `double getAmount()`, **mutable** `setAmount(double)` | `enum Type { DEPOSIT, WITHDRAW, TRANSFER }`. Handlers may rewrite the amount before commit (deposit/withdraw read `event.getAmount()` back after firing). |

`FactionCreateEvent`, `FactionDisbandEvent`, `FactionJoinEvent`, `FactionLeaveEvent` are defined here but **fired by the service layer**, outside these three packages.

---

## 3. `engine` package

The engine classes are Bukkit `Listener`s and/or scheduled `Runnable`s. Each has a `register(plugin)` (or `start(plugin)`) that calls `Bukkit.getPluginManager().registerEvents(this, plugin)` and/or schedules timers. Below, each engine documents: events handled, priority, `ignoreCancelled`, exact gating logic, messages, edge cases.

---

### 3.1 `EngineProtection` (Listener) — territory build/PvP/explosion/fire protection

Constructed with `(Repositories, FactionsConfig, FlagService, TerritoryGuard, Logger)`.
`wgSync = territoryGuard != null && territoryGuard.syncsBuildProtection()` — cached at construction. This flag switches build handlers between "DB mode" and "WorldGuard-sync mode".

#### 3.1.1 Block break / place (build protection)

| Handler | Event | Priority | ignoreCancelled |
|---|---|---|---|
| `onBlockBreak` | `BlockBreakEvent` | HIGH | true |
| `onBlockPlace` | `BlockPlaceEvent` | HIGH | true |

Logic (identical for break/place):
```
allowed = wgSync ? canModifyWgSync(player, block) : canModify(player, block.getChunk())
if (!allowed) { event.setCancelled(true); send deny message }
```
Deny messages:
- break → key `custom.protection.no-break`, default `<red>You may not break blocks in this territory.`
- place → key `custom.protection.no-place`, default `<red>You may not place blocks in this territory.`

**`canModify(player, chunk)` — DB build gating (the core rule):**
1. `player.hasPermission("factions.bypass")` → **allow**.
2. Resolve `PlayerModel pm = players().find(uuid)`. If present and `pm.isOverriding()` → **allow**.
3. `entry = board().findByChunk(world,x,z)`. Empty (wilderness) → **allow**.
4. `factionId = entry.factionId`:
   - `SAFEZONE_ID` **and** `config.isSafeZoneEnabled()` → **deny** (no building in safezone).
   - `WARZONE_ID` **and** `config.isWarZoneEnabled()` → **deny** (no building in warzone).
5. If `pm` empty or `!pm.isInFaction()` → **deny** (non-member in claimed land).
6. If `factionId == pm.getFactionId()` → **allow** (own territory).
7. Else look up both factions; if either missing → **deny**. Compute `rel = getRelation(playerFaction, factionId)`. **allow iff `rel == ALLY`** (allies may build; enemies/truce/neutral may not).
8. `StorageException` → **deny** (fail-safe).

**Zone summary (from class Javadoc):** Safezone: no build by non-members, no pvp. Warzone: pvp allowed, no build. Enemy territory: no build. Own/ally territory: full build access.

**`canModifyWgSync(player, block)` — WorldGuard-sync fast path:**
1. `hasPermission("factions.bypass")` → allow.
2. `territoryGuard.isFactionRegion(block.getLocation())` → allow (WG already enforced membership at NORMAL priority before this HIGH handler runs).
3. Otherwise (wilderness / un-synced) → fall through to `canModify(player, block.getChunk())`.

#### 3.1.2 Ally unlock (WG-sync only) — un-cancel WG denials for allies/override admins

| Handler | Event | Priority | ignoreCancelled |
|---|---|---|---|
| `onBlockBreakAllyUnlock` | `BlockBreakEvent` | HIGHEST | **false** |
| `onBlockPlaceAllyUnlock` | `BlockPlaceEvent` | HIGHEST | **false** |

Runs after WG (NORMAL) and after our own HIGH handler. Guard: `if (!wgSync || !event.isCancelled()) return;`. Then `if (tryUnlockForAlliedPlayer(player, block)) event.setCancelled(false);`.

**`tryUnlockForAlliedPlayer(player, block)`:**
1. If `!territoryGuard.isFactionRegion(block.getLocation())` → false (let WG decision stand).
2. `hasPermission("factions.bypass")` → true.
3. `pm = players().find(uuid)`; if present and `pm.isOverriding()` → true.
4. If `pm` empty or `!isInFaction()` → false.
5. `entry = board().findByChunk(...)`; empty → false.
6. `factionId` is SAFEZONE/WARZONE → false.
7. `factionId == pm.factionId` → true (own faction; normally unreachable here).
8. Look up `playerFaction`; empty → false. Return `getRelation(playerFaction, factionId) == ALLY`.
9. `StorageException` → false.

#### 3.1.3 PvP protection

| Handler | Event | Priority | ignoreCancelled |
|---|---|---|---|
| `onPvp` | `EntityDamageByEntityEvent` | HIGH | true |

1. Require damager instanceof `Player attacker` **and** entity instanceof `Player victim`; else return (only player-vs-player).
2. `attacker.hasPermission("factions.bypass")` → return (allow).
3. **Friendly-fire check (applies everywhere, even wilderness)**, only if `flagService != null`: look up attacker & victim `PlayerModel`. If both present, attacker `isInFaction()`, and `attacker.factionId == victim.factionId` (same faction) → look up that faction; if `!getFlag(faction, FRIENDLY_FIRE)` → cancel + send key `custom.protection.friendly-fire-disabled` (`<red>Friendly fire is disabled in your faction.`) and return.
4. Territory check: `chunk = victim.getLocation().getChunk()`; `entry = board().findByChunk(...)`. Empty (wilderness) → return (server-rule PvP).
5. `factionId`:
   - `SAFEZONE_ID` **and** `config.isSafeZoneEnabled()` → cancel + key `custom.protection.pvp-safezone` (`<red>PvP is disabled in the Safezone.`); return.
   - `WARZONE_ID` → return (PvP **always** allowed in warzone, regardless of enabled flag).
   - Otherwise regular faction: if `flagService != null`, look up claim owner; if present and `!getFlag(owner, PVP)` → cancel + key `custom.protection.pvp-territory` (`<red>PvP is disabled in this territory.`).
6. `StorageException` → log WARNING "Failed to evaluate PvP protection".

Note: friendly-fire message goes to `attacker`; all pvp-deny messages go to `attacker`.

#### 3.1.4 Explosion protection

| Handler | Event | Priority | ignoreCancelled |
|---|---|---|---|
| `onEntityExplode` | `EntityExplodeEvent` | HIGH | true |
| `onBlockExplode` | `BlockExplodeEvent` | HIGH | true |

Both: `event.blockList().removeIf(this::isProtectedFromExplosion)` — removes protected blocks from the explosion's affected list (does not cancel the whole event).

**`isProtectedFromExplosion(block)` → true means protect (remove from blast):**
1. `entry = board().findByChunk(block chunk)`. Empty → **false** (wilderness not protected).
2. SAFEZONE/WARZONE → **true** (always protect zones).
3. Regular faction present → return `!getFlag(faction, EXPLOSIONS)` (explosions flag ON → not protected; OFF → protected).
4. Faction row missing → **true** (default: protect claimed territory).
5. `StorageException` → **true** (fail-safe protect).

#### 3.1.5 Fire spread protection

| Handler | Event | Priority | ignoreCancelled |
|---|---|---|---|
| `onBlockSpread` | `BlockSpreadEvent` | HIGH | true |

1. If `event.getSource().getType() != Material.FIRE` → return (only fire spread).
2. `chunk = event.getSource().getChunk()`; `entry = board().findByChunk(...)`. Empty → return (no protection in wilderness).
3. `factionId` SAFEZONE/WARZONE → return (fire spread allowed in zones — not cancelled).
4. Regular faction: if `flagService != null` and present and `!getFlag(faction, FIRE_SPREAD)` → `event.setCancelled(true)`.
5. `StorageException` → log WARNING.

#### 3.1.6 NOT handled by this engine (scope boundary — implementer beware)
`EngineProtection` does **not** handle: piston (extend/retract) protection, liquid/bucket flow protection, item-frame / armor-stand / painting protection, animal (non-player entity) damage protection, entity-interact / door/button/lever interaction protection, fire-ignite (`BlockIgniteEvent`), TNT ignite, hanging-break, or `PlayerInteractEvent`. If those protections are desired they are new features, not present in the original engine layer. The only interaction gated is block **break/place** and player-vs-player **damage**.

---

### 3.2 `EngineChunkChange` — claim / unclaim engine (not a Listener; called by commands & auto-territory)

Constructed with `(Repositories, FactionsConfig, Logger)`. Holds a per-chunk lock map `ConcurrentHashMap<String,Object> claimLocks`. Chunk key = `world + ":" + x + ":" + z`.

Both public methods take (`Player`, `Chunk`), synchronize on a per-chunk lock (`computeIfAbsent(key)`), and `finally` `claimLocks.remove(key)`.

#### 3.2.1 `claim(player, chunk) → boolean`
Inside the synchronized block, in order:
1. `pm = players().find(uuid)`. If empty or `!isInFaction()` → send `<red>You are not in a faction.`, return false. `factionId = pm.getFactionId()`.
2. **Re-fetch faction** `factions().find(factionId)` (bug fix: disband during claim). Empty → `<red>Your faction no longer exists.`, false.
3. **Existing-claim / overclaim handling**: `existing = board().findByChunk(...)`. If present:
   - `existingFactionId`.
   - If `!config.isOverclaimingEnabled()` → `<red>This chunk is already claimed.`, false.
   - If existing is SAFEZONE/WARZONE → `<red>This chunk is already claimed.`, false.
   - If `existingFactionId == factionId` → `<red>This chunk is already claimed by your faction.`, false.
   - If `config.isOverclaimRequireEnemyRelation()` **and** `getRelation(faction, existingFactionId) != ENEMY` → `<red>You can only overclaim land from factions you are at war with.`, false.
   - Look up victim faction `victimOpt`. If present:
     - `victimLand = board().countByFactionId(existingFactionId)`; `victimMaxLand = computeMaxLand(candidate, existingFactionId)`.
     - **If `victimLand <= victimMaxLand`** → not raidable → key `claim.enemy-not-raidable` (`<red>{faction} still has enough power — you cannot overclaim their land yet.`, `{faction}`=victim name), false.
     - **F5 offline protection**: if `config.isOfflineProtectionEnabled()`, check whether any victim member is online (`players().findByFactionId(existingFactionId)` → any `Bukkit.getPlayer(UUID.fromString(m.getId())) != null`; catch bad UUID→false). If none online → key `claim.enemy-offline-protected` (`<red>{faction} is offline — you cannot overclaim their land while all members are offline.`), false.
     - **F6 war shield**: if `config.isWarShieldEnabled()` and `candidate.isShieldActive()` → key `claim.shield-active` (`<red>{faction} has an active war shield — their territory is protected right now.`), false.
     - Set `victimFaction = candidate`.
   - If victim row is gone (`victimOpt` empty), `victimFaction` stays null and the claim proceeds (cleans up the orphan claim).
4. **Power/land check** (real-time, no cache): `currentLand = board().countByFactionId(factionId)`; `maxLand = computeMaxLand(faction, factionId)`. If `currentLand >= maxLand` → `<red>Your faction does not have enough power to claim more land.`, false.
5. **Border check** (only when `victimFaction == null`, i.e. not an overclaim): if `!isValidBorder(chunk, factionId, faction)` → `<red>You may only claim land that borders your own territory or wilderness.`, false. Overclaims skip the border check.
6. Fire `FactionChunkClaimEvent(faction, uuid, world, x, z, victimFaction)`. If cancelled → return false.
7. Persist: `board().claimChunk(world, x, z, factionId)`.
8. **Overclaim notifications** (when `victimFaction != null`):
   - To claimer: key `claim.overclaimed` (`<green>You overclaimed a chunk from <yellow>{faction}<green>!`, `{faction}`=victim name).
   - `remaining = board().countByFactionId(victimFactionId)`.
   - To each **online** victim member: key `claim.overclaimed-victim` (`<red><yellow>{attacker}<red> overclaimed a chunk from your territory! <yellow>{remaining}<red> chunk(s) remain.`, `{attacker}`=claiming faction name, `{remaining}`).
9. Return true.
`StorageException` anywhere → log SEVERE "Failed to claim chunk <key>", send `<red>An internal error occurred. Please try again.`, false.

#### 3.2.2 `unclaim(player, chunk) → boolean`
1. `pm`; empty/`!isInFaction()` → `<red>You are not in a faction.`, false. `factionId`.
2. `entry = board().findByChunk(...)`; empty → `<red>This chunk is not claimed.`, false.
3. If `factionId != entry.factionId` → `<red>You can only unclaim your own faction's territory.`, false.
4. `factionOpt = factions().find(factionId)`. **If empty** → `board().unclaimChunk(...)` (clean up), return true (no event fired).
5. Fire `FactionChunkUnclaimEvent(faction, uuid, world, x, z)`. Cancelled → false.
6. `board().unclaimChunk(...)`; return true.
`StorageException` → log SEVERE "Failed to unclaim chunk <key>", `<red>An internal error occurred. Please try again.`, false.

#### 3.2.3 `computeMaxLand(faction, factionId)` — LOAD-BEARING power/land math
```
totalPower = faction.getPowerBoost()               // faction-level boost
inactiveExclude = config.isPowerInactiveExclusionEnabled()
inactiveMs = config.getPowerInactiveDays() * 24 * 3600 * 1000   // days→ms
now = System.currentTimeMillis()
for each PlayerModel pm in players().findByFactionId(factionId):
    if inactiveExclude and pm.getLastActivity() > 0 and (now - lastActivity) > inactiveMs:
        continue                                    // F1: skip inactive member's power
    totalPower += pm.getPower()
landPerPower = config.getLandPerPower()
if landPerPower <= 0: return config.getMaxLand()
return min( config.getMaxLand(), (int)(totalPower / landPerPower) )
```
Integer truncation via `(int)` cast. Note `getLastActivity()==0` (never recorded) counts as active.

#### 3.2.4 `isValidBorder(chunk, factionId, faction)` — LOAD-BEARING adjacency
```
if board().countByFactionId(factionId) == 0: return true   // first claim always valid
offsets = {(1,0),(-1,0),(0,1),(0,-1)}                       // 4 cardinal neighbors only
for each offset:
    neighbor = board().findByChunk(world, x+dx, z+dz)
    if neighbor empty (wilderness): return true
    if neighbor.factionId == factionId: return true         // own territory
    if getRelation(faction, neighbor.factionId) != ENEMY: return true
return false     // valid only if ALL 4 neighbors are enemy territory → invalid
```
A claim is valid if **any** cardinal neighbor is wilderness, own, or non-enemy. Invalid only when every neighbor is enemy land. Diagonal neighbors are ignored.

---

### 3.3 `EngineAutoTerritory` (Listener) — auto claim/unclaim on chunk crossing

Constructed with `(EngineChunkChange, TerritoryGuard, AutoTerritoryModeCache)`.
`register(plugin)` registers events **and** hydrates the mode cache for every currently-online player.

| Handler | Event | Priority | ignoreCancelled | Action |
|---|---|---|---|---|
| `onJoin` | `PlayerJoinEvent` | default (NORMAL) | — | `modeCache.hydrate(uuid)` |
| `onQuit` | `PlayerQuitEvent` | default | — | `modeCache.evict(uuid)` |
| `onMove` | `PlayerMoveEvent` | MONITOR | true | see below |

`onMove`: if `event.getTo()==null` or `from.getChunk().equals(to.getChunk())` → return (chunk-change detection). Then:
- `mode = modeCache.getMode(uuid)`.
- `CLAIM`: if `territoryGuard.canModifyTerritory(player, event.getTo())` → `engineChunkChange.claim(player, toChunk)`; return.
- `UNCLAIM`: `engineChunkChange.unclaim(player, toChunk)`.
- `OFF`: nothing.

### 3.4 `AutoTerritoryModeCache`
Constructed with `(Repositories, Logger)`. `ConcurrentHashMap<UUID, AutoTerritoryMode> cache`.
- `getMode(uuid)` → `cache.getOrDefault(uuid, AutoTerritoryMode.OFF)`.
- `setMode(uuid, mode)`: `players().findOrCreate(uuid)`, `setAutoTerritoryMode(mode)`, save, cache put (`null`→OFF), return true; on `StorageException` log WARNING + return false.
- `hydrate(uuid)`: `findOrCreate`, read `getAutoTerritoryMode()`, cache put, return it; on error cache OFF + return OFF.
- `evict(uuid)`: `cache.remove(uuid)`.
`AutoTerritoryMode` enum: `OFF`, `CLAIM`, `UNCLAIM`.

---

### 3.5 `EnginePlayerMove` (Listener) — territory-transition titles/notices

Constructed with `(Repositories, FactionsConfig, Logger)`.

| Handler | Event | Priority | ignoreCancelled |
|---|---|---|---|
| `onMove` | `PlayerMoveEvent` | MONITOR | true |

Chunk-change detection & gating:
1. If `from.getChunk().equals(to.getChunk())` → return.
2. `from`, `to` chunks; if `!from.getWorld().equals(to.getWorld())` → return (no cross-world notice).
3. Look up mover's `PlayerModel`; if present and `!hasTerritoryTitles()` → return (per-player toggle).
4. `fromInfo = resolveTerritory(from)`, `toInfo = resolveTerritory(to)`.
5. If `fromInfo.id != toInfo.id` (actually changed territory):
   - **If `toInfo.claimed()`** (entered a real faction): compute `leader` (owner display name), `members = players().findByFactionId(id).size()`, `land = board().countByFactionId(id)`, `power = powerBoost + Σ member power`. Send `MsgUtil.factionInfoHover(...)` built from:
     - base line key `custom.move.enter-claimed` default `<gold>You entered claimed faction territory: <white>{faction}`
     - hover title = faction name
     - hover lines: `custom.move.info-leader` (`<gray>Leader: <white>{value}`), `custom.move.info-members` (`<gray>Members: <white>{value}`), `custom.move.info-land` (`<gray>Land: <white>{value}`), `custom.move.info-power` (`<gray>Power: <white>{value}`, formatted `%.1f`, Locale.ROOT).
   - **Else** (wilderness/safezone/warzone): key `custom.move.enter` default `<gray>You entered: <white>{territory}`, `{territory}`=territory name.
6. `StorageException` → log WARNING.

**`resolveTerritory(chunk) → TerritoryInfo(id, name, claimed, faction)`:**
- No board entry → `(WILDERNESS_ID, "Wilderness", false, null)`.
- SAFEZONE: if `config.isSafeZoneEnabled()` → `(SAFEZONE_ID, "Safezone", false, null)` else Wilderness.
- WARZONE: if `config.isWarZoneEnabled()` → `(WARZONE_ID, "Warzone", false, null)` else Wilderness.
- Regular faction: `factions().find(id)` empty → Wilderness; present → `(id, name, true, faction)`.

`formatLeader(faction)`: blank/null ownerId → `"Unknown"`; else `Bukkit.getOfflinePlayer(UUID.fromString(ownerId)).getName()` (null name → ownerId; bad UUID → ownerId).

Note: no fly, stuck, or home logic exists in this engine (or any engine in scope). "Territory titles" here means the enter-notification chat messages, gated by `PlayerModel.hasTerritoryTitles()`.

---

### 3.6 `EngineChat` — faction-tag chat prefix

Constructed with `(Repositories, FactionsConfig, Logger)`. Static `PAPER_CHAT` flag: true if `Class.forName("io.papermc.paper.event.player.AsyncChatEvent")` succeeds, else false (Spigot/Bukkit).

`register(plugin)`: if `PAPER_CHAT` register a `PaperChatListener` (may throw `ReflectiveOperationException` → log SEVERE "Chat renderer init failed; faction chat tags disabled on Paper"); else register `LegacyChatListener`.

**Faction tag** (`buildFactionTag(player)`): if player in faction and faction found → `"<gray>[<white>" + factionName + "<gray>]</gray> "` (trailing space); otherwise `""`.

**PaperChatListener** — `onChat(AsyncChatEvent)` at priority **HIGHEST, ignoreCancelled=true**:
- If `!config.isChatFormatEnabled()` → return.
- Build tag; prefix component = empty component if tag empty, else MiniMessage-deserialized tag.
- Install a `ChatRenderer.ViewerUnaware` renderer that renders: `prefix + displayName + separator + message`, where separator is the MiniMessage `"<gray>: <white>"`.
- `StorageException` → log WARNING "Failed to format chat for <name>"; `ReflectiveOperationException` → log WARNING "Chat renderer reflection failed".
- Implementation detail: **all Adventure interactions go through reflection** so maven-shade never rewrites `net.kyori.adventure.*` descriptors. The `"net.kyori"` string is built via `String.valueOf("net.kyori")` to defeat constant folding. Uses `MiniMessage.deserialize(String, TagResolver...)` (the single-arg overload was removed in Adventure 4.20) with an empty TagResolver array. A reimplementation that does not shade Adventure can use the API directly; the reflection is purely a shading workaround.

**LegacyChatListener** — `onChat(AsyncPlayerChatEvent)` at **HIGHEST, ignoreCancelled=true**:
- If `!config.isChatFormatEnabled()` → return.
- `event.setFormat(tag + "%s: %s")`.
- `StorageException` → log WARNING.

**Scope note — no chat channels or spy.** There is no faction-only / ally chat channel, no channel switching, and no social-spy feature in this engine. It only injects a `[FactionName]` prefix into normal public chat. (Faction/ally channel messaging, if any, lives in the command layer, outside these packages.)

---

### 3.7 `EnginePower` (Runnable + Listener) — power regen tick, death/kill hooks, raidable broadcast

Constructed with `(Repositories, FactionsConfig, Logger, TaskScheduler, PowerService)`. Records `startedAt = System.currentTimeMillis()` at construction (server-start grace baseline). Holds `CancelableTask timerTask`.

**`start(plugin)`**: `intervalSeconds = max(1, config.getPowerTickIntervalSeconds())`; `intervalTicks = intervalSeconds*20`; `timerTask = taskScheduler.scheduleAsyncTimer(this, intervalTicks, intervalTicks)` (async, both delay & period = intervalTicks); register listeners.
**`stop()`**: cancel timer if set.

#### 3.7.1 `run()` (scheduled, async, every intervalSeconds)
- `players().findAll()` → for each `tickPower(pm)`.
- If `config.isRaidableBroadcastEnabled()` → `checkRaidableTransitions()`.
- `StorageException` → log SEVERE "Failed to tick power for players".

**`tickPower(pm)`**: `current = pm.getPower()`, `max = config.getMaxPower()`. `source = REGEN_ONLINE` if `Bukkit.getPlayer(uuid)!=null` else `REGEN_OFFLINE`. If `current < max` → `powerService.apply(Request(pm.id, source, baseDelta=0.0, actorName="system", reason=source.name(), world=null, zone=null, bypassFreeze=false))`. (The actual regen amount comes from `PowerService.sourceAmount`, not from baseDelta — see §3.11.)

#### 3.7.2 `onQuit(PlayerQuitEvent)` (default priority)
`taskScheduler.runAsync(() -> applyPowerOnQuit(uuid))`. `applyPowerOnQuit` is currently a **no-op** (death handles power loss).

#### 3.7.3 `onDeath(PlayerDeathEvent)` — priority MONITOR, ignoreCancelled=true
Capture on the main thread: dead player, `killer = dead.getKiller()` (nullable), deadId, killerId (nullable), worldName, chunkX, chunkZ. Then `taskScheduler.runAsync(() -> applyDeathPower(deadId, killerId, world, cx, cz))`.

**`applyDeathPower(...)`** (async):
1. **Grace period**: if `now - startedAt < config.getPowerGracePeriodSeconds()*1000` → return (no power loss during startup grace).
2. `claim = board().findByChunk(world, cx, cz)`; `deadOpt = players().find(deadId)`; `deadFactionId = deadOpt.map(factionId).orElse(null)`; `zone = PowerServiceImpl.zoneFromClaim(claim, deadFactionId)` (see §3.11.3).
3. **Safezone skip**: if `config.isSafeZoneEnabled()` and claim present and claim is SAFEZONE → return (no power change).
4. If `deadOpt` present:
   - `victimPowerBefore = deadModel.getPower()` (F3 — captured before mutation).
   - **F2 death streak**: `loss = config.getPowerLossOnDeath()`; `streak = 0`. If `config.isDeathStreakEnabled()`:
     - `windowMs = config.getDeathStreakWindowSeconds()*1000`; `lastDeath = deadModel.getLastDeathAt()`.
     - If `lastDeath > 0 && now - lastDeath <= windowMs` → `streak = deadModel.getDeathStreak() + 1`.
     - If `streak > 0` → `loss = loss * Math.pow(config.getDeathStreakMultiplier(), streak)`.
     - Persist `deadModel.setLastDeathAt(now)`, `setDeathStreak(streak)`.
   - Apply loss: `deathResult = powerService.apply(Request(deadId, DEATH, -loss, "system", "DEATH", world, zone, false))`. `actualLoss = Math.abs(deathResult.effectiveDelta())`.
   - **Message to dead player** (on main thread via `runSync`, only if online): if death-streak enabled and `streak>0` → key `power.death-streak-penalty` (`<red>Death streak ×{streak}! You lost <yellow>{amount}<red> power.`) with `{streak}` = **`finalStreak + 1`** and `{amount}` = `%.1f` actualLoss; else key `power.lost-on-death` (`<red>You lost <yellow>{amount}<red> power on death.`).
   - **Kill reward** (only if `killerId != null && config.isPowerGainOnKillEnabled()`): look up killer model. If present:
     - `gain = config.getPowerGainOnKill()`. **F3 kill scaling** if `config.isKillScaleEnabled()`: `killerPower = killerModel.getPower()`. If `>0`: `ratio = victimPowerBefore / killerPower`; `factor = clamp(ratio, config.getKillScaleMinFactor(), config.getKillScaleMaxFactor())` (i.e. `max(min, min(max, ratio))`); `gain *= factor`. If killerPower `<= 0`: `gain *= config.getKillScaleMinFactor()`.
     - `killResult = powerService.apply(Request(killerId, KILL, gain, "system", "KILL", world, zone, false))`; `finalGain = killResult.effectiveDelta()`.
     - Message (main thread, if killer online): key `power.kill-gained` (`<green>You gained <yellow>{amount}<green> power from your kill.`, `%.1f` finalGain).
5. `StorageException` → log SEVERE "Failed to apply death power changes".

Note: the DEATH/KILL apply requests carry the actual delta as baseDelta, but `PowerService.sourceAmount` **overrides** DEATH to `-|config death amount|` and KILL to `+|config kill amount|` — see §3.11.2. The zone/world multipliers are then applied only for DEATH/KILL sources.

#### 3.7.4 `computeTotalPower(factionId)` → `powerService.getFactionPower(factionId)`.

#### 3.7.5 `checkRaidableTransitions()` (F4) — called each tick when broadcast enabled
```
landPerPower = config.getLandPerPower()
for each faction in factions().findAll():
    if !faction.isNormal(): continue
    totalPower = powerService.getFactionPower(id)
    maxLand = landPerPower<=0 ? config.getMaxLand() : min(config.getMaxLand(), (int)(totalPower/landPerPower))
    currentLand = board().countByFactionId(id)
    nowRaidable = currentLand > maxLand
    if nowRaidable == faction.isRaidable(): continue     // no transition
    faction.setRaidable(nowRaidable); factions().save(faction)
    // notify online members (main thread) + optional server broadcast
```
- Member message (all online members, run on `runSync`): becoming raidable → key `raidable.became-raidable` (`<red>⚠ Your faction is now raidable! Enemies can overclaim your land.`); recovered → key `raidable.no-longer-raidable` (`<green>✔ Your faction is no longer raidable.`).
- If `config.isRaidableBroadcastServerWide()` → broadcast to **all** online players: becoming → key `raidable.server-announce` (`<red>⚠ <yellow>{faction}</yellow> is now raidable!`); recovered → key `raidable.server-announce-recovered` (`<green>✔ <yellow>{faction}</yellow> is no longer raidable.`).
- Note the raidable threshold here uses strict `currentLand > maxLand`, whereas claim blocking in §3.2 uses `currentLand >= maxLand`.

---

### 3.8 `EngineEconomy` — faction bank deposit/withdraw/transfer + periodic tax

Constructed via three overloaded ctors; the full one is `(Plugin, Repositories, FactionsConfig, NotificationsConfig, VaultEconomy, TaskScheduler, Logger)` (plugin arg is stored implicitly unused; `notificationsConfig` and `taskScheduler` may be null). Holds `CancelableTask taxTask`. `roundMoney(v) = Math.round(v*100)/100.0` (2-dp).

Common guards for deposit/withdraw/transfer: amount must be `> 0`; `vaultEconomy.isEnabled()`; `config.isBankEnabled()`.

#### 3.8.1 `deposit(player, factionId, amount)` (player wallet → bank)
1. `amount <= 0` → `<red>Amount must be positive.` false.
2. `!vault.isEnabled()` → `<red>Economy is not available.` false.
3. `!config.isBankEnabled()` → `<red>Faction banks are disabled.` false.
4. `factions().find(factionId)` empty → `<red>Faction not found.` false.
5. `vault.getBalance(player) < amount` → `<red>You do not have enough money.` false.
6. Fire `FactionBankTransactionEvent(faction, uuid, DEPOSIT, amount)`; cancelled → false; else read back `finalAmount = event.getAmount()`.
7. Re-check `vault.isEnabled()` (commit window) → else `<red>Economy is not available.` false.
8. `vault.withdraw(player, finalAmount)`; if false → `<red>Could not withdraw money from your account.` false.
9. `faction.setBank(bank + finalAmount)`; save; `recordTransaction(factionId, uuid, "DEPOSIT", finalAmount, null, "Player deposit")`.
10. `<green>Deposited <white>{amount:%.2f}<green> into the faction bank.` true.
`StorageException` → log SEVERE + `<red>An internal error occurred.` false.

#### 3.8.2 `withdraw(player, factionId, amount)` (bank → player wallet)
Guards as above. Then: faction not found → `<red>Faction not found.`; `faction.getBank() < amount` → `<red>The faction bank does not have enough funds.`; fire event (WITHDRAW), read `finalAmount`; re-check vault; **debit bank first** (`setBank(bank - finalAmount)`, save) then `vault.deposit(player, finalAmount)`; if deposit fails → **roll back** (`setBank(bank + finalAmount)`, save) + `<red>Could not credit your account.` false; else `recordTransaction(..., "WITHDRAW", finalAmount, null, "Player withdraw")` + `<green>Withdrew <white>{amount:%.2f}<green> from the faction bank.` true.

#### 3.8.3 `transfer(invokerUuid, fromFactionId, toFactionId, amount)` (bank → bank, atomic)
Guards: `amount>0`, vault enabled, bank enabled (all silent `false`, no messages). Wrapped in `repos.factions().transaction(...)`:
- Re-load both factions inside tx; either empty → abort (no change).
- If `from.getBank() < amount` → abort. Re-check vault inside tx → abort if disabled.
- `from.setBank(bank-amount)`, `to.setBank(bank+amount)`, save both.
- `recordTransaction(fromId, invoker, "TRANSFER_OUT", amount, toId, "Transfer to " + (to.name||to.id))`.
- `recordTransaction(toId, invoker, "TRANSFER_IN", amount, fromId, "Transfer from " + (from.name||from.id))`.
- `success[0] = true`.
After tx, if success → fire `FactionBankTransactionEvent(fromFaction, invoker, TRANSFER, amount)` (fired **after** commit, purely for audit; not cancellable-effective). Return `success[0]`. `StorageException` → log SEVERE, false.

#### 3.8.4 `recordTransaction(factionId, actorUuid, type, amount, counterpartyFactionId, note)`
If `repos.bankTransactions() == null` → skip. Else build `BankTransactionModel(randomUUID)`: factionId, actorUuid (null→null else toString), `type.toUpperCase(ROOT)`, amount, counterparty, `createdAt=now`, note; save.

#### 3.8.5 Tax scheduler
`startTaxScheduler(scheduler)`: stop existing; if `!config.isBankEnabled() || !config.isTaxEnabled()` → return. `intervalHours = max(1, config.getTaxIntervalHours())`; `intervalTicks = intervalHours*60*60*20`; `taxTask = scheduler.scheduleAsyncTimer(this::applyPeriodicTaxesSafely, intervalTicks, intervalTicks)`; log info `"Faction bank tax enabled: rate=<rate>, intervalHours=<h>"`.
`stopTaxScheduler()`: cancel if set.
`applyPeriodicTaxesSafely()`: `n = applyFactionTaxesNow()`; if `n>0` log info `"Applied faction bank tax to <n> faction(s)."`.

**`applyFactionTaxesNow() → int`** (LOAD-BEARING tax math):
```
if !bankEnabled || !taxEnabled: return 0
rate = config.getTaxRate();  if rate <= 0: return 0
minBank   = max(0, config.getTaxMinBankBalance())
minCharge = max(0, config.getTaxMinChargeAmount())
for each faction in factions().findAll():
    if !faction.isNormal(): continue
    currentBank = faction.getBank()
    if currentBank <= minBank: continue
    computed = roundMoney(currentBank * rate)
    if computed <= 0 || computed < minCharge: continue
    taxAmount = min(currentBank, computed)
    newBank   = roundMoney(max(0, currentBank - taxAmount))
    faction.setBank(newBank); save
    recordTransaction(id, null, "TAX", taxAmount, null,
        "Periodic bank tax (" + roundMoney(rate*100) + "%)")
    taxedFactions++
    notifyTaxedMembers(faction, taxAmount, newBank)
return taxedFactions
```
`StorageException` → log SEVERE, returns count so far.

`notifyTaxedMembers`: no-op if `notificationsConfig==null || !isEconomyTaxNotifyMembers()`. Else build message key `bank.tax-charged` (`<gold>Faction bank tax charged: <yellow>{amount}<gold>. New bank: <yellow>{balance}<gold>.`, both `%.2f` Locale.US) and call `FactionMemberNotifier.notifyMembers(taskScheduler, repos, logger, factionId, member -> member.hasBankTaxNotifications(), message)` — online members get it live, offline members get an inbox entry.

---

### 3.9 `EngineNotifications` (Listener) — join-time invite/inbox/MOTD delivery

Constructed with `(InviteService, FactionService, Repositories, Logger[, NotificationsConfig])`. `register(plugin, scheduler)` stores scheduler + registers listener.

`onJoin(PlayerJoinEvent)` (default priority): if `taskScheduler==null` → return. Else `runAsync`:
1. **Invites**: `invites = inviteService.listActiveInvitesForPlayer(uuid)`; `model = players().findOrCreate(uuid)`. If invites non-empty **and** `model.hasInviteNotifications()` → `runSyncForPlayer(player, () -> sendInviteSummary(...))`.
   - `sendInviteSummary`: key `invite.summary` (`<gold>You have <white>{count}</white> pending faction invite(s):`); then per invite, resolve faction name (`factionService.getFactionById(...).name` or `"Unknown"`) and inviter name (`Bukkit.getOfflinePlayer(UUID).getName()` fallback to uuid/"Unknown"), send `MsgUtil.inviteListEntry(player, factionName, inviterName)`.
2. **Inbox**: `inboxEntries = repos.inbox().findByPlayerId(uuid)`. If non-empty **and** (`notificationsConfig==null || isInboxEnabled()`): **delete** all entries (`inbox().deleteByPlayerId(uuid)`), `maxEntries = notificationsConfig==null ? 20 : getInboxMaxPerLogin()`, truncate to first `maxEntries` if larger, `runSyncForPlayer(player, () -> deliverInbox(...))`.
   - `deliverInbox`: key `notifications.inbox-header` (`<gold>Missed faction notifications (<white>{count}</white>):`) then each `entry.getMessage()` raw.
   - **Edge case**: all entries are deleted from storage even though only `maxEntries` are shown → excess missed notifications are lost.
3. **MOTD**: `motdFaction = factionService.getFactionByPlayer(uuid).orElse(null)`. If non-null and `model.hasMotdNotifications()` and `motd` non-empty → `runSyncForPlayer`: key `motd.header` (`<gold>== <yellow>Faction MOTD</yellow> ==`) then key `motd.display` (`<gray>{motd}`, `{motd}`=faction motd).
Any `Exception` → `logger.warning("Failed to send join notifications: " + msg)`.

---

### 3.10 `EngineAuditLog` (Listener) — forwards custom events to `AuditService`

Constructed with `(AuditService)`. All handlers at **MONITOR, ignoreCancelled=true**.
| Handler | Event | Recorded action | Detail |
|---|---|---|---|
| `onChunkClaim` | `FactionChunkClaimEvent` | `FactionAuditAction.CLAIM` | `"<world> <x>,<z>"` |
| `onChunkUnclaim` | `FactionChunkUnclaimEvent` | `FactionAuditAction.UNCLAIM` | `"<world> <x>,<z>"` |
| `onBankTransaction` | `FactionBankTransactionEvent` | DEPOSIT→`BANK_DEPOSIT`, WITHDRAW→`BANK_WITHDRAW`, else→`BANK_TRANSFER` | `String.format(ROOT,"%.2f",amount)` |
Records `auditService.record(faction.getId(), event.getPlayerUUID(), action, detail)`.
Kick/promote/demote/relation audits are recorded elsewhere (in `FactionServiceImpl`), not here.

---

### 3.11 `PowerService` / `PowerServiceImpl` (referenced by EnginePower; in `service`, but load-bearing)

`Source` enum: `REGEN_ONLINE, REGEN_OFFLINE, DEATH, KILL, BUY, ADMIN_SET, ADMIN_ADD, ADMIN_REMOVE, ADMIN_RESET`.
`ZoneContext` enum: `SAFEZONE, WARZONE, OWN_CLAIMED, ENEMY_CLAIMED, WILDERNESS`.
`Request(playerId, source, baseDelta, actorName, reason, world, zone, bypassFreeze)`.
`Result(changed, blockedByFreeze, before, requestedDelta, effectiveDelta, after, reasonCode)`.

#### 3.11.1 `apply(request)` pipeline (LOAD-BEARING)
```
model = players().find/create; before = model.getPower(); requestedDelta = baseDelta
// Freeze gate
if !bypassFreeze && model.isPowerFrozen() && sourceAffectedByFreeze(source):
    notify blocked-by-freeze; return Result(changed=false, blockedByFreeze=true, ..., 0.0, before, "FROZEN")
sourceAmount = sourceAmount(source, baseDelta)   // see below — overrides baseDelta for most sources
if !sourceEnabled(source): return Result(false,false,before,requestedDelta,0.0,before,"SOURCE_DISABLED")
delta = sourceAmount
if source==DEATH || source==KILL: delta = applyMultipliers(delta, world, zone)   // world*zone
delta = applyEventClamp(delta)                    // clamp to ±config.getPowerMaxChangePerEvent() (0 = no clamp)
minPower = config.getPowerMin(); maxPower = config.getPowerMax()
after = clamp(before + delta, minPower, maxPower)
effectiveDelta = after - before
if |effectiveDelta| < 0.00001: return Result(false,false,...,0.0,before,"NO_CHANGE")
model.setPower(after); save; powerHistory().record(id, effectiveDelta, reasonCode, after)
notifyPowerChange(...); return Result(true,false,before,requestedDelta,effectiveDelta,after,reasonCode)
```

#### 3.11.2 `sourceAmount(source, requested)`
| Source | Amount |
|---|---|
| REGEN_ONLINE | `config.getPowerSourceRegenOnlineAmount()` |
| REGEN_OFFLINE | `config.getPowerSourceRegenOfflineAmount()` |
| DEATH | `-|config.getPowerSourceDeathLossAmount()|` |
| KILL | `+|config.getPowerSourceKillGainAmount()|` |
| BUY | `requested` (baseDelta passthrough) |
| ADMIN_* | `requested` |

`sourceEnabled`: REGEN_ONLINE→`isPowerSourceRegenOnlineEnabled`, REGEN_OFFLINE→`isPowerSourceRegenOfflineEnabled`, DEATH→`isPowerSourceDeathLossEnabled`, KILL→`isPowerSourceKillGainEnabled`, BUY→`isPowerBuyEnabled() && isPowerSourceBuyEnabled()`, ADMIN_*→true.
`sourceAffectedByFreeze`: REGEN_*→`isPowerFreezeBlocksRegen`, DEATH/KILL/BUY→`isPowerFreezeBlocksAutomatic`, ADMIN_*→false.
`applyMultipliers(delta, world, zone)` = `delta * config.getPowerWorldMultiplier(world) * config.getPowerZoneMultiplier(zone.name().toLowerCase(ROOT))`.
`applyEventClamp(delta)`: `maxAbs = config.getPowerMaxChangePerEvent()`; if `<=0` no clamp, else `clamp(delta, -maxAbs, maxAbs)`.
`reasonCode(request)`: trimmed non-blank `reason`, else `source.name()`.

#### 3.11.3 `getFactionPower(factionId)` & `zoneFromClaim`
`getFactionPower` = `faction.getPowerBoost()` + Σ member power, **skipping members excluded by inactivity** (`isPowerInactiveExclusionEnabled()` + `getPowerInactiveDays()` window vs `getLastActivity()`).
`static zoneFromClaim(Optional<BoardEntry> claim, String factionId)`: empty→WILDERNESS; SAFEZONE→SAFEZONE; WARZONE→WARZONE; `factionId != null && == claim.factionId`→OWN_CLAIMED; else ENEMY_CLAIMED.
`notifyPowerChange`/`notifyBlockedByFreeze` broadcast a `[Power]` debug line to online `factions.admin` holders + console (e.g. `<gray>[Power] <white>{player}</white> blocked by freeze. Source: <white>{source}</white>.`).

---

### 3.12 `EngineTeamChests` (Listener) — faction chest inventories

Constructed with `(TeamChestService, Logger)`. `CHEST_SIZE = 54`. `ConcurrentHashMap<UUID, OpenChestSession(factionId, chestName)> sessions`.
- `openChest(player, factionId, chestName, title) → boolean`: `teamChestService.getChestContents(factionId, chestName)`; empty → false. Create a 54-slot inventory titled `title`, fill first `min(contents.size, 54)` slots, register session for the player, `player.openInventory(inventory)`, true.
- `onInventoryClose(InventoryCloseEvent)` (default priority): only if `getPlayer() instanceof Player`. `session = sessions.remove(uuid)`; if null → return. Collect all `event.getInventory().getContents()` into a list, `teamChestService.setChestContents(factionId, chestName, items)`; on false → `logger.warning("Failed to persist team chest <name> for faction <id>")`.

---

### 3.13 `EngineUpdateNotifier` (Listener) — op update notice on join

Constructed with `(FactionsConfig, UpdateNotificationManager)`.
`onJoin(PlayerJoinEvent)` (default priority): if `!config.isUpdateCheckEnabled() || !config.isUpdateNotifyOpsOnJoin()` → return; if `!player.isOp()` → return. `updateManager.latest()` → `ChainedUpdateResult::getResult`, filter `!hasError() && isUpdateAvailable()`, then send:
- key `update.available` (`<yellow>Update available for Factions: <white>{current}</white> -> <green>{latest}</green> <gray>({source})`; `{current}`=result.getCurrentVersion, `{latest}`=result.getLatestVersion or "unknown", `{source}`=updateManager source or "unknown").
- if release URL present, key `update.url` (`<gray>Download: <aqua><click:open_url:'{url}'>{url}</click>`).

---

### 3.14 `FactionMemberNotifier` (static utility)

Two static helpers used by other engines:

**`notifyOnlineMembers(scheduler, repos, logger, factionId, Predicate<PlayerModel> filter, Consumer<Player> notifyAction)`**: no-op if `repos`/`repos.players()` null. Load `players().findByFactionId(factionId)`; for each member passing `filter`, resolve UUID (bad UUID → skip), get online `Player` (null/!online → skip). If `scheduler==null` run inline else `scheduler.runSyncForPlayer(online, () -> notifyAction.accept(online))`. `StorageException` → log WARNING.

**`notifyMembers(scheduler, repos, logger, factionId, filter, String message)`**: online members receive `MsgUtil.send(online, message)` (inline or via `runSyncForPlayer`); **offline members** get a persisted inbox entry — build `FactionInboxEntry(randomUUID)` with playerId, message, `createdAt=now`, `inbox().save(entry)` (inline if scheduler null, else `runAsync`). This is the online-vs-inbox split that pairs with `EngineNotifications` inbox delivery on join.

---

## 4. Scheduled tasks — complete inventory (in scope + adjacent)

| Task | Owner | Sync/Async | Period | Action |
|---|---|---|---|---|
| Power tick | `EnginePower.start` | **async** | `max(1, powerTickIntervalSeconds) * 20` ticks (delay=period) | regen all players (§3.7.1), raidable transitions if enabled |
| Bank tax | `EngineEconomy.startTaxScheduler` | **async** | `max(1, taxIntervalHours) * 3600 * 20` ticks | tax all normal factions (§3.8.5) |
| bStats metrics | `metrics/BStatsMetricsManager` (out of scope pkg) | async | see that class | telemetry only |

**No scheduled task exists for**: invite expiry (invites are filtered at read time by `InviteService.listActiveInvitesForPlayer`, not swept by a timer), autosave (persistence is per-operation / handled by the data layer, not a timer in these packages), power-decay separate from the regen tick, or war-shield rotation (shield activeness is computed on-demand from UTC hour in `FactionModel.isShieldActive()`).

---

## 5. Config keys referenced (engines) — implement these getters

**Zones:** `isSafeZoneEnabled`, `isWarZoneEnabled`.
**Claim/land/power:** `getMaxLand`, `getLandPerPower`, `getMaxPower`, `getPowerMin`, `getPowerMax`, `getPowerTickIntervalSeconds`, `getPowerGracePeriodSeconds`, `getPowerLossOnDeath`, `getPowerGainOnKill`, `isPowerGainOnKillEnabled`, `getPowerMaxChangePerEvent`.
**Power sources:** `isPowerSourceRegenOnlineEnabled`/`getPowerSourceRegenOnlineAmount`, `isPowerSourceRegenOfflineEnabled`/`getPowerSourceRegenOfflineAmount`, `isPowerSourceDeathLossEnabled`/`getPowerSourceDeathLossAmount`, `isPowerSourceKillGainEnabled`/`getPowerSourceKillGainAmount`, `isPowerBuyEnabled`, `isPowerSourceBuyEnabled`, `getPowerWorldMultiplier(world)`, `getPowerZoneMultiplier(zoneNameLower)`, `isPowerFreezeBlocksRegen`, `isPowerFreezeBlocksAutomatic`.
**Inactivity (F1):** `isPowerInactiveExclusionEnabled`, `getPowerInactiveDays`.
**Death streak (F2):** `isDeathStreakEnabled`, `getDeathStreakWindowSeconds`, `getDeathStreakMultiplier`.
**Kill scaling (F3):** `isKillScaleEnabled`, `getKillScaleMinFactor`, `getKillScaleMaxFactor`.
**Raidable (F4):** `isRaidableBroadcastEnabled`, `isRaidableBroadcastServerWide`.
**Overclaim (F5/F6):** `isOverclaimingEnabled`, `isOverclaimRequireEnemyRelation`, `isOfflineProtectionEnabled`, `isWarShieldEnabled`.
**Bank/tax:** `isBankEnabled`, `isTaxEnabled`, `getTaxIntervalHours`, `getTaxRate`, `getTaxMinBankBalance`, `getTaxMinChargeAmount`.
**Chat:** `isChatFormatEnabled`.
**Updates:** `isUpdateCheckEnabled`, `isUpdateNotifyOpsOnJoin`.
**NotificationsConfig:** `isEconomyTaxNotifyMembers`, `isInboxEnabled`, `getInboxMaxPerLogin` (default 20 when config null).

## 6. Permission nodes referenced (engines)
- `factions.bypass` — bypass build protection + PvP protection (checked in `EngineProtection`).
- `factions.admin` — receives `[Power]` debug broadcasts (in `PowerServiceImpl`).
(Override toggle is the per-player `PlayerModel.isOverriding()` flag, not a permission.)

## 7. Message keys referenced (engines) — key → default
| Key | Default |
|---|---|
| `custom.protection.no-break` | `<red>You may not break blocks in this territory.` |
| `custom.protection.no-place` | `<red>You may not place blocks in this territory.` |
| `custom.protection.friendly-fire-disabled` | `<red>Friendly fire is disabled in your faction.` |
| `custom.protection.pvp-safezone` | `<red>PvP is disabled in the Safezone.` |
| `custom.protection.pvp-territory` | `<red>PvP is disabled in this territory.` |
| `claim.enemy-not-raidable` | `<red>{faction} still has enough power — you cannot overclaim their land yet.` |
| `claim.enemy-offline-protected` | `<red>{faction} is offline — you cannot overclaim their land while all members are offline.` |
| `claim.shield-active` | `<red>{faction} has an active war shield — their territory is protected right now.` |
| `claim.overclaimed` | `<green>You overclaimed a chunk from <yellow>{faction}<green>!` |
| `claim.overclaimed-victim` | `<red><yellow>{attacker}<red> overclaimed a chunk from your territory! <yellow>{remaining}<red> chunk(s) remain.` |
| `custom.move.enter-claimed` | `<gold>You entered claimed faction territory: <white>{faction}` |
| `custom.move.info-leader` | `<gray>Leader: <white>{value}` |
| `custom.move.info-members` | `<gray>Members: <white>{value}` |
| `custom.move.info-land` | `<gray>Land: <white>{value}` |
| `custom.move.info-power` | `<gray>Power: <white>{value}` |
| `custom.move.enter` | `<gray>You entered: <white>{territory}` |
| `power.death-streak-penalty` | `<red>Death streak ×{streak}! You lost <yellow>{amount}<red> power.` |
| `power.lost-on-death` | `<red>You lost <yellow>{amount}<red> power on death.` |
| `power.kill-gained` | `<green>You gained <yellow>{amount}<green> power from your kill.` |
| `raidable.became-raidable` | `<red>⚠ Your faction is now raidable! Enemies can overclaim your land.` |
| `raidable.no-longer-raidable` | `<green>✔ Your faction is no longer raidable.` |
| `raidable.server-announce` | `<red>⚠ <yellow>{faction}</yellow> is now raidable!` |
| `raidable.server-announce-recovered` | `<green>✔ <yellow>{faction}</yellow> is no longer raidable.` |
| `bank.tax-charged` | `<gold>Faction bank tax charged: <yellow>{amount}<gold>. New bank: <yellow>{balance}<gold>.` |
| `invite.summary` | `<gold>You have <white>{count}</white> pending faction invite(s):` |
| `notifications.inbox-header` | `<gold>Missed faction notifications (<white>{count}</white>):` |
| `motd.header` | `<gold>== <yellow>Faction MOTD</yellow> ==` |
| `motd.display` | `<gray>{motd}` |
| `update.available` | `<yellow>Update available for Factions: <white>{current}</white> -> <green>{latest}</green> <gray>({source})` |
| `update.url` | `<gray>Download: <aqua><click:open_url:'{url}'>{url}</click>` |

Raw (non-keyed) messages sent by `EngineChunkChange`/`EngineEconomy` use hard-coded `<red>`/`<green>` strings quoted verbatim in §3.2 and §3.8.

## 8. PlayerModel / FactionModel fields touched (for schema parity)
**PlayerModel:** `getPower/setPower`, `isPowerFrozen`, `getFactionId`, `isInFaction`, `isOverriding`, `getLastActivity`, `getLastDeathAt/setLastDeathAt`, `getDeathStreak/setDeathStreak`, `getAutoTerritoryMode/setAutoTerritoryMode`, `hasTerritoryTitles`, `hasInviteNotifications`, `hasMotdNotifications`, `hasBankTaxNotifications`.
**FactionModel:** `getId/getName/getOwnerId`, `getPowerBoost`, `getBank/setBank`, `getRelationsJson`, `getMotd`, `isNormal`, `isRaidable/setRaidable`, `isShieldActive` (UTC-hour rolling window from `getShieldStartHour`/`getShieldDurationHours`, wraps midnight; duration<=0 or null start → inactive).

## 9. Event priority / ordering summary (protection interplay)
1. WorldGuard (when synced) enforces membership at **NORMAL** for BlockBreak/Place.
2. `EngineProtection` build handlers run at **HIGH, ignoreCancelled=true** — in DB mode they enforce faction rules; in WG-sync mode they only allow (WG already ran).
3. `EngineProtection` ally-unlock handlers run at **HIGHEST, ignoreCancelled=false** — WG-sync only, un-cancel for allies/override admins.
4. PvP at **HIGH, ignoreCancelled=true**. Explosions/fire-spread at **HIGH, ignoreCancelled=true**.
5. Movement engines (`EnginePlayerMove`, `EngineAutoTerritory`) at **MONITOR, ignoreCancelled=true**.
6. Power death hook at **MONITOR, ignoreCancelled=true**. Chat at **HIGHEST, ignoreCancelled=true**.
7. Audit forwarders at **MONITOR, ignoreCancelled=true**.
Join/quit/inventory-close handlers use default (NORMAL) priority.
