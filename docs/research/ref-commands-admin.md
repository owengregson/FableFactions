# FableFactions Spec — Admin, Power, Flag, Role & Relation Commands

Clean-room behavioral spec derived from `the reference implementation`. Covers the command packages
`command/sub/admin`, `command/sub/power`, `command/sub/flag`, `command/sub/role`,
`command/sub/relation` plus the parent `CmdRelation`, `CmdRole`, `CmdFlag` group commands and
all supporting types (`FactionFlag`, `Relation`, `FactionAuditAction`, `RankModel`,
`RankAuthority`, `FlagService`, `PowerService`, and the `FactionService` role/relation logic).

An implementer should be able to reproduce every command exactly from this document.

---

## 0. Command Framework & Registration

There are **two separate command trees** with **two separate registries**:

| Base command | Aliases | Registry | Executor | Contains |
|---|---|---|---|---|
| Player tree | `f`, `faction`, `factions` | `commandRegistry` | `FactionCommandExecutor` | user commands: `relation`, `power`, `powerhistory`, `role`, `flag`, `audit`, … |
| Admin tree | `fa`, `factionadmin` | `adminRegistry` | `AdminCommandExecutor` | `bypass`, `claim`, `unclaim`, `disband`, `reload`, `safezone`, `warzone`, `shield`, `flag`, `audit`, `power`, `info`, `help` |

Thus admin commands are invoked as e.g. `/fa power set …`, `/fa flag …`, `/fa disband …`.
Player commands are invoked as `/f relation …`, `/f role …`, `/f flag …`, `/f powerhistory …`.

### Base class `FactionCommand`
Each command declares:
- **name** (`super("name")`), optional **aliases** (`setAliases(...)`).
- **permission** (`setPermission(node)`) — checked before `perform`. `null` = no perm required.
- **description**, **required args** (`setRequiredArgs(...)`) and **optional args** (`setOptionalArgs(...)`) — used for usage strings and help output only (they are display metadata, not enforced parsing).
- **requiresPlayer** (`setRequiresPlayer(true)`) — when true and sender is console, the framework rejects with a "player only" message before `perform` runs. Default is `false` unless set. Commands cast `ctx.getSender()` to `Player` inside `perform`.
- **children** (`addChild(cmd)`) — subcommands dispatched by first arg; if no child matches, the parent's own `perform` runs.
- **complete(ctx, argIndex)** — tab completion per positional arg index (0-based).

### `CommandContext` accessors
`ctx.arg(i)` returns the i-th positional arg **or empty string `""`** if absent (never null).
`ctx.getArgs()` returns the full `List<String>` of args to this command level.
`ctx.getSender()`, `ctx.isPlayer()`, `ctx.getConfig()` (→ `FactionsConfig`), `ctx.getRepos()` (→ `Repositories`),
`ctx.getLogger()`, `ctx.getPlugin()`.

### Option parsing (`parseArguments(rawArgs, valuedOptionNames)`)
Used by audit commands. Splits `rawArgs` into positional args and `--name=value` / `--name value` options.
- Tokens **not** starting with `--` → positional.
- `--name=value` or `--name value` (next token consumed) → option. Names lowercased.
- Unknown option name (not in `valuedOptionNames`) → error `"<red>Unknown option: <white>--{name}"`.
- Missing value (no `=`, and next token is missing or starts with `--`, or value blank) → error `"<red>Missing value for option: <white>--{name}"`.
- Returns `ParsedCommandArgs` with `positionalArgs()`, `optionValue(name)`, `hasError()`, `errorMessage()`.

### Messaging (`MsgUtil`)
- `MsgUtil.send(sender, miniMessageString)` — raw MiniMessage send.
- `MsgUtil.sendKey(sender, key, defaultText, k1, v1, k2, v2, …)` — looks up localized message by key, falling back to `defaultText`, then substitutes `{k}` placeholders.
- `MsgUtil.message(sender, key, defaultText)` — resolve string without sending.
- `MsgUtil.replace(text, k, v, …)` — placeholder substitution.
- `MsgUtil.helpEntry(sender, label, description)` — formats a help line.
Placeholders in message templates use `{name}` syntax. All numeric formatting uses `Locale.ROOT`.

### `CommandGuards`
Shared preconditions (each sends its own error message and returns empty/false on failure):
| Guard | Success condition | Failure message key / default |
|---|---|---|
| `requireFaction(player, svc)` | `svc.getFactionByPlayer(uuid)` present | `general.not-in-faction` → `<red>You are not in a faction.` |
| `requireOwner(player, svc)` | `svc.isOwner(uuid)` true | `general.must-be-owner` → `<red>Only the faction owner can do that.` |
| `requireOfficerOrAbove(player, svc)` | `svc.isOfficerOrAbove(uuid)` true | `general.must-be-officer` → `<red>Only officers or above can do that.` |

`isOwner` = player's rank priority ≥ 100. `isOfficerOrAbove` = rank priority ≥ 50 (see §5).

---

## 1. Config Keys Referenced (exact paths & defaults)

All from `config.yml` (`cfg`) unless noted `roles.yml` (`rolesCfg`).

| Getter | Config path | Default | Used by |
|---|---|---|---|
| `getMaxAllies()` | `factions.max-allies` | `5` | relation limit |
| `getMaxTruces()` | `factions.max-truces` | `5` | relation limit |
| `getPowerPerPlayerMax()` / `getMaxPower()` | `factions.power.per-player-max` | `10.0` | power buy cap |
| `getPowerMax()` | `factions.power.constraints.max-power` | = `getMaxPower()` | power clamp / reset target |
| `getPowerMin()` | `factions.power.constraints.min-power` | `0.0` | power clamp |
| `getPowerMaxChangePerEvent()` | `factions.power.constraints.max-change-per-event` | `0.0` (0 = unlimited) | per-event clamp |
| `isPowerBuyEnabled()` | `factions.power.buy.enabled` | `false` | `/f power buy` |
| `getPowerBuyCostPerPoint()` | `factions.power.buy.cost-per-point` | `100.0` | `/f power buy` |
| `getPowerBuyMaxPerPurchase()` | `factions.power.buy.max-per-purchase` | `5.0` | `/f power buy` |
| `isPowerFreezeAllowAdminBypass()` | `factions.power.freeze.allow-admin-bypass` | `true` | admin power cmds `bypassFreeze` arg |
| `isPowerFreezeBlocksAutomatic()` | `factions.power.freeze.blocks-automatic` | `true` | freeze affects DEATH/KILL/BUY |
| `isPowerFreezeBlocksRegen()` | `factions.power.freeze.blocks-regen` | `true` | freeze affects regen |
| `isPowerNotifyActor()` | `factions.power.notifications.actor` | `true` | freeze-blocked notice |
| `getLandMaxPerCommand()` | `factions.land.max-per-command` | `200` | admin claim/unclaim/safezone/warzone bulk cap |
| `getAuditPageSize()` | `factions.audit.page-size` | `10` | `/fa audit` page size |
| `isWarShieldEnabled()` | `factions.war.shield.enabled` | `false` | `/fa shield` |
| `getWarShieldMaxDurationHours()` | `factions.war.shield.max-duration-hours` | `8` | `/fa shield` duration cap |
| `getFlagDefault(flag)` | `factions.flags.{id}.default` | flag hard default (§4) | flag effective value |
| `isFlagPlayerEditable(flag)` | `factions.flags.{id}.player-editable` | `true` | `/f flag set` lock |
| `isCustomRolesEnabled()` | `roles.custom.enabled` (rolesCfg) | `true` | role create/edit/delete |
| `isRoleFactionOverridesEnabled()` | `roles.overrides.enabled` (rolesCfg) | `false` | ALL role mutations |
| `getMinCustomRolePriority()` | `roles.custom.min-priority` (rolesCfg) | `11` | custom role priority range |
| `getMaxCustomRolePriority()` | `roles.custom.max-priority` (rolesCfg) | `99` | custom role priority range |
| `getMaxCustomRolesPerFaction()` | `roles.custom.max-per-faction` (rolesCfg) | `0` (0 = unlimited) | role count limit |
| `isRolePrefixesEnabled()` | `roles.prefix.enabled` (rolesCfg) | `true` | prefix create/set |
| `getMaxRolePrefixLength()` | `roles.prefix.max-length` (rolesCfg) | `32` (0 = unlimited) | prefix normalization |

`roles.yml` also has `roles.custom.max-per-faction: 8` and prefixes `roles.defaults.{owner|officer|member}.prefix`
in the shipped file, but code defaults for those getters are as above (shipped file overrides them).

---

## 2. Admin Commands (`/fa …`)

### 2.1 `/fa bypass` — `CmdAdminBypass`
- **Permission:** `factions.admin`. **Requires player:** yes. No args.
- **Behavior:** loads/creates the sender's `PlayerModel` (`players().findOrCreate(uuid)`), toggles the boolean `overriding` field (`setOverriding(!isOverriding())`), saves.
- **Messages (raw, not keyed):** `<gold>[Admin] Bypass enabled.` / `<gold>[Admin] Bypass disabled.`
- **Errors:** on `StorageException` → `<red>Failed to toggle bypass.`
- **Semantics:** `overriding` is the protection-bypass flag consumed elsewhere (build in others' territory, etc.). Persisted per-player, survives relog.

### 2.2 `/fa claim <faction> [one|square|circle|fill] [radius]` — `CmdAdminClaim`
- **Permission:** `factions.cmd.claim.other`. **Requires player:** yes.
- **Args:** `arg0` = faction name (required); `arg1` = mode (default `one`); `arg2` = radius.
- **Flow:**
  1. `factions().findByName(arg0)`; on exception → `<red>Failed to load faction.`; empty → `<red>Faction not found.`
  2. mode = `arg1` lowercased, blank → `"one"`. radius = `parseRadius(arg2)`.
  3. `max = Math.max(1, config.getLandMaxPerCommand())`. center = player's current chunk.
  4. Collect target chunks (see **Chunk collection** below) for mode; `fill` → `collectSquare(center, 1, max)`; `one`/default → just `[center]`.
  5. For each target chunk: if `board().findByChunk(world,x,z)` is **empty** (unclaimed), `board().claimChunk(world,x,z, factionId)` and increment `claimed`. Exceptions per-chunk are swallowed (keep processing).
  6. Message (raw): `<green>Admin-claimed <white>{claimed}<green> chunk(s) for <white>{factionName}<green>.`
- **Note:** only claims chunks that are currently unclaimed (does not overwrite others' claims).

### 2.3 `/fa unclaim <faction> [all|one|square|circle|fill] [radius]` — `CmdAdminUnclaim`
- **Permission:** `factions.cmd.claim.other`. **Requires player:** yes.
- **Args:** `arg0` faction (required); `arg1` mode (default `one`); `arg2` radius.
- **Flow:**
  1. Same faction lookup as claim (`<red>Failed to load faction.` / `<red>Faction not found.`).
  2. If mode == `all`: `board().findByFactionId(factionId)` → unclaim every entry; message `<yellow>Admin-unclaimed all <white>{count}<yellow> chunks for <white>{name}<yellow>.` On exception → `<red>Failed to unclaim all faction land.`
  3. Else: collect chunks by mode (`fill` → `collectSquare(center,1,max)`; `one`/default → `[center]`), radius = `parseRadius(arg2)`, max = `getLandMaxPerCommand()`.
  4. For each chunk: only unclaim if `board().findByChunk(...)` present **and** its `factionId` equals the target faction's id (won't unclaim other factions' land). Increment `removed`.
  5. Message: `<yellow>Admin-unclaimed <white>{removed}<yellow> chunk(s) for <white>{name}<yellow>.`

### 2.4 `/fa safezone [one|square|circle|remove] [radius]` — `CmdAdminSafezone`
- **Permission:** `factions.cmd.safezone`. **Requires player:** yes. No required args.
- **Flow:** mode = `arg0` lowercased (blank → `one`).
  - `remove`: look up current chunk; if present **and** its factionId == `FactionModel.SAFEZONE_ID` → unclaim, message `<green>Removed safe zone from this chunk.`; else `<red>This chunk is not a safe zone.`; on exception `<red>Failed to remove safe zone chunk.`
  - `square`/`circle`/`one`: collect chunks (radius = `parseRadius(arg1)`, max = `getLandMaxPerCommand()`); for each, `board().claimChunk(world,x,z, SAFEZONE_ID)` (overwrites any existing claim — no unclaimed-check), increment `assigned`. Message `<green>Assigned <white>{assigned}<green> chunk(s) as safe zone.`

### 2.5 `/fa warzone [one|square|circle|remove] [radius]` — `CmdAdminWarzone`
- Identical structure to safezone but uses `FactionModel.WARZONE_ID`.
- **Permission:** `factions.cmd.warzone`. **Requires player:** yes.
- Messages: `<green>Removed war zone from this chunk.` / `<red>This chunk is not a war zone.` / `<red>Failed to remove war zone chunk.` / `<green>Assigned <white>{assigned}<green> chunk(s) as war zone.`

**Chunk collection helpers** (identical across claim/unclaim/safezone/warzone):
- `parseRadius(v)` = `Math.max(1, Integer.parseInt(v))`, defaulting to `1` on parse failure.
- `collectSquare(center, radius, max)`: iterate `dx` in `[-radius, radius]`, `dz` in `[-radius, radius]`, `getChunkAt(cx+dx, cz+dz)`; dedupe by `"x:z"` key; stop when `out.size() >= max`.
- `collectCircle(center, radius, max)`: same but skip chunks where `dx*dx + dz*dz > radius*radius`.
- `square`/`circle` use the parsed radius; `fill` uses fixed radius `1` (a 3×3 block); `one`/default = just the center chunk.

### 2.6 `/fa disband <faction>` — `CmdAdminDisband`
- **Permission:** `factions.cmd.disband.other`. **Requires player:** no (console-capable).
- **Flow:**
  1. `factionService.getFactionByName(arg0)`; empty → `<red>Faction not found.`
  2. **Predefined protection:** if `PredefinedConfigManager` instance non-null AND `isEnabled()` AND `isBlockDisband()` AND `isPredefinedName(faction.name)` → send key `predefined.disband-blocked` → `<red>Predefined factions cannot be disbanded.`; return.
  3. `factionService.disbandFaction(factionId)`: true → `<yellow>Disbanded faction <white>{name}<yellow>.`; false → `<red>Failed to disband faction.`
- **Cross-feature:** disband internally clears incoming relations (see §6 `clearIncomingRelations`).

### 2.7 `/fa reload` — `CmdAdminReload`
- **Permission:** `factions.admin`. **Requires player:** no.
- **Flow (order matters):**
  1. `plugin.reloadConfig()`.
  2. Ensure `roles.yml` exists in data folder (else `saveResource("roles.yml", false)`).
  3. Build new `FactionsConfig(plugin.getConfig(), YamlConfiguration.loadConfiguration(rolesFile))`.
  4. If plugin is `ReferenceFactions` with non-null bootstrap: `infraRegistry.setConfig(newConfig)` then `bootstrap.reloadServices()`. If services reload fails, log warning `"Failed to restart services during /fa reload; a plugin restart may be required."`.
  5. Resolve default locale: `plugin.getConfig().getString("factions.language.default", "en")` (fallback to `config.getDefaultLanguage()` else `"en"`).
  6. Reload messages: ensure `messages/` dir exists; for each shipped locale in `{en, es, de, fr, pt-BR, zh, ru, ja}` save resource `messages/messages_{locale}.yml` if absent; load every `messages_*.yml` in that dir into a map keyed by `MessagesConfig.normalizeLocale(rawLocaleFromFilename)`; construct `MessagesConfig(bundles, defaultLocale)`, `setRepositories(repos)`, and `MsgUtil.setMessagesConfig(...)`.
  7. If `PredefinedConfigManager` instance non-null → `predefined.reload()`.
  8. Send key `admin.reload` → `<green>Configuration reloaded.`

### 2.8 `/fa shield <faction> <clear | <start-hour 0-23> <duration-hours>>` — `CmdAdminShield`
- **Permission:** `factions.cmd.shield`. **Requires player:** no.
- **Feature gate:** if `!config.isWarShieldEnabled()` → key `shield.feature-disabled` → `<red>War shields are not enabled on this server.`; return.
- **Flow:**
  1. `arg0` faction name; blank → raw usage `<red>Usage: /fa shield <faction> <clear|<start-hour (0-23)> <duration-hours>>`.
  2. Look up faction by scanning `factions().findAll()` for case-insensitive name match (uses `findAll().stream().filter(equalsIgnoreCase)`, not `findByName`). Exception → `<red>Failed to look up faction.`; empty → `<red>Faction '<white>{name}<red>' not found.`
  3. `action = arg1` lowercased.
  4. **clear:** `setShieldStartHour(null)`, `setShieldDurationHours(0)`, save (on exception `<red>Failed to save faction.`). Message key `shield.cleared` → `<yellow>War shield cleared for <white>{faction}<yellow>.`
  5. Else parse `startHour = Integer.parseInt(action)`; on failure key `shield.invalid-hour` → `<red>Start hour must be 0–23, or use 'clear'.`. Range check `0..23` else key `shield.invalid-hour` → `<red>Start hour must be 0–23.`.
  6. `maxDuration = config.getWarShieldMaxDurationHours()`. Parse `duration = Integer.parseInt(arg2)`; on failure or if `<1 || >maxDuration` → key `shield.invalid-duration` → `<red>Duration must be 1–{max} hours.` (max substituted).
  7. `setShieldStartHour(startHour)`, `setShieldDurationHours(duration)`, save (exception → `<red>Failed to save faction.`). Message key `shield.set` → `<green>War shield set for <yellow>{faction}</yellow>: <white>{start}:00 UTC</white> for <white>{duration}h</white>.`
- **Semantics:** start hour is whole UTC hour (0–23); window = `[start:00 UTC, start+duration)`. Stored on `FactionModel.shieldStartHour` (nullable Integer) and `shieldDurationHours` (int).

### 2.9 `/fa flag <faction> <flag> [on|off]` — `CmdAdminFlag`
- **Permission:** `factions.admin`. **Requires player:** no. (Admin override — ignores `player-editable` locks.)
- **Args:** `arg0` faction, `arg1` flag id, `arg2` optional on/off.
- **Flow:**
  1. `getFactionByName(arg0)`; empty → `<red>Faction '<white>{name}</white>' not found.`
  2. `flagId = arg1.toLowerCase(ROOT)`; `FactionFlag.byId(flagId)` empty → key `flag.invalid` → `<red>Unknown flag '<white>{flag}</white>'.`
  3. Determine `newValue`:
     - If fewer than 3 args (`ctx.getArgs().size() < 3`): **toggle** → `!flagService.getFlag(faction, flag)`.
     - Else `raw = arg2.toLowerCase`: `on`/`true`/`yes` → true; `off`/`false`/`no` → false; anything else → `<red>Value must be <white>on</white> or <white>off</white>.`; return.
  4. `flagService.setFlag(faction, flag, newValue)` (persists immediately, **no editable check** — admin override).
  5. Key `flag.admin-override` → `<gray>[Admin] Set '<white>{flag}</white>' to {state} for <white>{faction}</white>.` where `{state}` is literal `<green>ON</green>` / `<red>OFF</red>`.
- **Tab-complete:** argIndex 1 → `FactionFlag.ids()`; argIndex 2 → `["on","off"]`.

### 2.10 `/fa audit <faction> [page] [--action=<action>]` — `CmdAdminAudit`
- **Permission:** `factions.admin`. **Requires player:** no (console-friendly).
- **Flow:**
  1. `factionService.getFactionByName(arg0)`; empty → key `general.faction-not-found` → `<red>Faction <yellow>{name}</yellow> not found.`
  2. Parse remaining args (`args[1..]`) via `parseArguments(remaining, {"action"})`. On parse error send `parsed.errorMessage()`.
  3. `pageSize = Math.max(1, config.getAuditPageSize())`. `page = parsePage(firstPositional)` (≥1, default 1). `offset = (page-1)*pageSize`. `actionFilter = parsed.optionValue("action")`.
  4. Fetch entries:
     - If `actionFilter` non-blank: resolve `FactionAuditAction.fromId(actionFilter)`; unknown → `<red>Unknown action '<white>{filter}<red>'. Valid: <gray>{FactionAuditAction.validIds()}` and abort. Else `auditLogs().findByFactionAndAction(factionId, actionId, pageSize, offset)`.
     - Else `auditLogs().findByFaction(factionId, pageSize, offset)`.
  5. Header (raw): `<gold>== Audit Log: <yellow>{displayName}<gold>{filterNote} (Page {page}) ==` where `filterNote` = `" [{filter}]"` when filtering.
  6. Empty page → `<yellow>No audit entries found on this page.`
  7. Each entry line: `<dark_aqua>{time}  <white>{actor}  <aqua>{action}  <gray>{detail}` where `time = CmdAudit.formatTime(entry.createdAt)`, `actor = CmdAudit.resolveActor(entry.actorUuid)`, action = `entry.getAction()`, detail = `entry.getDetail()`.
  8. `StorageException` → `<red>Could not load audit log.` + logger warning.
- **`FactionAuditAction` valid ids:** `claim, unclaim, relation-change, kick, promote, demote, role-create, role-rename, role-priority-set, role-prefix-set, role-delete, role-assign, bank-deposit, bank-withdraw, bank-transfer, merge-request, merge-accept, motd-set`.

### 2.11 `/fa help` — `CmdAdminHelp`
- **Permission:** `factions.admin`. Header `<gold>== Factions Admin ==`. Iterates `commandRegistry.getAll()` (the **admin** registry passed in), skipping commands whose permission the sender lacks, and prints `MsgUtil.helpEntry(sender, "/fa " + cmd.getName(), cmd.getDescription())`.

---

## 3. Admin Power Commands (`/fa power …`) — `CmdAdminPower` group

Parent `CmdAdminPower`: name `power`, **permission** `factions.cmd.admin.power`, no own `perform`
(pure group). Children below. Shared helpers in `AdminPowerCommandUtil`:
- `resolvePlayer(powerService, input)` → `powerService.findPlayerByNameOrUuid(input)` (accepts name or UUID).
- `displayName(playerId, fallback)` → offline player name from UUID, else `fallback`.
- `joinReason(args, startIndex)` → space-joined args from `startIndex`, trimmed (empty string if none).
- `onlineNames()` → online player names (tab completion for arg 0 of every subcommand).

### PowerService.apply mechanics (shared by all admin mutations)
`Request(playerId, source, baseDelta, actorName, reason, world, zone, bypassFreeze)`; admin commands pass
`world=null, zone=null, bypassFreeze = config.isPowerFreezeAllowAdminBypass()`.
`apply()`:
1. Player not found → `Result(changed=false, reasonCode="PLAYER_NOT_FOUND")`.
2. `before = model.getPower()`.
3. Freeze check: if `!bypassFreeze && model.isPowerFrozen() && sourceAffectedByFreeze(source)` → blocked, `reasonCode="FROZEN"`. **Admin sources (`ADMIN_SET/ADD/REMOVE/RESET`) are NEVER affected by freeze** (`sourceAffectedByFreeze` returns false for them), so admin mutations always proceed regardless of freeze state or the bypass flag.
4. `sourceEnabled` for admin sources always `true`.
5. `sourceAmount` for admin sources = the raw `baseDelta` (no multipliers; multipliers only apply to DEATH/KILL).
6. `applyEventClamp(delta)`: if `getPowerMaxChangePerEvent() > 0`, clamp delta to `±max`; else unchanged. **This clamp DOES apply to admin adds/removes/sets** — a single admin mutation cannot exceed the per-event cap when configured.
7. `after = clamp(before + delta, getPowerMin(), getPowerMax())`. `effectiveDelta = after - before`.
8. If `|effectiveDelta| < 0.00001` → `Result(changed=false, reasonCode="NO_CHANGE")` (no save, no history).
9. Else save player, `powerHistory().record(playerId, effectiveDelta, reasonCode, after)`, notify. `reasonCode` = the trimmed `reason` if non-blank (admin commands pass e.g. `"ADMIN_ADD:{reason}"`), else source name.
`Result` fields: `changed, blockedByFreeze, before, requestedDelta, effectiveDelta, after, reasonCode`.

### 3.1 `/fa power view <player>` — `CmdAdminPowerView`
- **Permission:** `factions.cmd.admin.power.view`. Args: `<player>` required.
- Resolve player; empty → key `general.player-not-found` → `<red>Player <yellow>{name}</yellow> not found.`
- Key `power.admin-view` → `<gray>[Power] <white>{player}</white> power=<yellow>{power}</yellow>, frozen=<yellow>{frozen}</yellow>` with power `%.2f`, frozen = `String.valueOf(pm.isPowerFrozen())`.
- StorageException → `<red>Storage error while reading player power.`

### 3.2 `/fa power set <player> <amount> <reason...>` — `CmdAdminPowerSet`
- **Permission:** `factions.cmd.admin.power.set`. Args required: player, amount, reason (rest).
- `amount = Double.parseDouble(arg1)` (**may be negative** — set uses raw value); parse fail → `<red>Invalid amount: <white>{arg1}`.
- reason = `joinReason(args, 2)`; blank → `<red>A reason is required.`
- Resolve player (else player-not-found key). `delta = targetAmount - pm.getPower()`.
- `apply(Request(id, ADMIN_SET, delta, senderName, "ADMIN_SET:{reason}", null, null, bypass))`.
- Key `power.admin-set-success` → `<green>Set power for <white>{player}</white>: <yellow>{before}</yellow> -> <yellow>{after}</yellow>.` (before/after `%.2f`).
- StorageException → `<red>Storage error while setting power.`
- Tab-complete arg1 → `["0","5","10"]`.

### 3.3 `/fa power add <player> <amount> <reason...>` — `CmdAdminPowerAdd`
- **Permission:** `factions.cmd.admin.power.add`.
- `amount = Math.abs(Double.parseDouble(arg1))` (**always positive**); parse fail → `<red>Invalid amount: <white>{arg1}`.
- reason required (else `<red>A reason is required.`).
- `apply(Request(id, ADMIN_ADD, +amount, sender, "ADMIN_ADD:{reason}", null, null, bypass))`.
- Key `power.admin-add-success` → `<green>Added <yellow>{delta}</yellow> power to <white>{player}</white> (<yellow>{before}</yellow> -> <yellow>{after}</yellow>).` where `{delta}` = `result.effectiveDelta()` `%.2f`.
- Tab-complete arg1 → `["1","2","5"]`.

### 3.4 `/fa power remove <player> <amount> <reason...>` — `CmdAdminPowerRemove`
- **Permission:** `factions.cmd.admin.power.remove`.
- `amount = Math.abs(Double.parseDouble(arg1))`; applies **negated** (`-amount`) with source `ADMIN_REMOVE`, reason `"ADMIN_REMOVE:{reason}"`.
- Key `power.admin-remove-success` → `<green>Removed <yellow>{delta}</yellow> power from <white>{player}</white> (<yellow>{before}</yellow> -> <yellow>{after}</yellow>).` where `{delta}` = `Math.abs(result.effectiveDelta())` `%.2f`.
- Same reason/parse/not-found/storage errors as add. Tab-complete arg1 → `["1","2","5"]`.

### 3.5 `/fa power reset <player> <reason...>` — `CmdAdminPowerReset`
- **Permission:** `factions.cmd.admin.power.reset`. Args: player, reason (rest).
- reason = `joinReason(args, 1)`; blank → `<red>A reason is required.`
- `desired = config.getPowerMax()`; `delta = desired - pm.getPower()`; source `ADMIN_RESET`, reason `"ADMIN_RESET:{reason}"`.
- Key `power.admin-reset-success` → `<green>Reset power for <white>{player}</white>: <yellow>{before}</yellow> -> <yellow>{after}</yellow>.`
- StorageException → `<red>Storage error while resetting power.`

### 3.6 `/fa power freeze <player> <on|off> [reason...]` — `CmdAdminPowerFreeze`
- **Permission:** `factions.cmd.admin.power.freeze`. Required: player, on|off. (Reason accepted but unused.)
- Parse: `on`/`true` → enabled true; `off`/`false` → false; else `<red>Use <white>on</white> or <white>off</white>.`
- Does **not** go through `PowerService.apply`: directly `pm.setPowerFrozen(enabled)` + `players().save(pm)`.
- Key `power.admin-freeze-success` → `<green>Power freeze for <white>{player}</white> is now <yellow>{state}</yellow>.` where state = `"on"`/`"off"`.
- Not-found → player-not-found key. StorageException → `<red>Storage error while updating freeze state.`
- Tab-complete arg1 → `["on","off"]`.

### 3.7 `/fa power history <player> [page]` — `CmdAdminPowerHistory`
- **Permission:** `factions.cmd.admin.power.history`. Required: `<player>`; optional page.
- Blank player → `<red>Usage: <yellow>/fa power history <player> [page]`.
- **Delegates** to the player-tree `CmdPowerHistory` (§7.3) by constructing a new `CommandContext` with args `[arg0, arg1]` (blank arg1 filtered out) and executing it. This means the admin variant reuses the exact rendering and paging of the player command (page size 10, UTC formatting). Note the admin permission gates entry, but the delegated command's own `factions.cmd.power.history.other` check does **not** re-fire for the admin path since the sender still passes it if they have admin perms (see §7.3 branch logic — arg0 is a name → "other" branch; requires that node, but admins typically hold it).

---

## 4. Flag System

### 4.1 `FactionFlag` enum (id, display, default)
| Enum | id | Display | Hard default | Gates |
|---|---|---|---|---|
| `PVP` | `pvp` | PvP | **true** | Allow PvP inside this faction's claimed territory. |
| `FRIENDLY_FIRE` | `friendly-fire` | Friendly Fire | false | Allow faction members to damage each other anywhere. |
| `EXPLOSIONS` | `explosions` | Explosions | false | Allow explosions to destroy terrain in territory. |
| `FIRE_SPREAD` | `fire-spread` | Fire Spread | false | Allow fire to spread in territory. |
| `OPEN` | `open` | Open | false | Allow anyone to join without an invite. |

- `FactionFlag.byId(id)` case-insensitive lookup → `Optional`. `FactionFlag.ids()` → id list for tab-complete.
- Effective default per-server = `config.getFlagDefault(flag)` = `factions.flags.{id}.default`, falling back to the hard default above.

### 4.2 `FlagService` semantics (`FlagServiceImpl`)
- Flags stored **sparsely** in `FactionModel.flagsJson` as compact JSON `{"pvp":true,"open":false}`.
  Values are only `true`/`false` (booleans, unquoted). Parser is a hand-rolled string scan: strips braces, splits on `,`, splits each on first `:`, strips quotes on key, reads `true`/`false` (case-insensitive) as value; malformed entries skipped; `{}`/blank → empty map.
- `getFlag(faction, flag)`: if the id is explicitly present in JSON → that value; else `config.getFlagDefault(flag)`.
- `setFlag(faction, flag, value)`: parse map, put id→value, re-serialize to `flagsJson`, `factions().save(faction)` immediately. Serialization writes `"id":true` entries. On `StorageException` logs SEVERE (does not throw).
- `getAllFlags(faction)`: returns an `EnumMap<FactionFlag,Boolean>` covering **all** flags (explicit value or config default).
- `isFlagEditable(flag)`: = `config.isFlagPlayerEditable(flag)` = `factions.flags.{id}.player-editable` (default true). Governs `/f flag set` only; admins bypass.

### 4.3 `/f flag [list|set]` — `CmdFlag` (parent, player tree)
- **Permission:** `factions.cmd.flag`. **Requires player:** yes. Optional args `[list|set]`.
- No-arg `perform`: requires faction (`general.not-in-faction` guard), then delegates to `CmdFlagList.execute(ctx)`.
- Children: `list`, `set`.

### 4.4 `/f flag list` — `CmdFlagList`
- **Permission:** `factions.cmd.flag`. Requires player. Requires faction (guard).
- Header key `flag.list-header` → `<gold><bold>Faction Flags</bold></gold>` (placeholder `faction` = name available).
- For **every** `FactionFlag.values()` in enum order: `value = getAllFlags(faction).getOrDefault(flag, flag.getDefaultValue())`; `editNote = isFlagEditable(flag) ? "" : " <dark_gray>(locked)</dark_gray>"`.
  - ON → key `flag.entry-on` → `<gray>  {flag}: <green>ON</green>{edit_note}` (`{flag}` = id).
  - OFF → key `flag.entry-off` → `<gray>  {flag}: <red>OFF</red>{edit_note}`.

### 4.5 `/f flag set <flag> [on|off]` — `CmdFlagSet`
- **Permission:** `factions.cmd.flag.set`. Requires player.
- **Guards (in order):** requireFaction, then requireOfficerOrAbove. (Both send their standard error and abort.)
- `flagId = arg0.toLowerCase(ROOT)`; `byId` empty → key `flag.invalid` → `<red>Unknown flag '<white>{flag}</white>'. Valid: pvp, friendly-fire, explosions, fire-spread, open`.
- **Editable gate:** `!isFlagEditable(flag)` → key `flag.not-editable` → `<red>Flag '<white>{flag}</white>' is locked by the server administrator.`; abort.
- Value: if `args.size() < 2` → toggle (`!getFlag`); else parse `on/true/yes` → true, `off/false/no` → false, else `<red>Value must be <white>on</white> or <white>off</white>.`
- `setFlag(...)`. Result: ON → key `flag.set-on` → `<green>Flag '<white>{flag}</white>' is now <green>ON</green>.`; OFF → key `flag.set-off` → `<red>Flag '<white>{flag}</white>' is now <red>OFF</red>.`
- Tab-complete: arg0 → `FactionFlag.ids()`, arg1 → `["on","off"]`.

**Player vs Admin flag editing:** `/f flag set` respects `player-editable` locks and requires officer+;
`/fa flag` (§2.9) ignores locks entirely and requires only `factions.admin`.

---

## 5. Role System

### 5.1 `RankModel` (persistent `ranks` table)
Columns: `id VARCHAR(36)`, `faction_id VARCHAR(36)`, `name VARCHAR(64)`, `prefix VARCHAR(32)` (nullable),
`priority INT DEFAULT 10`. Every faction has 3 built-in ranks:

| Built-in name | Constant | Priority |
|---|---|---|
| `Owner` | `RANK_OWNER` | `PRIORITY_OWNER = 100` |
| `Officer` | `RANK_OFFICER` | `PRIORITY_OFFICER = 50` |
| `Member` | `RANK_MEMBER` | `PRIORITY_MEMBER = 10` |

- `isOwner()` = priority ≥ 100. `isOfficerOrAbove()` = priority ≥ 50. `canManage(other)` = `this.priority > other.priority` (strictly greater).
- New `RankModel(id)` defaults priority to 10.

### 5.2 `RankAuthority` helpers
- `MIN_CUSTOM_PRIORITY = PRIORITY_MEMBER + 1 = 11`; `MAX_CUSTOM_PRIORITY = PRIORITY_OWNER - 1 = 99`.
- `canManage(actor, target)` = `actor.priority > target.priority`.
- `findByName(ranks, name)` = case-insensitive, trimmed match (`normalizeName` = `trim().toLowerCase(ROOT)`).
- `isProtectedBuiltin(name)` = normalized name equals Owner/Officer/Member. Protected built-ins cannot be renamed, re-prioritized, or deleted.

### 5.3 Global feature gates
Every role mutation requires `roles.overrides.enabled = true` (`isRoleFactionOverridesEnabled`).
Additionally:
- create/rename/setpriority/delete require `roles.custom.enabled = true`.
- setprefix / prefix-on-create require `roles.prefix.enabled = true`.
`assignRole` requires **only** overrides enabled (not custom-enabled).

### 5.4 `/f role …` — `CmdRole` group (player tree)
- **Permission:** `factions.cmd.role`. **Requires player:** yes. Children: `list, create, rename, setpriority, setprefix, delete, assign`.

### 5.5 `/f role list` — `CmdRoleList`
- **Permission:** `factions.cmd.role.list`. Guard: requireFaction.
- Header key `custom.role.list-header` → `<gold>== Faction Roles ==`.
- For each `listRoles(actor)` (all ranks in the actor's faction; prefix null → shown as `-`): key `custom.role.list-entry` → `<gray>- <white>{name}<gray> | priority <white>{priority}<gray> | prefix <white>{prefix}`.

### 5.6 `/f role create <name> <priority> [prefix]` — `CmdRoleCreate`
- **Permission:** `factions.cmd.role.create`. Guard: requireOfficerOrAbove.
- Early feature check: if `!isCustomRolesEnabled() || !isRoleFactionOverridesEnabled()` → key `custom.role.create-disabled` → `<red>Role creation is disabled. Enable roles.custom.enabled and roles.overrides.enabled.`
- `priority = Integer.parseInt(arg1)`; fail → key `custom.role.invalid-priority` → `<red>Priority must be a number.`
- prefix = `arg2` if `args.size() >= 3` else null.
- `factionService.createRole(actor, name, priority, prefix)` → `CreateRoleResult`, mapped to messages:

| Result | Cause | Message key → default |
|---|---|---|
| `SUCCESS` | created | `custom.role.create-success` → `<green>Created role <white>{name}<green> with priority <white>{priority}<green>.` |
| `PRIORITY_OUT_OF_RANGE` | priority outside `[min,max]` | `custom.role.priority-out-of-range` → `<red>Priority must be between {min} and {max}.` (min/max from config) |
| `ACTOR_RANK_INSUFFICIENT` | `actorRank.priority <= priority` | `custom.role.actor-rank-insufficient` → `<red>You cannot create a role with a priority equal to or above your own rank.` |
| `NAME_TAKEN` | existing rank same name | `custom.role.name-taken` → `<red>A role named <yellow>{name}<red> already exists.` |
| `ROLE_LIMIT_REACHED` | custom-role count limit | `custom.role.limit-reached` → `<red>Your faction has reached the maximum number of custom roles.` |
| default (`FEATURE_DISABLED`, `NOT_IN_FACTION`, `INVALID_NAME`, `STORAGE_ERROR`) | — | `custom.role.create-failed` → `<red>Could not create that role. …` |

- **`createRole` service logic (order):** feature gate → actor player+rank present (else NOT_IN_FACTION) → name trimmed non-blank (else INVALID_NAME) → name not already used (else NAME_TAKEN) → `isValidCustomRolePriority` (else PRIORITY_OUT_OF_RANGE) → `withinCustomRoleCountLimit` (else ROLE_LIMIT_REACHED) → `actorRank.priority > priority` (else ACTOR_RANK_INSUFFICIENT) → create `RankModel(randomUUID)` with faction id, trimmed name, priority, `normalizeRolePrefix(prefix)`; save; audit `ROLE_CREATE` with name; notify role-created. Storage error → STORAGE_ERROR.
- **`isValidCustomRolePriority(p)`:** with `min=getMinCustomRolePriority()`, `max=getMaxCustomRolePriority()`: if `min > max` (misconfig) fall back to `p ∈ [11,99]`; else `p ∈ [min,max]`.
- **`withinCustomRoleCountLimit(ranks)`:** if `getMaxCustomRolesPerFaction() <= 0` → unlimited (true); else count non-builtin ranks and require `count < max`.
- **`normalizeRolePrefix(prefix)`:** null/blank → null; else trim; if `maxLength>0 && trimmed.length() > maxLength` → **null** (signals invalid to callers). NOTE: on create, an over-length prefix silently becomes null (no error); the role is still created with no prefix.

### 5.7 `/f role rename <old> <newName>` — `CmdRoleRename`
- **Permission:** `factions.cmd.role.edit`. Guard: requireOfficerOrAbove.
- `renameRole(actor, old, new)` → true → key `custom.role.rename-success` → `<green>Renamed role <white>{old}<green> to <white>{new}<green>.`; false → `custom.role.rename-failed` → `<red>Could not rename that role.`
- **Service logic:** requires custom+overrides enabled; actor player/faction/rank present; newName trimmed non-blank; target found by name; target **not** protected built-in; `canManage(actor, target)`; new name not colliding with a different rank; then set name, save, audit `ROLE_RENAME` (`old -> new`), notify. Any failed precondition → false.
- Tab-complete arg0 → role names.

### 5.8 `/f role setpriority <role> <priority>` — `CmdRoleSetPriority`
- **Permission:** `factions.cmd.role.edit`. Guard: requireOfficerOrAbove.
- `priority = Integer.parseInt(arg1)`; fail → key `custom.role.invalid-priority` → `<red>Priority must be a number.`
- `setRolePriority(actor, role, priority)` → true → key `custom.role.priority-success` → `<green>Set role <white>{name}<green> priority to <white>{priority}<green>.`; false → `custom.role.priority-failed` → `<red>Could not update that role priority.`
- **Service logic:** custom+overrides enabled; actor present; `isValidCustomRolePriority(priority)`; target found; not protected built-in; `canManage(actor,target)`; `actorRank.priority > priority` (cannot raise a role to ≥ own rank); set priority, save, audit `ROLE_PRIORITY_SET` (`name -> priority`), notify.

### 5.9 `/f role setprefix <role> <prefix|none>` — `CmdRoleSetPrefix`
- **Permission:** `factions.cmd.role.edit`. Guard: requireOfficerOrAbove.
- Feature check: `!isRolePrefixesEnabled() || !isRoleFactionOverridesEnabled()` → key `custom.role.prefix-disabled` → `<red>Role prefixes are disabled. Enable roles.prefix.enabled and roles.overrides.enabled.`
- `prefix = "none".equalsIgnoreCase(arg1) ? null : arg1`.
- `setRolePrefix(actor, role, prefix)` → true → key `custom.role.prefix-success` → `<green>Updated role <white>{name}<green> prefix.`; false → `custom.role.prefix-failed` → `<red>Could not update that role prefix.`
- **Service logic:** requires prefix+overrides enabled; actor present; target found; `canManage(actor,target)`; `normalizedPrefix = normalizeRolePrefix(prefix)`; **if the raw prefix was non-blank but normalized to null (over max length) → return false** (this is where an over-length prefix is rejected, unlike create). Set prefix (may be null to clear), save, audit `ROLE_PREFIX_SET`, notify. Note: **setprefix is NOT restricted to non-builtin ranks** — only `canManage` gate (so an Owner can set the Officer/Member prefix).
- Tab-complete arg1 → `["none"]`.

### 5.10 `/f role delete <role>` — `CmdRoleDelete`
- **Permission:** `factions.cmd.role.delete`. Guard: requireOfficerOrAbove.
- `deleteRole(actor, role)` → true → key `custom.role.delete-success` → `<yellow>Deleted role <white>{name}<yellow>.`; false → `custom.role.delete-failed` → `<red>Could not delete that role.`
- **Service logic:** custom+overrides enabled; actor present; target found; not protected built-in; `canManage(actor,target)`; **not in use** — `players().findByFactionId(factionId)` none have `rankId == target.id` (if any member holds the role → false); delete, audit `ROLE_DELETE`, notify.

### 5.11 `/f role assign <player> <role>` — `CmdRoleAssign`
- **Permission:** `factions.cmd.role.assign`. Guard: requireOfficerOrAbove.
- Target via `Bukkit.getOfflinePlayer(arg0)` (name). `assignRole(actor, targetUuid, roleName)` → true → key `custom.role.assign-success` → `<green>Assigned role <white>{role}<green> to <white>{player}<green>.`; false → `custom.role.assign-failed` → `<red>Could not assign that role.`
- **Service logic:** requires **overrides enabled only** (custom not required); actor & target players present, actor rank present; target must be in the **same faction** as actor; role found by name; if target currently has a rank, `canManage(actor, targetCurrentRank)` required (can't reassign someone you can't manage); `canManage(actor, role)` required (can't assign a role at/above your own priority); set `targetPm.rankId = role.id`, save, audit `ROLE_ASSIGN` (`targetName -> roleName`).
- Tab-complete: arg0 → online names; arg1 → role names.

**Promotion/demotion** (`/f promote`, `/f demote`, separate commands not in scope but relevant): implemented by
`changeMemberRank(actor, target, promote)` — finds target's rank index in the faction's rank list (ordered as
returned by `findByFactionId`), moves to `idx-1` (promote) / `idx+1` (demote); requires `canManage(actor, target)`
and `canManage(actor, newRank)`; **cannot promote into Owner** (`promote && newRank.isOwner()` → false); audits
`MEMBER_PROMOTE`/`MEMBER_DEMOTE`.

---

## 6. Relation System

### 6.1 `Relation` enum
Ordinal order (used by `isAtLeast`/`isAtMost`): `MEMBER(0) < ALLY(1) < TRUCE(2) < NEUTRAL(3) < ENEMY(4)`.
- `isFriendly()` = MEMBER or ALLY. `isNeutralOrBetter()` = not ENEMY. `isHostile()` = ENEMY.
- `displayName()`: Member/Ally/Truce/Neutral/Enemy. `colorTag()`: MEMBER `<green>`, ALLY `<aqua>`, TRUCE `<yellow>`, NEUTRAL `<gray>`, ENEMY `<red>`.
- `MEMBER` is a player-to-faction relation, **not** a valid faction-faction relation target (rejected by commands).

### 6.2 Storage format
`FactionModel.relationsJson` is a JSON map `{"<targetFactionId>":"ENEMY", …}` where the value is the
`Relation.name()` (quoted). Parse/serialize is a hand-rolled scan identical in structure to the flag parser
(strips braces, splits on `,` then first `:`, strips quotes on key AND value, `Relation.valueOf(value)`;
invalid entries skipped). This map is the faction's **outgoing** relation "wishes".

### 6.3 Relation state machine (`FactionService.setRelation(actorUuid, targetName, relation)`)
Returns `Optional<Relation>` (empty = failure). Logic:
1. Resolve source (actor's faction) and target (`findByName`). Either missing → empty.
2. Same faction → empty.
3. `sourceMap` / `targetMap` = parsed relations. `previous = sourceMap.get(targetId)`.
4. **Limit check** `withinRelationLimit(sourceMap, previous, relation)`:
   - Only ALLY/TRUCE are limited; ENEMY/NEUTRAL always pass.
   - If `previous == relation` (no change) → pass.
   - Else count existing entries equal to `relation` in `sourceMap`; `max = ALLY ? getMaxAllies() : getMaxTruces()` (default 5 each); pass iff `count < Math.max(0, max)`.
   - Fail → empty (command shows `relation.set-failed`).
5. Put `sourceMap[targetId] = relation`, save source, **audit `RELATION_CHANGE`** with detail `"{RELATION} with {targetName}"`.
6. **ENEMY or NEUTRAL → always mirrored**: put `targetMap[sourceId] = relation`, save target. Return the relation (takes effect immediately, unilaterally).
7. **ALLY/TRUCE → require mutual consent**: only if `targetMap.get(sourceId) == relation` (target already wished the same) is the target re-saved (promoting to active). Return the relation regardless (the wish is recorded).
- Storage error → empty.

**Key behavioral asymmetry:** ENEMY and NEUTRAL are bilateral/instant (setting enemy makes both sides enemies).
ALLY and TRUCE are unilateral **wishes** until both factions independently set the same relation toward each other.

### 6.4 `/f relation <faction> <ally|truce|neutral|enemy>` — `CmdRelation` (parent, player tree)
- **Name:** `relation`, **alias** `relationship`. **Permission:** `factions.cmd.relation`. **Requires player:** yes.
- Children: `list`, `wishes`.
- **Flow:**
  1. requireFaction; requireOfficerOrAbove.
  2. `getFactionByName(arg0)` empty → `<red>Faction not found.`
  3. Same faction as source → `<red>You cannot set a relation with your own faction.`
  4. `relation = Relation.valueOf(arg1.toUpperCase(ROOT))`; invalid → `<red>Invalid relation. Use ally, truce, neutral, or enemy.`; if `== MEMBER` → `<red>Invalid relation.`
  5. `setRelation(...)` empty → key `relation.set-failed` → `<red>Failed to set relation.` (this includes hitting ally/truce limits).
  6. `mutual = isMutual(source, target, relation)` (both maps show `relation` toward each other **after** the set).
  7. **ALLY/TRUCE branch:**
     - mutual → actor key `relation.mutual-established` → `<green>Mutual <white>{relation}<green> established with <white>{faction}<green>.`; notify all target members key `relation.mutual-established-target` → `<green><white>{faction}<green> and your faction are now <white>{relation}<green>.`; then broadcast announcement (see 6.5).
     - not mutual (pending) → actor key `relation.pending-wish` → `<yellow>Relation wish set to <white>{relation}<yellow> for <white>{faction}<yellow>. They must set the same relation to confirm.`; notify target members key `relation.pending-received` → `<yellow><white>{faction}<yellow> requested <white>{relation}<yellow> with your faction.`
  8. **NEUTRAL/ENEMY branch (falls through):** actor key `relation.set` → `<green>You set your relation with <yellow>{faction}</yellow> to <yellow>{relation}</yellow>.`; notify target members key `relation.updated-by-other` → `<yellow><white>{faction}<yellow> set relation to <white>{relation}<yellow>.`; if ENEMY → broadcast announcement.
- **Member notification:** `FactionMemberNotifier.notifyMembers(null, repos, logger, targetFactionId, member->true, message)` — messages every online member of the target faction.
- **Tab-complete:** arg0 → all faction names; arg1 → `["ally","truce","neutral","enemy"]`.
- `factionDisplayName(f)` = name if non-blank else id.

### 6.5 Relation announcement broadcast (`sendRelationAnnouncement`)
Fired for **mutual ALLY/TRUCE established** and **ENEMY set**. Builds a message:
- ENEMY: key `ezcountdown.relation-enemy` → `<red>⚔ {source} declared war on {target}!`
- else: key `ezcountdown.relation-{ally|truce}` → `<green>🤝 {source} and {target} are now {DisplayName}!`
Delivery:
- If EzCountdown available (`ezCountdownNotifier != null && isEnabled()` AND `notificationsConfig != null && isEzCountdownEnabled()`) → `ezCountdownNotifier.sendAnnouncement(message, durationSeconds, displayTypes)`.
- Else broadcast to **all online players** via `MsgUtil.send`.
- **DiscordSRV:** if `discordSrvNotifier != null && isEnabled() && factionsConfig != null`, and the per-relation Discord toggle is on (`isDiscordSrvRelationEnemyEnabled` / `...AllyEnabled` / `...TruceEnabled`), send the corresponding template message (`getDiscordSrvRelation{Enemy|Ally|Truce}Message`) with `{source}`/`{target}` replaced.

### 6.6 `/f relation list [ally|truce|neutral|enemy]` — `CmdRelationList`
- **Permission:** `factions.cmd.relation`. Requires player. Guard: requireFaction.
- `filter = parseRelation(arg0)` (case-insensitive `Relation.valueOf`, invalid/blank → null = show all).
- Header (raw): `<gold>== Faction Relations ==`.
- For each entry in the source faction's relations map: skip if `filter != null && value != filter`.
  - Resolve target faction by id → name (fallback to raw id). `status = relationStatus(...)`.
  - Line (raw): `<yellow>- <white>{targetName}<gray>: {colorTag}{displayName}<gray> ({status})`.
- No entries shown → `<gray>No relation entries.`
- **`relationStatus`:** target unknown (id no longer resolves) → `"unknown"`; for non-ALLY/TRUCE relations → `"active"`; for ALLY/TRUCE → parse target's map, `"mutual"` if target reciprocates the same relation, else `"pending"`.
- Tab-complete arg0 → `["ally","truce","neutral","enemy"]`.

### 6.7 `/f relation wishes` — `CmdRelationWishes`
- **Permission:** `factions.cmd.relation`. Requires player. Guard: requireFaction.
- If relations JSON is null/blank/`{}` → `<gray>No relation wishes set.`
- Else → `<gold>Relation wishes are currently set. Use <white>/f relation list</white><gold>.` (a pointer message; the detailed view lives in `list`).

---

## 7. Player Power Commands (`/f …`)

### 7.1 `/f power [buy]` — `CmdPower` (parent)
- **Permission:** `factions.cmd.power`. **Requires player:** yes. Optional args `[buy]`. Child: `buy`.
- No-arg `perform`: key `custom.power.usage-buy` → `<gray>Usage: <yellow>/f power buy <amount>` (this command is purely a usage/group stub — it does not display current power itself).

### 7.2 `/f power buy <amount>` — `CmdPowerBuy`
- **Permission:** `factions.cmd.power.buy`. Requires player. Required arg `<amount>`.
- **Flow:**
  1. `!config.isPowerBuyEnabled()` → key `power.buy-disabled` → `<red>Buying power is not enabled on this server.`
  2. `!vaultEconomy.isEnabled()` → key `power.buy-no-vault` → `<red>An economy plugin is required to buy power.`
  3. `maxPerPurchase = config.getPowerBuyMaxPerPurchase()`. Parse amount with `MoneyParser.parse(arg0)`; if empty, `<= 0`, or `> maxPerPurchase` → key `power.buy-invalid-amount` → `<red>Enter a positive amount (max <yellow>{max}<red> per purchase).` (max `%.1f`).
  4. Load `players().find(playerId)`; empty → `<red>Could not find your player data.`
  5. `maxPower = config.getMaxPower()`. If `pm.getPower() >= maxPower` → key `power.buy-already-max` → `<red>You already have maximum power.`
  6. `actualAmount = min(amount, maxPower - pm.getPower())` (clamps to headroom). `cost = actualAmount * config.getPowerBuyCostPerPoint()`; formatted `%.2f`.
  7. `vaultEconomy.getBalance(player) < cost` → key `power.buy-insufficient-funds` → `<red>You need <yellow>{cost}<red> to buy that much power.`
  8. `vaultEconomy.withdraw(player, cost)` false → `<red>Transaction failed — please try again.`
  9. `powerService.apply(Request(playerId, BUY, actualAmount, playerName, "BUY", null, null, false))` (bypassFreeze **false** — BUY is subject to freeze via `blocks-automatic`).
  10. Key `power.buy-success` → `<green>You purchased <yellow>{amount}<green> power for <yellow>{cost}<green>.` (amount `%.1f`, cost `%.2f`).
- **Cost formula (exact):** `cost = min(requested, maxPower - currentPower) * costPerPoint`. Money withdrawn = cost. Power granted = `actualAmount`.
- StorageException → `<red>A storage error occurred. Please try again.`
- Tab-complete arg0 → `["1","2","5"]`.

### 7.3 `/f powerhistory [<player>] [<page>]` — `CmdPowerHistory`
- **Name:** `powerhistory`, **alias** `phist`. **Permission:** `factions.cmd.power.history`. **Requires player:** no.
- Page size fixed `PAGE_SIZE = 10`. Timestamp format `yyyy-MM-dd HH:mm` in **UTC**.
- **Arg dispatch:**
  - If `arg0` empty OR numeric (`isPageNumber`): **own history**. Console with no player → key `power.history-console-usage` → `<red>Specify a player: <yellow>/f powerhistory <player> [page]`. Else self is target; `page = parsePage(arg0)` if arg0 present.
  - Else (`arg0` is a name): **other player**. Requires perm `factions.cmd.power.history.other` else key `general.no-permission` → `<red>You do not have permission to do that.`. `op = Bukkit.getOfflinePlayer(arg0)`; if `!op.hasPlayedBefore()` → key `general.player-not-found` → `<red>Player <yellow>{name}</yellow> not found.`. `page = parsePage(arg1)` if present.
- `offset = (page-1)*10`. `powerHistory().findRecentByPlayerUuid(uuid, 10, offset)`.
- Empty → key `power.history-empty` → `<yellow>No power history found for <white>{name}<yellow>.`
- Header key `power.history-header` → `<gold>== Power History: <yellow>{name}<gold> (Page {page}) ==`.
- Per row: `time` (UTC), `reason = row.getReason()`, `power_after = %.1f`.
  - `delta >= 0` → key `power.history-entry-gain` → `<dark_aqua>{time}  <white>{reason}  <green>{delta}  <gray>→  <white>{power_after}` where delta = `"+" + %.1f`.
  - `delta < 0` → key `power.history-entry-loss` → `<dark_aqua>{time}  <white>{reason}  <red>{delta}  <gray>→  <white>{power_after}` where delta = `%.1f` (already negative).
- StorageException → key `power.history-storage-error` → `<red>A storage error occurred. Please try again.`
- **Records captured:** only DEATH, KILL, BUY, and admin power events (regen ticks are not recorded). Reason strings are the `reasonCode` produced by `PowerService.apply` (e.g. `ADMIN_ADD:{reason}`, `BUY`, source names).
- Tab-complete arg0 → online names.

---

## 8. Permission Node Inventory

| Node | Commands |
|---|---|
| `factions.admin` | `/fa bypass`, `/fa reload`, `/fa flag`, `/fa audit`, `/fa help` |
| `factions.cmd.claim.other` | `/fa claim`, `/fa unclaim` |
| `factions.cmd.disband.other` | `/fa disband` |
| `factions.cmd.safezone` | `/fa safezone` |
| `factions.cmd.warzone` | `/fa warzone` |
| `factions.cmd.shield` | `/fa shield` |
| `factions.cmd.admin.power` | `/fa power` (group) |
| `factions.cmd.admin.power.view` | `/fa power view` |
| `factions.cmd.admin.power.set` | `/fa power set` |
| `factions.cmd.admin.power.add` | `/fa power add` |
| `factions.cmd.admin.power.remove` | `/fa power remove` |
| `factions.cmd.admin.power.reset` | `/fa power reset` |
| `factions.cmd.admin.power.freeze` | `/fa power freeze` |
| `factions.cmd.admin.power.history` | `/fa power history` |
| `factions.cmd.flag` | `/f flag`, `/f flag list` |
| `factions.cmd.flag.set` | `/f flag set` |
| `factions.cmd.role` | `/f role` (group) |
| `factions.cmd.role.list` | `/f role list` |
| `factions.cmd.role.create` | `/f role create` |
| `factions.cmd.role.edit` | `/f role rename`, `setpriority`, `setprefix` |
| `factions.cmd.role.delete` | `/f role delete` |
| `factions.cmd.role.assign` | `/f role assign` |
| `factions.cmd.relation` | `/f relation`, `relation list`, `relation wishes` |
| `factions.cmd.power` | `/f power` (group) |
| `factions.cmd.power.buy` | `/f power buy` |
| `factions.cmd.power.history` | `/f powerhistory` |
| `factions.cmd.power.history.other` | `/f powerhistory <other player>` (extra runtime check) |

---

## 9. Message Key Inventory (key → default text)

Admin/generic:
- `general.faction-not-found` → `<red>Faction <yellow>{name}</yellow> not found.`
- `general.player-not-found` → `<red>Player <yellow>{name}</yellow> not found.`
- `general.no-permission` → `<red>You do not have permission to do that.`
- `general.not-in-faction` → `<red>You are not in a faction.`
- `general.must-be-owner` → `<red>Only the faction owner can do that.`
- `general.must-be-officer` → `<red>Only officers or above can do that.`
- `admin.reload` → `<green>Configuration reloaded.`
- `predefined.disband-blocked` → `<red>Predefined factions cannot be disbanded.`
- `shield.feature-disabled`, `shield.cleared`, `shield.invalid-hour`, `shield.invalid-duration`, `shield.set` (defaults in §2.8).

Power:
- `power.admin-view`, `power.admin-set-success`, `power.admin-add-success`, `power.admin-remove-success`, `power.admin-reset-success`, `power.admin-freeze-success` (§3).
- `power.buy-disabled`, `power.buy-no-vault`, `power.buy-invalid-amount`, `power.buy-already-max`, `power.buy-insufficient-funds`, `power.buy-success` (§7.2).
- `power.history-console-usage`, `power.history-empty`, `power.history-header`, `power.history-entry-gain`, `power.history-entry-loss`, `power.history-storage-error` (§7.3).
- `custom.power.usage-buy` (§7.1).

Flag: `flag.invalid`, `flag.admin-override`, `flag.list-header`, `flag.entry-on`, `flag.entry-off`, `flag.not-editable`, `flag.set-on`, `flag.set-off` (§4).

Role (all prefixed `custom.role.`): `list-header`, `list-entry`, `create-disabled`, `invalid-priority`, `create-success`, `priority-out-of-range`, `actor-rank-insufficient`, `name-taken`, `limit-reached`, `create-failed`, `rename-success`, `rename-failed`, `priority-success`, `priority-failed`, `prefix-disabled`, `prefix-success`, `prefix-failed`, `delete-success`, `delete-failed`, `assign-success`, `assign-failed` (§5).

Relation: `relation.set-failed`, `relation.mutual-established`, `relation.mutual-established-target`, `relation.pending-wish`, `relation.pending-received`, `relation.set`, `relation.updated-by-other`; announcement keys `ezcountdown.relation-enemy`, `ezcountdown.relation-ally`, `ezcountdown.relation-truce` (§6).

> Several commands use **raw MiniMessage strings** (not keyed) — notably all four claim/unclaim/safezone/warzone
> messages, `/fa bypass`, `/fa disband` success/fail, `/fa flag` faction-not-found, and `/f relation` early errors.
> These are hardcoded English and not localizable.

---

## 10. Cross-Feature Interactions & Edge Cases

- **Freeze bypass:** `factions.power.freeze.allow-admin-bypass` sets `bypassFreeze` on admin mutations, but admin sources are exempt from freeze anyway (`sourceAffectedByFreeze` = false), so the flag has no effect on admin power add/set/remove/reset — those always apply. It only conceptually matters for the request contract.
- **Per-event clamp** (`max-change-per-event`) DOES clip admin single-mutation deltas when configured `> 0`, so `/fa power add 1000` may be capped.
- **Power clamp:** all power ends up in `[min-power, max-power]`. `/fa power set 99999` clamps to max; the success message shows the actual clamped `after`.
- **NO_CHANGE:** setting power to its current value, or add/remove that clamps to zero effective change, saves nothing and records no history, but the command still prints a success message with equal before/after.
- **Admin flag vs player flag:** admin `/fa flag` ignores both the `player-editable` lock and rank requirement; player `/f flag set` enforces both. Both mutate the same `flagsJson` and take effect immediately.
- **Relation limits** apply only to the initiator's outgoing ally/truce count; ENEMY/NEUTRAL have no cap and are mirrored to the target instantly. Downgrading (e.g. ally→neutral) bypasses limit checks.
- **Disband** clears the disbanded faction's id from every other faction's relations map (prevents stale references shown as `unknown` in `/f relation list`).
- **Role list ordering** for promote/demote uses the raw `findByFactionId` order (index-based neighbor stepping), so demotion/promotion depends on stored rank ordering, not strictly on priority.
- **Safezone/Warzone claim** overwrites existing claims (no unclaimed check), unlike admin `/fa claim` which only fills unclaimed chunks.
- **`/fa shield`** uses a full-table `findAll()` scan for name matching (case-insensitive) rather than `findByName`, so it matches regardless of stored casing.
- **`/fa power history`** reuses the player `CmdPowerHistory`; because arg0 is always a player name in the admin path, it routes into the "other player" branch and requires `hasPlayedBefore()` on the target.
- **Over-length role prefix:** silently dropped to null on `create`; explicitly rejected (command failure) on `setprefix`.
- **Custom role priority misconfig:** if `min-priority > max-priority`, validation falls back to the hard range `[11, 99]`.
