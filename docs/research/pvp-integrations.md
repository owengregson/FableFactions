# FableFactions Spec — Third-Party Integrations (`integration/` package)

Clean-room behavioral spec for the `com.pvpindex.factions.integration` package of the
legacy PvPIndex factions plugin. Covers **Vault, WorldGuard, PlaceholderAPI, EssentialsX,
dynmap, LWC/LWCX, DiscordSRV, EzCountdown**. An implementer who has never seen the source
must be able to reproduce every behavior below exactly.

---

## 0. Cross-Cutting Architecture & Conventions

### 0.1 Soft-dependency model
`plugin.yml` declares every integration under `softdepend` (NOT `depend`), so the plugin
loads even when all are absent. Exact `softdepend` list (order preserved):

```
TeamsAPI, Vault, WorldGuard, WorldEdit, PlaceholderAPI, Essentials, EssentialsX,
dynmap, DiscordSRV, EzEconomy, EzCountdown, LWC, LWCX
```

`loadbefore`: `EzShops, EzAuction, EzRTP, EzClean`. `authors` include `Brettflan`.

Note WorldEdit is listed because WorldGuard's region API (`BukkitAdapter`, `BlockVector3`)
lives in WorldEdit classes. Both `Essentials` and `EssentialsX` are listed but the plugin
looks up the Bukkit plugin named **`Essentials`** (EssentialsX registers under that name).

### 0.2 Two isolation strategies (must be preserved)
Integrations fall into two categories by how they avoid `NoClassDefFoundError` at load:

1. **Compile-time-typed classes** — directly `import` provider classes. These must **only
   ever be instantiated after the provider plugin is confirmed present**, and must never be
   referenced from unconditionally-loaded code paths. Applies to:
   `DynmapLayer` (imports `org.dynmap.*`), `WorldGuardTerritoryGuard` /
   `WorldGuardRegionSync` (import `com.sk89q.*`), `EssentialsXInterop` (imports
   `com.earth2me.essentials.*`), `EzCountdownNotifierImpl` (imports
   `com.skyblockexp.ezcountdown.*`), `FactionsPlaceholders` (imports `me.clip.*`),
   `VaultEconomy` (imports `net.milkbowl.vault.*`).
2. **Reflection-based classes** — no provider imports at all; resolve provider classes by
   `Class.forName`/`getMethod` at runtime; safe to load unconditionally. Applies to:
   `DiscordSrvNotifier`, `LwcxInterop`, and the `EzCountdownNotifier` façade (which then
   reflectively loads the typed `EzCountdownNotifierImpl`).

For provider-typed classes the plugin uses a **façade + Noop + factory** trio so bootstrap
code depends only on an interface:
- WorldGuard: `TerritoryGuard` iface, `NoopTerritoryGuard`, `WorldGuardTerritoryGuard`, `TerritoryGuardFactory`.
- EssentialsX: `EssentialsInterop` iface, `NoopEssentialsInterop`, `EssentialsXInterop`, `EssentialsInteropFactory`.
- LWC: `LwcInterop` iface, `NoopLwcInterop`, `LwcxInterop`, `LwcInteropFactory`.
- EzCountdown: `EzCountdownSender` iface, `EzCountdownNotifier` façade (no imports),
  `EzCountdownNotifierImpl` (typed, reflection-loaded).

### 0.3 Detection = plugin-present AND config-toggle
Every integration gate is **AND** of: (a) config boolean toggle, and (b) plugin actually
present (via `PluginManager.getPlugin("<Name>") != null`, sometimes plus a service/class
check). Order of checks varies per integration (documented below). Absence at either gate
degrades to a Noop / no-op / chat-fallback with an `INFO`-level log line.

### 0.4 Bootstrap wiring
Two bootstrap components own integration init:

- **`InfrastructureBootstrapComponent`** (early; can abort startup) initializes, in order:
  `initVault → initEssentialsInterop → initTerritoryGuard → initLwcInterop`. Stores each on
  `InfraRegistry` (`context.infra()`). On `stop()` it calls `lwcInterop.unregister()` then
  closes the DB.
- **`OptionalHooksBootstrapComponent`** (late; never blocks startup) initializes, in order:
  `initBstats → initPlaceholderApi → initDynmap → initEzCountdown → initDiscordSrv`. Always
  returns `true`.
- **`EnginesBootstrapComponent`** wires `WorldGuardRegionSync` (only when sync enabled) and
  injects `VaultEconomy` into `EngineEconomy`, `TerritoryGuard` into `EngineProtection` &
  `EngineAutoTerritory`.

`BootstrapContext` exposes `setVaultEnabled/setDynmapEnabled/setPlaceholderApiEnabled/
setEzCountdownEnabled/setDiscordSrvEnabled/setLwcEnabled(boolean)` — status flags for
metrics/diagnostics; these are set true/false as each hook succeeds/fails.

### 0.5 Config keys — master reference table
All under `config.yml` `integrations:` unless noted. Defaults are the code-level defaults.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `integrations.vault` | bool | `true` | `isVaultEnabled()` (informational; Vault hook always attempts) |
| `integrations.worldguard` | bool | `true` | Enable WG territory guard |
| `integrations.worldguard-sync-regions` | bool | `false` | Mirror claims as WG regions (restart required to toggle) |
| `integrations.dynmap` | bool | `true` | Enable dynmap layer |
| `integrations.placeholderapi` | bool | `true` | (accessor exists; hook actually gated only on plugin presence — see §3) |
| `integrations.essentialsx.enabled` | bool | `false` | Enable EssentialsX teleport interop |
| `integrations.lwc.enabled` | bool | `true` | Master LWC toggle |
| `integrations.lwc.require-build-rights-to-create` | bool | `true` | Cancel protection creation without build rights |
| `integrations.lwc.remove-if-no-build-rights` | bool | `true` | Remove stale protection on interact |
| `integrations.lwc.remove-on-claim-change` | bool | `true` | Purge alien protections after claim change |
| `integrations.discordsrv.enabled` | bool | `false` | Master DiscordSRV toggle |
| `integrations.discordsrv.channel-id` | string | `""` | Target channel; empty ⇒ DiscordSRV main channel |
| `integrations.discordsrv.events.faction-created.enabled` | bool | `true` | |
| `integrations.discordsrv.events.faction-created.message` | string | `**{faction}** was founded!` | |
| `integrations.discordsrv.events.faction-disbanded.enabled` | bool | `true` | |
| `integrations.discordsrv.events.faction-disbanded.message` | string | `**{faction}** was disbanded.` | |
| `integrations.discordsrv.events.relation-ally.enabled` | bool | `true` | |
| `integrations.discordsrv.events.relation-ally.message` | string | `:handshake: **{source}** and **{target}** are now allies!` | |
| `integrations.discordsrv.events.relation-truce.enabled` | bool | `true` | |
| `integrations.discordsrv.events.relation-truce.message` | string | `:white_flag: **{source}** and **{target}** agreed to a truce.` | |
| `integrations.discordsrv.events.relation-enemy.enabled` | bool | `true` | |
| `integrations.discordsrv.events.relation-enemy.message` | string | `:crossed_swords: **{source}** declared war on **{target}**!` | |
| `factions.metrics.bstats.enabled` | bool | `true` | bStats (not in `integration/` pkg; noted for completeness) |
| `factions.metrics.bstats.plugin-id` | int | `31240` | bStats plugin id |

EzCountdown lives in **`notifications.yml`** (`NotificationsConfig`), NOT `config.yml`:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `ezcountdown.enabled` | bool | `true` | Enable EzCountdown display announcements |
| `ezcountdown.announcement-duration-seconds` | long | `8` | Display duration |
| `ezcountdown.display-types` | string list | `["ACTION_BAR"]` (when empty) | e.g. `ACTION_BAR`, `TITLE` |

---

## 1. Vault (Economy)

**Package:** `integration/vault/VaultEconomy.java`. Single concrete class (no iface/Noop).

### 1.1 Detection / enable
- `setup()`: `Bukkit.getPluginManager().getPlugin("Vault")`. If `null` → set internal
  `vaultPresent=false`, return `false`. Else return `true`. Does NOT resolve the economy
  provider yet.
- Bootstrap (`InfrastructureBootstrapComponent.initVault`): construct `VaultEconomy(logger)`,
  call `setup()`. On `false` log `WARNING "Vault not found — economy features will be disabled."`
  and `setVaultEnabled(false)`. On `true` log `INFO "Vault found — economy provider will be
  resolved on first use."` and `setVaultEnabled(true)`. Always store the instance on infra.
- **Lazy provider resolution** — `economy()` (private) resolves on every call via
  `Bukkit.getServicesManager().getRegistration(Economy.class)` and returns
  `rsp != null ? rsp.getProvider() : null`. If `vaultPresent==false` it short-circuits to
  `null`. Rationale: economy plugins (e.g. EzEconomy) may register *after* Vault, so provider
  must be resolved on demand, never cached.
- `isEnabled()` = `economy() != null` (Vault present AND a provider registered).

### 1.2 API surface (net.milkbowl.vault.economy.Economy)
| Method | Vault call | Failure/guard behavior |
|---|---|---|
| `getBalance(Player)` → double | `eco.getBalance(player)` | returns `0` if `economy()==null` |
| `withdraw(Player, amount)` → bool | `eco.withdrawPlayer(player, amount).transactionSuccess()` | returns `false` if `eco==null` **OR `amount <= 0`** |
| `deposit(Player, amount)` → bool | `eco.depositPlayer(player, amount).transactionSuccess()` | returns `false` if `eco==null` **OR `amount <= 0`** |

Never throws; all failures return `false`/`0`.

### 1.3 Consumers & call patterns
**`EngineEconomy`** (faction bank deposit/withdraw/transfer). Key robustness pattern:
Vault availability is re-checked **before each operation AND again inside the commit
window** (comment: "Bug fix #3"). Deposit flow (player wallet → faction bank):
1. Reject `amount <= 0` (`"<red>Amount must be positive."`).
2. `if (!vaultEconomy.isEnabled())` → `"<red>Economy is not available."` return false.
3. `if (!config.isBankEnabled())` → `"<red>Faction banks are disabled."` (`isBankEnabled()`
   aliases `isEconomyEnabled()`).
4. Load faction; if absent → `"<red>Faction not found."`.
5. `getBalance(player) < amount` → `"<red>You do not have enough money."`.
6. Fire `FactionBankTransactionEvent(faction, uuid, DEPOSIT, amount)`; if cancelled return false;
   read possibly-mutated `event.getAmount()` as `finalAmount`.
7. Re-check `isEnabled()`.
8. `withdraw(player, finalAmount)`; on false → `"<red>Could not withdraw money from your
   account."`. Only then credit bank (`faction.setBank(bank+finalAmount)`, save), record
   transaction `"DEPOSIT"`, message `"<green>Deposited <white>%.2f<green> into the faction bank."`.

Withdraw flow (faction bank → player wallet) mirrors deposit but debits bank FIRST, then
`deposit(player, finalAmount)`; **if that fails it rolls back the bank debit** (re-adds and
saves) and sends `"<red>Could not credit your account."`. Transfer (bank→bank) wraps in a
Jaloquent `transaction()` and does not touch Vault wallet at all (checks `isEnabled()` as a
gate only). `StorageException` → `SEVERE` log + `"<red>An internal error occurred."`.

**`CmdWarp`** (teleport to a paid warp): if `warp.hasCost()`:
- `vaultEconomy == null || !vaultEconomy.isEnabled()` → key `warp.cost-no-economy`
  (`"<red>An economy plugin is required to use this warp."`).
- `getBalance(player) < cost` → key `warp.cost-insufficient`
  (`"<red>You need <gold>{cost}</gold> to use this warp (balance: <gold>{balance}</gold>)."`),
  `cost`/`balance` formatted `%.2f`.
- Else `withdraw(player, cost)` (return value ignored), then key `warp.cost-charged`
  (`"<green>Charged <gold>{cost}</gold> for warp <yellow>{name}</yellow>."`) with post-charge
  balance also `%.2f`.

**`CmdPowerBuy`** (`/f power buy <amount>`): gate order — `isPowerBuyEnabled()` (key
`power.buy-disabled`), then `!vaultEconomy.isEnabled()` → key `power.buy-no-vault`
(`"<red>An economy plugin is required to buy power."`). Amount parsed via `MoneyParser.parse`;
must be `>0` and `<= getPowerBuyMaxPerPurchase()` else key `power.buy-invalid-amount`.
`actualAmount = min(amount, maxPower - currentPower)`;
`cost = actualAmount * getPowerBuyCostPerPoint()` (formatted `%.2f`).
`getBalance < cost` → key `power.buy-insufficient-funds`. `withdraw(player, cost)` false →
`"<red>Transaction failed — please try again."`. On success applies power via
`PowerService.Request(..., Source.BUY, ...)` and key `power.buy-success`.

### 1.4 Graceful absence
No Vault ⇒ all mutations return false, balances read 0, and each consumer surfaces a
user-facing "economy required/unavailable" message. No exceptions ever propagate.

---

## 2. WorldGuard (Territory Guard + optional Region Sync)

**Package:** `integration/worldguard/`. Files: `TerritoryGuard` (iface),
`NoopTerritoryGuard`, `WorldGuardTerritoryGuard`, `TerritoryGuardFactory`,
`WorldGuardRegionSync`.

### 2.1 `TerritoryGuard` interface
```
boolean canModifyTerritory(Player player, Location location);   // true = allowed
default boolean syncsBuildProtection()  { return false; }
default boolean isFactionRegion(Location location) { return false; }
```
`NoopTerritoryGuard`: `canModifyTerritory` always `true`; defaults for the rest.

### 2.2 Detection (`TerritoryGuardFactory.create`)
1. `if (!config.isWorldGuardEnabled())` → log `INFO "WorldGuard integration disabled in
   config."` → `NoopTerritoryGuard`.
2. `if (pm.getPlugin("WorldGuard") == null)` → log `INFO "WorldGuard not found — territory
   guard disabled."` → `NoopTerritoryGuard`.
3. Else log `INFO "WorldGuard detected — territory guard enabled."` → return
   `new WorldGuardTerritoryGuard(config.isWorldGuardSyncRegions())`.

Injected into `EngineProtection`, `EngineAutoTerritory`, `CmdWarp`, `CmdWarpSet`.

### 2.3 `WorldGuardTerritoryGuard`
Constructed with `boolean syncEnabled`.
- `canModifyTerritory(player, location)`: wraps `WorldGuardPlugin.inst().wrapPlayer(player)`
  → `LocalPlayer`; `WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()`
  → `RegionQuery`; returns `query.testBuild(BukkitAdapter.adapt(location), localPlayer)`.
  **Any exception → return `true` (fail-open).**
- `syncsBuildProtection()` → returns `syncEnabled`.
- `isFactionRegion(location)`: `false` if `location.getWorld()==null`. Else get the world's
  `RegionManager` via `...getRegionContainer().get(BukkitAdapter.adapt(world))`; `false` if
  null. Compute region id via `regionName(...)`; return `rm.getRegion(regionId) != null`.
  Uses WG's in-memory store (no DB). **Any exception → `false`.**

**Region-name formula (LOAD-BEARING, must match exactly):**
```
regionName(world, chunkX, chunkZ):
  w = world.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_")
  x = chunkX < 0 ? ("n" + (-chunkX)) : String.valueOf(chunkX)
  z = chunkZ < 0 ? ("n" + (-chunkZ)) : String.valueOf(chunkZ)
  return "f_" + w + "_" + x + "_" + z
```
Coordinates are **chunk** coords. Negative → `n` prefix + absolute value. Only `[a-z0-9_]`
in the world segment. Example: world `world_nether`, chunk `(-3, 5)` → `f_world_nether_n3_5`.

### 2.4 `EngineProtection` interaction (WG-sync mode)
`EngineProtection` reads `wgSync = territoryGuard != null && territoryGuard.syncsBuildProtection()`
at construction. In sync mode WG enforces membership natively at `NORMAL` priority; the
plugin's handlers run at `HIGH`/`HIGHEST` `ignoreCancelled=true`:
- `canModifyWgSync(player, block)`: `factions.bypass` perm → true; if
  `territoryGuard.isFactionRegion(block.getLocation())` → true (WG already validated); else
  fall through to DB `canModify(player, chunk)`. Purpose: only wilderness triggers a DB lookup.
- `tryUnlockForAlliedPlayer(player, block)` (`HIGHEST`): if NOT a faction region → return
  false (leave WG's decision). Else `factions.bypass` → true; overriding player → true;
  no-faction → false; else DB relation check to re-permit allies WG denied. This restores
  ally/override access that WG's member-only domain would block.

### 2.5 `WorldGuardRegionSync` (only when `worldguard-sync-regions: true`)
Registered by `EnginesBootstrapComponent` **only if** `cfg.isWorldGuardSyncRegions()` AND
`getPlugin("WorldGuard") != null`: constructs `WorldGuardRegionSync(repos, plugin, logger)`,
calls `register()` (registers Bukkit listener) then `syncAll()` (synchronous startup sync).

**Region cuboid geometry (per claimed chunk):**
```
minX = chunkX << 4 ;  minZ = chunkZ << 4
min = BlockVector3.at(minX,           world.getMinHeight(),     minZ)
max = BlockVector3.at(minX + 15,      world.getMaxHeight() - 1, minZ + 15)
region = new ProtectedCuboidRegion(regionName(world,x,z), min, max)
```
Full-height cuboid spanning the 16×16 chunk. Region **members** = the faction's member
player UUIDs (a `DefaultDomain` with `addPlayer(uuid)` for each). WG then lets only members
build.

**Member UUID resolution (`memberUuidsForFaction`):**
- If factionId is `FactionModel.SAFEZONE_ID` or `WARZONE_ID` → return **empty list** (no
  members ⇒ nobody can build via WG).
- Else map `repos.players().findByFactionId(id)` → `PlayerModel.getId()` → `UUID.fromString`,
  silently dropping ids that aren't valid UUIDs.

**`syncAll()` (startup):** iterate all factions; skip factions with no claims; for each claim
get `Bukkit.getWorld(name)` (skip if null) and its `RegionManager` (skip if null), then
`addOrUpdateRegion(...)`, incrementing `created`. After the loop `saveAllWorlds()` saves each
world's RegionManager once (per-world try/catch → `WARNING`). `StorageException` →
`failed++`, `WARNING`. Final log: `"WorldGuard region sync: <created> regions created/updated
[, <failed> error(s)]."`

**Events** (all `EventPriority.MONITOR`):
| Event | ignoreCancelled | Action |
|---|---|---|
| `FactionChunkClaimEvent` | true | recompute member UUIDs, `addOrUpdateRegion`, then `saveAsync(rm, world)` |
| `FactionChunkUnclaimEvent` | true | if region exists, `rm.removeRegion(id)` + `saveAsync` |
| `FactionJoinEvent` | true | `updateMemberInFactionRegions(faction, playerUUID, add=true)` |
| `FactionLeaveEvent` | **false** | `updateMemberInFactionRegions(..., add=false)` |
| `FactionDisbandEvent` | true | `removeAllRegionsForFaction(faction.getId())` |

- `addOrUpdateRegion` **replaces** any existing region of that id (`rm.addRegion` overwrites).
- `updateMemberInFactionRegions`: for every claim of the faction, look up the region and
  `region.getMembers().addPlayer/removePlayer(uuid)`; collect affected worlds; `saveAsync`
  once per affected world.
- `removeAllRegionsForFaction`: for each claim `rm.removeRegion(id)`; `saveAsync` per world.
- `saveAsync(rm, world)`: `Bukkit.getScheduler().runTaskAsynchronously(plugin, rm::save)` with
  a try/catch → `WARNING "WG sync: async save failed for world <name>"`. **All incremental
  saves are async; only startup `syncAll` saves synchronously.**
- `getRegionManager(world)`: `...getRegionContainer().get(BukkitAdapter.adapt(world))`;
  exception → `null`.
- All `StorageException`s in handlers → `WARNING` log, swallowed.

### 2.6 `CmdWarpSet` / `CmdWarp` guard usage
`CmdWarpSet`: after officer check, `if (!territoryGuard.canModifyTerritory(player, target))`
→ key `custom.warp.set-protected` (`"<red>You cannot set a warp in this protected region."`).
(`CmdWarp` uses only Essentials/Vault, not the guard directly.)

### 2.7 Graceful absence
Disabled or WG absent ⇒ `NoopTerritoryGuard` (`canModifyTerritory` always true,
`syncsBuildProtection`/`isFactionRegion` false) so `EngineProtection` uses its plain DB path
and warps/auto-claim are never blocked by WG.

---

## 3. PlaceholderAPI

**Package:** `integration/placeholderapi/FactionsPlaceholders.java` extends
`me.clip.placeholderapi.expansion.PlaceholderExpansion`.

### 3.1 Detection / registration
`OptionalHooksBootstrapComponent.initPlaceholderApi`: if `getPlugin("PlaceholderAPI") == null`
→ `setPlaceholderApiEnabled(false)`, return (no log). Else
`new FactionsPlaceholders(repos, logger).register()`, log `INFO "PlaceholderAPI hooked."`,
`setPlaceholderApiEnabled(true)`. **Note:** the `integrations.placeholderapi` config accessor
exists (`isPlaceholderApiEnabled()`, default true) but the hook is gated **only on plugin
presence** — the config toggle is not consulted here.

Expansion metadata: identifier `pvpindex`, author `gyvex`, version `1.0.0`, `persist()` =
`true` (survives PlaceholderAPI reloads).

### 3.2 Placeholders — `%pvpindex_<param>%`
`onRequest(OfflinePlayer, params)` catches `StorageException` → logs `WARNING
"PlaceholderAPI lookup failed for <params>"` and returns `""`. It first loads
`PlayerModel` by `player.getUniqueId().toString()`. Resolution is an exact-match `switch`:

| Param | Output | Not-in-faction / empty fallback |
|---|---|---|
| `faction_name` | faction's `getName()` | `"None"` (also if faction lookup empty) |
| `faction_power` | `(int)` sum of `PlayerModel.getPower()` over all faction members, as string | `"0"` |
| `faction_members` | member count (`findByFactionId(id).size()`) | `"0"` |
| `faction_land` | claimed chunk count (`board().countByFactionId(id)`) | `"0"` |
| `faction_bank` | `String.valueOf(faction.getBank())` (raw double) | `"0.0"` (also if faction empty) |
| `player_power` | `String.valueOf(PlayerModel.getPower())` (raw double) | `"0"` (no player model) |
| `player_role` | rank `getName()` | `"None"` |
| `player_role_prefix` | rank `getPrefix()` | `""` (empty string) |
| any other | `null` (PlaceholderAPI treats as unhandled) | — |

Notes:
- `faction_power` is the **integer truncation** of the summed member powers.
- `faction_bank` and `player_power` output **raw double `toString`** (e.g. `0.0`, `12.5`),
  not formatted.
- Role resolution (`resolveRole`): empty if no player model, or `rankId` is null/blank;
  else `repos.ranks().find(rankId)`.
- "in a faction" = `PlayerModel.isInFaction()`.

### 3.3 Graceful absence
No PlaceholderAPI ⇒ expansion never registered; placeholders render literally. No other code
depends on it.

---

## 4. EssentialsX (Teleport / Jail / Vanish interop)

**Package:** `integration/essentials/`. Files: `EssentialsInterop` (iface),
`NoopEssentialsInterop`, `EssentialsXInterop`, `EssentialsInteropFactory`.

### 4.1 Interface
```
boolean teleport(Player, Location dest, Runnable onSuccess, Runnable onFailure);
boolean isJailed(Player);
boolean isVanished(Player);
```
`teleport` contract: returns `true` when it **handles** the request (and will fire exactly one
of `onSuccess`/`onFailure` asynchronously on completion); returns `false` when the caller must
fall back to a native `player.teleport(...)`.

`NoopEssentialsInterop`: `teleport` → `false`; `isJailed`/`isVanished` → `false`.

### 4.2 Detection (`EssentialsInteropFactory.create`)
1. `if (!config.isEssentialsXEnabled())` (`integrations.essentialsx.enabled`, **default false**)
   → `INFO "EssentialsX interop disabled in config."` → Noop.
2. `Plugin essentials = pm.getPlugin("Essentials")`; if null → `INFO "EssentialsX not found —
   interop disabled."` → Noop.
3. `if (!(essentials instanceof IEssentials))` → `WARNING "Found plugin named 'Essentials' but
   it does not implement IEssentials — interop disabled."` → Noop.
4. Else `INFO "EssentialsX <version> detected — home/warp teleport interop enabled."` →
   `new EssentialsXInterop((IEssentials) essentials, logger)`.

### 4.3 `EssentialsXInterop` (com.earth2me.essentials API)
- `teleport`: `user = essentials.getUser(player)`; if null return `false`.
  `user.setLastLocation()` (so Essentials `/back` returns here after the teleport).
  Create `CompletableFuture<Boolean>`; call
  `user.getAsyncTeleport().now(destination, /*center=*/true,
  PlayerTeleportEvent.TeleportCause.COMMAND, future)`. On `future.whenComplete((ok,err))`:
  - `err != null` → `WARNING "EssentialsX async teleport threw: <msg>"`, run `onFailure`.
  - `Boolean.TRUE.equals(ok)` → run `onSuccess`, else run `onFailure`.
  Return `true` (handled).
- `isJailed`: `user != null && user.isJailed()`.
- `isVanished`: `user != null && user.isVanished()`.

### 4.4 Teleport routing at call sites (`CmdHome`, `CmdWarp`)
Pattern (identical in both): first `if (essentialsInterop.isJailed(player))` block a teleport
(`CmdHome` key `home.jailed` `"<red>You cannot teleport home while jailed."`; `CmdWarp` key
`warp.jailed` `"<red>You cannot use warps while jailed."`). Then:
```
if (essentialsInterop.teleport(player, dest, onSuccess, onFailure)) return;   // handled async
player.teleport(dest);                                                         // native fallback
<send success message>
```
- `onSuccess`/`onFailure` are the localized success/failure messages
  (`home.teleported`/`home.teleport-failed`; `warp.teleported`/`warp.teleport-failed`).
- With Noop interop, `teleport` returns false → native fallback runs synchronously and the
  success message is sent immediately.
- `isVanished` is exposed by the API but **has no caller** in the codebase (present for
  completeness / future use).

### 4.5 Graceful absence
Disabled/absent/wrong-type ⇒ Noop ⇒ native `player.teleport` path everywhere; jail checks
always return false (no jail blocking).

---

## 5. dynmap (Territory Layer)

**Package:** `integration/dynmap/DynmapLayer.java implements Listener`. Uses `org.dynmap.*`
markers API.

### 5.1 Detection / start
`OptionalHooksBootstrapComponent.initDynmap`: `getPlugin("dynmap")`; if null or
`!isEnabled()` → `setDynmapEnabled(false)`, return. Else construct
`new DynmapLayer(repos, taskScheduler, logger)` and call `layer.start(plugin)` inside a
try/catch (`WARNING "Failed to hook dynmap: <msg>"` → `setDynmapEnabled(false)`). On success
log `INFO "dynmap hooked - faction territory layer enabled."`, `setDynmapEnabled(true)`.

> Note: `initDynmap` gates only on the **dynmap plugin being enabled**, not on the
> `integrations.dynmap` config toggle (accessor exists but isn't consulted here).

`start(plugin)`:
1. `getPlugin("dynmap")`; null or not enabled → return `false`.
2. Cast to `DynmapAPI`; `markerApi = dynmapApi.getMarkerAPI()`; if null → `WARNING "dynmap
   MarkerAPI not ready — faction territory layer skipped."` return `false`.
3. **Delete any stale marker set** with id `pvpindex_factions` (from a prior `/fa reload`).
4. `markerSet = markerApi.createMarkerSet("pvpindex_factions", "Factions", null, false)`;
   null → `WARNING "Failed to create dynmap faction marker set."` return `false`.
5. `markerSet.setHideByDefault(false)` and `markerSet.setLayerPriority(5)`.
6. Schedule `loadAllClaims` **one tick later** (via `taskScheduler.runSync(...)` if present,
   else `getScheduler().runTask(plugin, ...)`) so dynmap finishes its own init.
7. Register `this` as a Bukkit listener. Return `true`.

Constants: layer id `pvpindex_factions`, label `Factions`.

### 5.2 Colour palette (LOAD-BEARING)
8-entry RGB palette (0xRRGGBB):
```
0x3399ff, 0xff6633, 0x33cc33, 0xff3399, 0x9966ff, 0xffcc00, 0x00cccc, 0xff6600
```
`factionColor(id) = PALETTE[Math.abs(id.hashCode()) % PALETTE.length]` — deterministic per
faction id.

### 5.3 Marker geometry & id
Marker id format: `factionId~worldName~chunkX~chunkZ` (`~` separator — disjoint from UUID
hyphens / world chars / digits). Area marker corners (a 16×16 square, y ignored):
```
x0 = chunkX * 16.0 ;  z0 = chunkZ * 16.0
xCorners = {x0, x0,       x0+16, x0+16}
zCorners = {z0, z0+16,    z0+16, z0}
createAreaMarker(id, factionName, /*markup=*/false, world, xCorners, zCorners, /*persistent=*/false)
```
Style: `marker.setFillStyle(0.35, color)` (35% fill opacity), `marker.setLineStyle(1, 1.0,
color)` (1px, full opacity). Marker label = faction name. `addChunkMarker` first deletes any
existing marker with the same id (handles ownership change).

### 5.4 `loadAllClaims` (startup, +1 tick)
No-op if `markerSet==null`. Deletes all existing area markers, then for every faction
(`repos.factions().findAll()`) and every claim (`repos.board().findByFactionId`) calls
`addChunkMarker`. `StorageException` → `WARNING "Failed to load faction claims for dynmap
layer"`.

### 5.5 Live event handlers (all `EventPriority.MONITOR`)
| Event | ignoreCancelled | Behavior |
|---|---|---|
| `FactionChunkClaimEvent` | true | `addChunkMarker(factionId, factionName, world, x, z)` |
| `FactionChunkUnclaimEvent` | true | `removeChunkMarker(world, x, z)` |
| `FactionDisbandEvent` | true | delete every marker whose id `startsWith(factionId + "~")` |

`removeChunkMarker`: faction id isn't on the unclaim event, so it scans area markers for one
whose id **`endsWith("~" + world + "~" + x + "~" + z)`** and deletes the first match (breaks).
All handlers early-return if `markerSet == null`.

### 5.6 Graceful absence
No dynmap ⇒ `DynmapLayer` never instantiated (must not be class-referenced without dynmap on
classpath — would `NoClassDefFoundError`). No markers, events untouched.

---

## 6. LWC / LWCX (Chest-Protection interop)

**Package:** `integration/lwc/`. Files: `LwcInterop` (iface), `NoopLwcInterop`, `LwcxInterop
implements LwcInterop, Listener`, `LwcInteropFactory`. **Fully reflection-based** — no
`com.griefcraft.*` imports.

### 6.1 Interface & factory
`LwcInterop`: `void register(Plugin)`, `void unregister()`. `NoopLwcInterop`: both no-op.

`LwcInteropFactory.create(plugin, config, repos, scheduler, logger)`:
1. `if (!config.isLwcEnabled())` (`integrations.lwc.enabled`, default true) → `INFO "LWC
   integration disabled in config."` → Noop.
2. `lwc = pm.getPlugin("LWC"); lwcx = pm.getPlugin("LWCX")`; if both null → `INFO "LWC/LWCX
   not found - integration disabled."` → Noop.
3. Else `INFO "LWC/LWCX detected - integration enabled."` → `new LwcxInterop(repos, config,
   scheduler, logger)`.

`InfrastructureBootstrapComponent.initLwcInterop` stores it and immediately calls
`lwcInterop.register(plugin)`; `setLwcEnabled(!(interop instanceof NoopLwcInterop))`. On
plugin stop, `lwcInterop.unregister()` is called.

### 6.2 Reflection target classes
```
EVENT_REGISTER = "com.griefcraft.scripting.event.LWCProtectionRegisterEvent"
EVENT_INTERACT = "com.griefcraft.scripting.event.LWCProtectionInteractEvent"
LWC main        = "com.griefcraft.lwc.LWC"  (static getInstance())
```

### 6.3 `register(Plugin)`
Idempotent (guarded by `registered`). Resolves the two event classes via `Class.forName`.
Registers a single `EventExecutor` (`(listener,event) -> onLwcEvent(event)`) for BOTH event
classes at `EventPriority.HIGH`, `ignoreCancelled=true`, and also `registerEvents(this)` for
the `@EventHandler`-annotated `onChunkClaim`. Sets `registered=true`, logs `INFO "LWC
integration listeners registered."`.
- `ClassNotFoundException` → `INFO "LWC events not found - integration listeners not
  registered."` (soft — LWC present but event classes missing).
- Other exception → `WARNING "Failed to register LWC integration listeners"`.

`unregister()`: if registered, `HandlerList.unregisterAll(this)` (swallow exceptions),
`registered=false`.

### 6.4 Behavior A — protection creation gating
On `LWCProtectionRegisterEvent` (`onProtectionRegister`), gated by
`config.isLwcRequireBuildRightsToCreate()` (default true). Reflectively read `getPlayer()`
and `getBlock()`; if either null return. If `canModify(player.getUniqueId(),
block.getChunk())` → allow. Else reflectively `setCancelled(true)` — blocks creating a lock
where the player lacks build rights.

### 6.5 Behavior B — stale protection removal on interact
On `LWCProtectionInteractEvent` (`onProtectionInteract`), gated by
`config.isLwcRemoveIfNoBuildRights()` (default true). Reflectively `getProtection()` →
`getBlock()`, `getOwner()` (owner is a name or UUID string). Resolve owner UUID
(`resolveUuid`). If owner **can** modify that chunk → return (leave it). Otherwise:
1. `protection.remove()` (reflectively) — deletes the lock.
2. Resolve the event's nested `Result` enum (`resolveCancelResult`) and
   `event.setResult(Result.CANCEL)` so the interaction is cancelled.
3. If actor present, message via MiniMessage:
   `"<yellow>Removed stale LWC protection because the owner no longer has build rights."`

### 6.6 Behavior C — bulk cleanup after claim ownership change
`@EventHandler(MONITOR, ignoreCancelled=true) onChunkClaim(FactionChunkClaimEvent)`, gated by
`config.isLwcRemoveOnClaimChange()` (default true). Calls `cleanupChunkAsync(newOwnerFactionId,
world, x, z)`.
- **Scheduling:** run **1 tick later**, then hop to an async thread. With scheduler:
  `taskScheduler.runSyncLater(() -> taskScheduler.runAsync(() -> cleanupChunkSync(...)), 1L)`.
  Without: `Bukkit.getScheduler().runTaskLater(plugin, () -> runTaskAsynchronously(plugin,
  () -> cleanupChunkSync(...)), 1L)`.
- **Per-chunk lock:** `chunkLocks` is a `ConcurrentHashMap<String,Object>` keyed
  `world:chunkX:chunkZ`; `cleanupChunkSync` `computeIfAbsent`s a monitor and `synchronized`
  on it, removing the key in `finally` (prevents concurrent cleanup of the same chunk).
- **Logic:** load all LWC protections in the chunk (`getProtectionsInChunk`); for each,
  resolve owner UUID; look up `PlayerModel`; **keep** the protection if the owner is a member
  of the new-owner faction (`factionId.equals(pm.getFactionId())`); otherwise `remove()` it.
  Any exception → `FINE "Failed LWC cleanup for <key>"`.

`getProtectionsInChunk(world, x, z)` (reflection):
```
lwc = LWC.getInstance()
db  = lwc.getPhysicalDatabase()
xmin = x*16 ; xmax = xmin+15 ; zmin = z*16 ; zmax = zmin+15
db.loadProtections(String world, int xmin, int xmax, int 0, int 255, int zmin, int zmax)
```
(y-range hard-coded `0..255`.) Returns the list or `List.of()` if not a list.

### 6.7 `canModify(playerUuid, chunk)` — faction build-rights check (LWC's own model)
1. Load `PlayerModel` and `BoardEntry` for the chunk.
2. If chunk **not claimed** (`entry.isEmpty()`) → **true** (wilderness = anyone).
3. If claim faction is `SAFEZONE_ID` or `WARZONE_ID` → **false** (nobody, via LWC).
4. If player not in a faction → **false**.
5. If player's faction == claim faction → **true**.
6. Else load both factions; if either missing → false; else **true only when the player's
   faction has relation `ALLY` toward the claim faction** (relation parsed from
   `getRelationsJson`). TRUCE/NEUTRAL/ENEMY → false.

**Relation parsing** (`getRelation`): the interop parses `relationsJson` **by hand** (not a
JSON lib): searches for the literal token `"<otherFactionId>":"`, reads up to the next `"`,
`Relation.valueOf(...)`; any miss/parse error → `Relation.NEUTRAL`.

**`resolveUuid(ownerName)`:** null/blank → null. Try `UUID.fromString`. Else
`Bukkit.getPlayerExact(name)` (online) → its UUID; else `Bukkit.getOfflinePlayer(name)` →
its UUID (null-guarded).

### 6.8 Reflection helpers
`invoke(target, method, expectedType)` — no-arg call, returns null on any failure or
type-mismatch. `invokeVoid(target, method[, argType, arg])` — best-effort, swallows all
exceptions. `resolveCancelResult(eventClass)` — finds a nested enum named `Result`, returns
`Result.CANCEL` or null. `onLwcEvent(event)` dispatches by
`registerEventClass.isAssignableFrom` / `interactEventClass.isAssignableFrom`; exceptions →
`FINE "LWC event handling failed"`.

### 6.9 Graceful absence
LWC disabled or absent ⇒ Noop (no listeners). LWC present but event classes unresolvable ⇒
listeners not registered (INFO). All reflection failures are swallowed at FINE/WARNING; LWC
issues never break faction operations.

---

## 7. DiscordSRV (Faction event broadcasts)

**Package:** `integration/discordsrv/`. Files: `DiscordSrvNotifier` (reflection, no imports),
`DiscordSrvFactionListener implements Listener`.

### 7.1 Detection (`OptionalHooksBootstrapComponent.initDiscordSrv`)
1. `if (!config.isDiscordSrvEnabled())` (`integrations.discordsrv.enabled`, default false) →
   `INFO "DiscordSRV integration disabled in config."`, `setDiscordSrvEnabled(false)`, return.
2. `notifier = new DiscordSrvNotifier(logger, config.getDiscordSrvChannelId())`.
3. `if (!notifier.setup())` → `INFO "DiscordSRV not found — faction event broadcasts
   disabled."`, `setDiscordSrvEnabled(false)`, return.
4. Store notifier on infra; register `new DiscordSrvFactionListener(notifier, config)`;
   `setDiscordSrvEnabled(true)`; `INFO "DiscordSRV hooked — faction events will be posted to
   Discord."`.

`DiscordSrvNotifier.setup()`: `getPlugin("DiscordSRV") == null` → false. Else
`Class.forName("github.scarsz.discordsrv.DiscordSRV")`; success → `available=true`, true;
`ClassNotFoundException` → `WARNING "DiscordSRV plugin found but class not loadable: <msg>"`,
false. `isEnabled()` = `available`.

Main class constant (exact): `github.scarsz.discordsrv.DiscordSRV` (note: **no** leading
`com.`).

### 7.2 `sendMessage(String message)` (reflection, JDA 4)
No-op if `!available`. Else, in a try/catch (any exception → `WARNING "DiscordSRV message send
failed: <msg>"`):
```
dsrvClass = Class.forName("github.scarsz.discordsrv.DiscordSRV")
dsrv      = dsrvClass.getMethod("getPlugin").invoke(null)          // static getPlugin()
channel   = resolveChannel(dsrv)                                   // may be null → return
sendMsg   = channel.getClass().getMethod("sendMessage", CharSequence.class)
restAction= sendMsg.invoke(channel, message)                       // JDA RestAction
restAction.getClass().getMethod("queue").invoke(restAction)        // fire-and-forget
```
`resolveChannel(dsrv)`:
- If `channelId` non-blank: `jda = dsrv.getJda()`; if non-null,
  `jda.getTextChannelById(String channelId)`; if found return it; else `WARNING "DiscordSRV:
  channel '<id>' not found — using main channel."` and fall through.
- Fallback: `dsrv.getMainTextChannel()`.

Messages are plain text / Discord markdown (emoji shortcodes, `**bold**`) — **no MiniMessage
tags** (that's why config message defaults use `:handshake:` etc., not `<green>`).

### 7.3 Which messages fire on which events
**`DiscordSrvFactionListener`** (both `EventPriority.MONITOR, ignoreCancelled=true`):
| Event | Config gate | Sent message |
|---|---|---|
| `FactionCreateEvent` | `isDiscordSrvFactionCreatedEnabled()` (default true) | `getDiscordSrvFactionCreatedMessage()` with `{faction}` → `event.getFaction().getName()` |
| `FactionDisbandEvent` | `isDiscordSrvFactionDisbandedEnabled()` (default true) | `getDiscordSrvFactionDisbandedMessage()` with `{faction}` replaced |

**Relations fire from `CmdRelation.sendRelationAnnouncement`** (NOT the listener). After a
relation change is confirmed, and only if `discordSrvNotifier != null && isEnabled() &&
factionsConfig != null`:
| Relation | Gate | Template |
|---|---|---|
| `ENEMY` | `isDiscordSrvRelationEnemyEnabled()` | `getDiscordSrvRelationEnemyMessage()` |
| `ALLY` | `isDiscordSrvRelationAllyEnabled()` | `getDiscordSrvRelationAllyMessage()` |
| `TRUCE` (else) | `isDiscordSrvRelationTruceEnabled()` | `getDiscordSrvRelationTruceMessage()` |
Placeholders `{source}`/`{target}` → faction display names (name, or id if name blank).
**Timing subtlety:** ALLY and TRUCE announcements are only reached when the relation becomes
**mutual** (both sides set it); ENEMY announces immediately on set (see §9). NEUTRAL never
announces to Discord.

### 7.4 Graceful absence
Disabled/absent ⇒ notifier stays null on infra (or `available=false`); listener never
registered; `CmdRelation` null-guards the notifier. No Discord traffic, no errors.

---

## 8. EzCountdown (Ephemeral display announcements)

**Package:** `integration/ezcountdown/`. Files: `EzCountdownSender` (iface, no imports),
`EzCountdownNotifier` (façade, no imports, reflection-loads impl), `EzCountdownNotifierImpl`
(typed, `com.skyblockexp.ezcountdown.*`).

### 8.1 Detection (`OptionalHooksBootstrapComponent.initEzCountdown`)
```
notifier = new EzCountdownNotifier(logger)
if (!notificationsConfig.isEzCountdownEnabled() || !notifier.setup()) {
    INFO "EzCountdown not found or disabled — faction announcements will use chat only."
    infra.setEzCountdownNotifier(notifier); setEzCountdownEnabled(false); return;
}
infra.setEzCountdownNotifier(notifier); setEzCountdownEnabled(true);
INFO "EzCountdown hooked — faction relation announcements enabled."
```
(The notifier instance is always stored on infra, enabled or not.)

`EzCountdownNotifier.setup()`: `getPlugin("EzCountdown") == null` → false. Else reflectively
`Class.forName("com.pvpindex.factions.integration.ezcountdown.EzCountdownNotifierImpl")
.getDeclaredConstructor(Logger.class).newInstance(logger)` → cast to `EzCountdownSender`
delegate; return true. Any exception → `WARNING "Failed to load EzCountdown integration:
<msg>"`, false. `isEnabled()` = `delegate != null && delegate.isEnabled()`.

`EzCountdownNotifierImpl.isEnabled()` = its `api() != null`, where `api()` =
`Bukkit.getServicesManager().getRegistration(EzCountdownApi.class).getProvider()` (null-safe).

### 8.2 `sendAnnouncement(message, durationSeconds, displayTypes)`
Façade delegates to impl if `delegate != null`. Impl: get `EzCountdownApi` (return if null);
parse display types; build `Notification.builder().duration(durationSeconds)
.displays(EnumSet<DisplayType>).message(message).build()`; `api.sendNotification(notif)`.
Exception → `WARNING "EzCountdown sendNotification failed: <msg>"`. No YAML countdown entry is
created (ephemeral only). `message` is MiniMessage-formatted.

**Display-type parsing (`parseDisplayTypes`):** null/empty list → `EnumSet.of(ACTION_BAR)`.
Else for each name `DisplayType.valueOf(name.toUpperCase(Locale.ROOT))`; unknown →
`WARNING "EzCountdown: unknown display type '<name>' — skipping."`. If the result is empty →
default `EnumSet.of(ACTION_BAR)`.

### 8.3 Consumer — relation announcements (`CmdRelation.sendRelationAnnouncement`)
For ALLY/TRUCE (mutual) and ENEMY changes, builds a localized message:
- ENEMY: key `ezcountdown.relation-enemy`, default `"<red>⚔ {source} declared war on
  {target}!"` (⚔).
- ALLY/TRUCE: key `ezcountdown.relation-<ally|truce>`, default
  `"<green>🤝 {source} and {target} are now <relation.displayName>!"` (🤝).
Then `{source}`/`{target}` are replaced with faction display names.

Routing (LOAD-BEARING branch):
```
useEzCountdown = ezCountdownNotifier != null
              && ezCountdownNotifier.isEnabled()
              && notificationsConfig != null
              && notificationsConfig.isEzCountdownEnabled();
if (useEzCountdown)
    ezCountdownNotifier.sendAnnouncement(msg,
        notificationsConfig.getEzCountdownDurationSeconds(),      // default 8
        notificationsConfig.getEzCountdownDisplayTypes());        // default [ACTION_BAR]
else
    Bukkit.getOnlinePlayers().forEach(p -> MsgUtil.send(p, msg)); // chat fallback to ALL online
```

### 8.4 Graceful absence
Disabled/absent ⇒ `isEnabled()` false ⇒ relation announcements fall back to a **server-wide
chat broadcast** to all online players. Never throws.

---

## 9. Cross-Feature Interaction: Relation Change Fan-Out

`CmdRelation` (`/f relation <faction> <ally|truce|neutral|enemy>`, alias `relationship`, perm
`factions.cmd.relation`) is the single fan-out point tying EzCountdown + DiscordSRV together.
Flow relevant to integrations:
1. Requires the sender to be in a faction and officer-or-above.
2. Rejects self-target, unknown relation (`"<red>Invalid relation. Use ally, truce, neutral,
   or enemy."`), and `MEMBER`.
3. `factionService.setRelation(...)`; on empty → key `relation.set-failed`.
4. **ALLY/TRUCE:** if now **mutual** (both factions store the same relation toward each other,
   parsed from each `relationsJson`), send confirmation + notify target members + call
   `sendRelationAnnouncement(...)`. If **not** mutual, only a pending-wish message is sent —
   **no announcement** (so no EzCountdown/Discord broadcast until confirmed).
5. **ENEMY:** always calls `sendRelationAnnouncement(...)` (unilateral war declaration).
6. **NEUTRAL:** notifies members but **never announces** (no EzCountdown, no Discord).

`sendRelationAnnouncement` then does BOTH: (a) EzCountdown-or-chat broadcast (§8.3) and (b)
DiscordSRV post (§7.3) — independently gated. Command wiring
(`CommandsBootstrapComponent`) constructs `CmdRelation(factionSvc, ezCountdownNotifier,
notificationsConfig, discordSrvNotifier, factionsConfig)`; any of the notifiers may be null
and each is null-guarded.

---

## 10. `InfraRegistry` storage (defaults matter)
`InfraRegistry` holds each integration handle with **safe defaults** so consumers never NPE
before bootstrap wires the real one:
- `essentialsInterop = new NoopEssentialsInterop()` (default)
- `territoryGuard` — set by factory (defaults conceptually to Noop; set during infra init)
- `lwcInterop` — set by factory
- `vaultEconomy`, `ezCountdownNotifier`, `discordSrvNotifier` — set during bootstrap; the
  latter two may remain null (consumers null-check).
Getters/setters: `get/setVaultEconomy`, `get/setEssentialsInterop`, `get/setTerritoryGuard`,
`get/setLwcInterop`, `get/setEzCountdownNotifier`, `get/setDiscordSrvNotifier`.

---

## 11. Event dependency summary (custom faction events consumed by integrations)
| Event | dynmap | WG sync | LWC | DiscordSRV |
|---|---|---|---|---|
| `FactionChunkClaimEvent` | add marker | add/update region + async save | bulk chunk cleanup | — |
| `FactionChunkUnclaimEvent` | remove marker | remove region + async save | — | — |
| `FactionDisbandEvent` | remove all faction markers | remove all faction regions | — | broadcast (via listener) |
| `FactionCreateEvent` | — | — | — | broadcast |
| `FactionJoinEvent` | — | add member to regions | — | — |
| `FactionLeaveEvent` | — | remove member from regions | — | — |
| `FactionBankTransactionEvent` | — | — | — | — (fired by EngineEconomy for Vault flows) |

All integration listeners use `EventPriority.MONITOR`; all `ignoreCancelled=true` EXCEPT
`WorldGuardRegionSync.onFactionLeave` (`ignoreCancelled=false`). `EngineProtection`'s LWC/WG
build handlers run at `HIGH`/`HIGHEST`.

---

## 12. Reimplementation checklist (invariants)
1. Provider-typed classes (dynmap/WG/EssentialsX/EzCountdownImpl/PlaceholderAPI/Vault) must be
   loaded only after the provider is confirmed present; reflection-typed classes
   (DiscordSRV/LWCX/EzCountdown façade) safe to load always.
2. Every enable gate = config toggle AND plugin presence (except PlaceholderAPI & dynmap init,
   which gate on presence only despite having config accessors).
3. Preserve exact string constants: dynmap layer id `pvpindex_factions`/label `Factions`,
   palette, marker-id `~` format; WG region-name `f_<world>_<x>_<z>` with `n`-prefixed
   negatives; PlaceholderAPI identifier `pvpindex`; DiscordSRV class `github.scarsz.discordsrv.DiscordSRV`.
4. Preserve failure modes: WG `canModifyTerritory` fails **open** (true); Vault mutations fail
   on non-positive amounts; reflection failures swallowed (LWC FINE/WARNING, DiscordSRV WARNING).
5. Preserve async/tick semantics: dynmap `loadAllClaims` +1 tick; WG incremental saves async /
   startup sync synchronous; LWC cleanup +1 tick then async under per-chunk lock.
6. Preserve teleport routing: try interop (async, `true`=handled) then native fallback; jail
   check precedes teleport at every call site.
7. Preserve relation fan-out gating: ALLY/TRUCE announce only when mutual; ENEMY always;
   NEUTRAL never; EzCountdown-or-chat + Discord independently gated.
</content>
</invoke>
