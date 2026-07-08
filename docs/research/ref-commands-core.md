# the reference implementation — Command Framework & Core Subcommands (SPEC)

Clean-room behavioral spec for the `/f` command framework and every top-level subcommand.
Source scope: `command/*.java` (framework) + `command/sub/Cmd*.java` (top-level leaf/group commands only).
Sub-package commands (`sub/bank`, `sub/warp`, `sub/role`, `sub/admin`, `sub/chest`, `sub/invite`, `sub/relation`, `sub/merge`, `sub/flag`, `sub/predefined`, `sub/power`) are OUT OF SCOPE here except where a top-level parent wires them as children (documented as "children" only).

---

## 1. Command Registration

### 1.1 plugin.yml (`src/main/resources/plugin.yml`)
Two Bukkit commands are declared:

| Command | Aliases | Permission | Usage |
|---|---|---|---|
| `f` | `faction`, `factions` | none (open) | `/f <subcommand> [args]` |
| `fa` | `factionadmin` | `factions.admin` | `/fa <subcommand> [args]` |

- `api-version: '1.21'`, `folia-supported: true`, `main: com.reference.factions.ReferenceFactions`.
- `softdepend`: TeamsAPI, Vault, WorldGuard, WorldEdit, PlaceholderAPI, Essentials, EssentialsX, dynmap, DiscordSRV, EzEconomy, EzCountdown, LWC, LWCX.
- `loadbefore`: EzShops, EzAuction, EzRTP, EzClean.
- ALL individual subcommand permission nodes are declared here (see §9). Only the two root commands are Bukkit commands; every subcommand is dispatched programmatically.

### 1.2 Programmatic registration (`bootstrap/CommandsBootstrapComponent.java`)
Runs in `start(BootstrapContext)`. Builds TWO independent `CommandRegistry` instances:

**Player registry (`/f`)** — registered in this exact order (order drives nothing functional except that `CommandRegistry.getAll()`/help section lookups are order-preserving `LinkedHashMap`):
`create, disband, info, invite, join, leave, kick, claim, unclaim, home, sethome, unsethome, fly, relation, merge, warp, chest, bank, power, powerhistory(CmdPowerHistory), list, language, map, notify, predefined, gui, leader, promote, demote, role, rename, desc, motd, top, flag, audit, help`.

Constructor dependencies passed at registration (relevant ones):
- `CmdCreate(FactionService)`, `CmdDisband(FactionService)`, `CmdInfo(FactionService)`.
- `CmdInvite(FactionService, InviteService, Repositories)`.
- `CmdJoin(FactionService, InviteService, FlagService)`.
- `CmdClaim(EngineChunkChange, TerritoryGuard, AutoTerritoryModeCache)`.
- `CmdUnclaim(EngineChunkChange, FactionService, AutoTerritoryModeCache)`.
- `CmdHome(FactionService, EssentialsInterop)`, `CmdSetHome(FactionService, TerritoryGuard)`, `CmdUnsetHome(FactionService)`.
- `CmdFly(FactionService)`.
- `CmdRelation(FactionService, EzCountdownNotifier, NotificationsConfig, DiscordSrvNotifier, FactionsConfig)` (full 5-arg form).
- `CmdBank(FactionService, EngineEconomy)`, `CmdList(FactionService)`, `CmdMap()`, `CmdNotify()`.
- `CmdLanguage(FactionsGuiManager, GuiConfig)`, `CmdGui(FactionsGuiManager, GuiConfig)`.
- `CmdHelp(commandRegistry)` — receives the registry itself.

**Admin registry (`/fa`)** — order: `info(CmdInfo), bypass, claim, unclaim, disband(CmdAdminDisband), reload, safezone, warzone, shield, flag(CmdAdminFlag), audit(CmdAdminAudit), power(CmdAdminPower), help(CmdAdminHelp)`. Note: `CmdInfo` is reused verbatim on both `/f` and `/fa`.

After building each registry, executor + tab-completer are attached to every alias:
```
for (alias : {"f","faction","factions"})  getCommand(alias).setExecutor(FactionCommandExecutor); .setTabCompleter(FactionTabCompleter)
for (alias : {"fa","factionadmin"})        getCommand(alias).setExecutor(AdminCommandExecutor);   .setTabCompleter(AdminTabCompleter)
```
Each `getCommand(alias)` is null-checked; missing aliases are skipped silently.

### 1.3 CommandRegistry (`registry/CommandRegistry.java` extends `Registry<String,FactionCommand>`)
- `register(FactionCommand cmd)`: puts `cmd` under `cmd.getName().toLowerCase()` AND under every alias (lowercased). Same instance stored under multiple keys.
- Backing store is `LinkedHashMap` (insertion order). `register(K,V)` overwrites duplicate keys.
- `get(String key)` → `Optional<FactionCommand>`.
- `getAll()` → `store().values().stream().distinct().toList()` (dedupes multi-alias instances, preserves order).
- `completionNames(partial, sender)`: returns all keys (names AND aliases) that `startsWith(partial)` AND whose command's permission is null or `sender.hasPermission(perm)`. Used at arg-index 0 completion. Note: aliases appear as separate completion entries (e.g. `info`, `i`, `show` all listed).

---

## 2. Root Executors

### 2.1 FactionCommandExecutor (`/f`)
`onCommand(sender, command, label, args)`:
1. **`args.length == 0`**: if `sender instanceof Player` AND `guiManager != null` AND `guiManager.openDefault(player)` returns true → return (opened default GUI). Otherwise call `sendHelp(sender)`.
2. Look up `commandRegistry.get(args[0].toLowerCase())`.
3. **Not found**: if `teamsBridge != null` and `teamsBridge.dispatch(sender, args)` returns true → return. Else send `MsgUtil.unknownCommand(sender, args[0])` (rich `[Help]` link) and return.
4. **Found**: build `CommandContext` with `subArgs = args[1..]` (or `List.of()` if only one token) and call `cmd.execute(ctx)`.
5. Always returns `true` (never shows Bukkit's own usage).

`sendHelp`: `commandRegistry.get("help").ifPresent(help -> help.execute(new CommandContext(..., List.of(), ...)))`.

### 2.2 AdminCommandExecutor (`/fa`)
Identical structure MINUS: no GUI-on-empty, no TeamsAPI bridge. Empty args → `sendHelp`. Unknown → `MsgUtil.unknownCommand`. The `/fa` Bukkit permission `factions.admin` is enforced by Bukkit before `onCommand` runs.

### 2.3 TeamsCommandBridge (`command/TeamsCommandBridge*.java`)
Interface loaded unconditionally (no TeamsAPI imports). `TeamsCommandBridgeImpl` imports `com.skyblockexp.teamsapi.api.TeamsAPI` and MUST be instantiated only via reflection (`Class.forName("...TeamsCommandBridgeImpl")`) after TeamsAPI is confirmed present — see `CommandsBootstrapComponent.loadTeamsCommandBridge` which returns null when `!context.isTeamsApiEnabled()` or on `ReflectiveOperationException` (logs warning). `null` bridge = TeamsAPI absent; every call site null-checks first.
- `dispatch(sender, args)`: iterate `TeamsAPI.getSubcommands()`, match `getName().equalsIgnoreCase(args[0])`. If matched: perm check (`getPermission()` null or `hasPermission`) → send `general.no-permission` and return true if denied; else `sub.execute(sender, args)`, and if that returns false send `general.invalid-args` with `{usage}` = `sub.getUsage()`. Returns true if any subcommand name matched, false otherwise (so `/f` can fall through to unknown-command).
- `completeSubcommandNames(sender, partial)`: names of permitted subcommands `startsWith(partial)`.
- `completeArgs(sender, args)`: delegate `sub.tabComplete(sender, args)` for the matched (and permitted) subcommand, else empty.

---

## 3. Tab Completers

### 3.1 FactionTabCompleter (`/f`)
- `args.length == 0` → `List.of()`.
- `args.length == 1` → `commandRegistry.completionNames(args[0].toLowerCase(), sender)` PLUS (if bridge != null) `teamsBridge.completeSubcommandNames(sender, partial)`. (No prefix filtering re-applied to the merge beyond what each source does.)
- `args.length >= 2` → look up top-level cmd by `args[0]`. If found: build `CommandContext` with args `[1..]` and return `cmd.tabComplete(ctx)`. If not found and bridge != null → `teamsBridge.completeArgs(sender, args)`. Else `List.of()`.

### 3.2 AdminTabCompleter (`/fa`)
Same, without the TeamsAPI merge/fallback. `args.length==1` → `completionNames`. `>=2` → route to matched command's `tabComplete`, else `List.of()`.

---

## 4. Base Class Contract — `FactionCommand` (abstract)

### 4.1 State & config setters (call only from subclass constructor)
| Field | Setter | Default | Meaning |
|---|---|---|---|
| `name` (final) | ctor arg, `.toLowerCase()` | — | primary name |
| `permission` | `setPermission(String)` | `null` (open) | required node |
| `description` | `setDescription(String)` | `""` | help line |
| `requiredArgs` | `setRequiredArgs(String...)` | `List.of()` | placeholder names, drives count check |
| `optionalArgs` | `setOptionalArgs(String...)` | `List.of()` | placeholder names, display only |
| `requiresPlayer` | `setRequiresPlayer(boolean)` | `false` | reject console |
| `aliases` | `setAliases(String...)` | empty | extra registry keys |
| `commandPath` | (internal) | `"/f"` | prefix for usage; children get `parentPath + " " + parentName` |
| `children` | `addChild(FactionCommand)` | empty `LinkedHashMap` | keyed by child name lowercased |

`addChild(child)` sets `child.commandPath = this.commandPath + " " + this.name` then `children.put(child.name.toLowerCase(), child)`. NOTE: `commandPath` stays `/f` even for admin commands — usage strings on `/fa` commands still read `/f ...` unless the subclass overrides `getUsage()`.

### 4.2 `execute(CommandContext)` — the dispatch pipeline (final)
Exact order:
1. **Permission**: if `permission != null && !sender.hasPermission(permission)` → send `general.no-permission` (`<red>You do not have permission to run this command.`) and return.
2. **Player-only**: if `requiresPlayer && !ctx.isPlayer()` → send `general.player-only` (`<red>This command can only be used by a player.`) and return.
3. **Child routing**: if `children` non-empty AND `args` non-empty AND `children.get(args[0].toLowerCase())` exists → call `child.execute(ctx.shift())` and return. (Child gets its own full pipeline incl. its own permission check.)
4. **Arg count**: if `args.size() < requiredArgs.size()` → send `general.invalid-args` (`<red>Usage: {usage}`) with `usage=getUsage()` and return.
5. **`perform(ctx)`**.

Group commands with a default action override `perform` (e.g. `CmdMerge`, `CmdFlag`); if not overridden, `perform` default sends the usage string (`general.invalid-args`).

### 4.3 `tabComplete(CommandContext)` (final)
`partial` = last arg lowercased (or `""`).
- **Has children**:
  - `args.size() <= 1`: merge into a `LinkedHashSet` — (a) child names where `child.name.startsWith(partial)` AND child perm null-or-held, then (b) results of `complete(ctx,0)` filtered by `startsWith(partial)`. This lets a group command offer child names + dynamic values at position 0 simultaneously.
  - else: route to `children.get(args[0])` → `child.tabComplete(ctx.shift())`. If no child matched, fall to `complete(ctx, args.size()-1)` filtered by `startsWith(partial)`.
- **Leaf**: `complete(ctx, max(0, args.size()-1))` filtered by `startsWith(partial)`.

### 4.4 `getUsage()`
`commandPath + " " + name`, then:
- if children: append ` <child1|child2|...>` (unique child names, order preserved).
- else: append each required placeholder then each optional placeholder, space-separated.
Subclasses may override (none of the in-scope top-level commands do).

### 4.5 Override points
- `perform(CommandContext ctx)`: default sends `general.invalid-args` usage.
- `complete(CommandContext ctx, int argIndex)`: default `List.of()`. `argIndex` is zero-based relative to this command.

### 4.6 Helpers available to subclasses
- `requirePlayer(ctx)`: cast sender to Player or send `general.player-only` and return null. (Redundant when `requiresPlayer=true`.)
- `parseArguments(rawArgs, valuedOptionNames)` → `ParsedCommandArgs`. Long-option parser:
  - Tokens NOT starting with `--` → positional (order preserved).
  - `--name=value` or `--name value` forms. `name` lowercased. Unknown option (`name` not in `valuedOptionNames`) → error `<red>Unknown option: <white>--{name}`. Missing value (next token missing or itself starts with `--`, or value blank after trim) → error `<red>Missing value for option: <white>--{name}`. Values are `.trim()`ed.
  - `ParsedCommandArgs`: `hasError()`, `errorMessage()`, `positionalArgs()`, `optionValue(name)` (lowercased lookup). `success()` copies lists immutably; `error()` yields empty lists.

### 4.7 CommandContext (`command/CommandContext.java`, immutable)
Fields: `plugin, sender, args(copyOf), repos, config, logger`.
- `isPlayer()` = `sender instanceof Player`.
- `requirePlayer()` = cast or send `general.player-only` + null.
- `arg(i)` = `args[i]` or `""` if OOB (never throws; commands rely on this heavily).
- `shift()` = new context with `args.subList(1, size)` (empty if already empty).
- Accessors: `getPlugin/getSender/getArgs/getRepos/getConfig/getLogger`.

### 4.8 CommandGuards (`command/CommandGuards.java`, static helpers)
| Method | Behavior on failure |
|---|---|
| `requireFaction(player, svc)` → `Optional<FactionModel>` | if `svc.getFactionByPlayer(uuid)` empty → send `general.not-in-faction` (`<red>You are not in a faction.`), return empty |
| `requireOwner(player, svc)` → boolean | if `!svc.isOwner(uuid)` → send `general.must-be-owner` (`<red>Only the faction owner can do that.`), return false |
| `requireOfficerOrAbove(player, svc)` → boolean | if `!svc.isOfficerOrAbove(uuid)` → send `general.must-be-officer` (`<red>Only officers or above can do that.`), return false |

---

## 5. Messaging — `MsgUtil` (`util/MsgUtil.java`)

- `send(sender, miniMsg)`: if `ADVENTURE` (Paper native Adventure present + `CommandSender.sendMessage(Component)` overload exists) → deserialize MiniMessage via server's native MiniMessage and send as Component (hover/click preserved). Else `LegacyOps`: MiniMessage → for players, serialize to Gson→BungeeCord components (`player.spigot().sendMessage`) so hover/click survive on Spigot; for non-players, legacy §-string. Fallback on any throw = `stripTags` (plain).
- `sendKey(sender, key, fallback, kv...)`: `template = message(sender,key,fallback)` then `send(replace(template, kv...))`. `message(sender,...)` resolves per-sender locale via `MessagesConfig.getForSender`; if `messagesConfig==null` returns fallback. `message(key,fallback)` (no sender) uses locale-agnostic `get`.
- `replace(template, kv...)`: replaces `{key}`→value for each pair.
- Rich builders (all return MiniMessage strings; single-quote args are `mmEscape`d = `\`→`\\` then `'`→`\'`):
  - `helpEntry(sender, usage, desc)`: hover=`help.entry-hover` (`<gray>{description}<newline><dark_gray>Click to suggest command`), line=`help.entry-line` (`<yellow>{usage}<gray> - {description}`), wrapped in `<hover:show_text><click:suggest_command:'{usage}'>`.
  - `infoHeader(name)`: `<gold>== <hover...><click:suggest_command:'/f info {name}'><yellow><bold>{name}</click></hover><gold> ==`.
  - `warpEntry(name)`, `inviteNotification(sender, faction)` (`[Accept]` runs `/f join {faction}`, `[Deny]` hover-only), `inviteListEntry(sender, faction, inviter)`, `factionInfoHover(...)`.
  - `unknownCommand(sender, input)`: `general.unknown-subcommand-detailed` (`<red>Unknown command '<yellow>{input}<red>'. `) + `[Help]` click→`/f help`, hover=`general.help-hover`.
- `stripTags`, `toLegacy` (§-codes for inventory titles).

**Message-key convention**: two families exist — plain namespaced keys (`general.*`, `home.*`, `motd.*`, `map.*`, `relation.*`, `invite.*`, `help.*`, `language.*`, `member.*`, `info.*`, `predefined.*`, `fly.*`, `ezcountdown.*`) and `custom.*` keys (`custom.faction.*`, `custom.member.*`, `custom.home.*`, `custom.gui.*`, `custom.notify.*`, `custom.invite.*`, `custom.merge.*`, `custom.list.*`, `custom.top.*`, `custom.fly.*`). MANY messages are hard-coded MiniMessage literals passed straight to `MsgUtil.send` with NO key (not localizable) — noted per-command below.

---

## 6. Cross-cutting Facts

- **No cooldowns, no confirmation-token cache, no paging cache** exist in the command framework itself. "Confirm" flows are literal string-arg checks (`leader`, `unsethome`, `unclaim all` require the literal token `confirm`). The ONLY per-instance cache is `CmdInfo.lastFactionBySender` (`ConcurrentHashMap<String,String>`), remembering the last-viewed faction id per sender for `/f info page`.
- **Console senders**: commands with `setRequiresPlayer(true)` reject console at pipeline step 2. Commands WITHOUT it that can run from console: `list`, `top`, `help`, `info` (info handles console via usage message when no name given). `map`, `notify`, `create`, etc. are player-only.
- **Offline/unknown target resolution**: `Bukkit.getPlayerExact` (kick, exact online only), `Bukkit.getPlayer` (invite, prefix online), `Bukkit.getOfflinePlayer(name)` (promote/demote/leader — always returns a non-null OfflinePlayer, so NO "not found" guard; a typo creates a bogus UUID and the service call simply fails/no-ops), `Bukkit.getOfflinePlayer(UUID)` (audit/info name resolution).
- **StorageException** (Jaloquent) is caught locally in each command that touches repos and downgraded to a friendly message; details logged via `ctx.getLogger()`.

---

## 7. Per-Command Specification (top-level `command/sub/Cmd*.java`)

Legend: **Perm** = permission node; **Player?** = `requiresPlayer`; args in `<>` required, `[]` optional. Msg keys listed as `key` (fallback text abbreviated). "hardcoded" = literal MiniMessage sent with no message key.

### 7.1 CmdCreate — `/f create <name>`
- Perm `factions.cmd.create`; Player yes; required `<name>`.
- Flow: reject if `factionService.isInFaction(uuid)` → hardcoded `<red>You are already in a faction.`
- Predefined guard: if `PredefinedConfigManager.getInstance()` non-null AND `.isEnabled()` AND `!.isPredefinedName(name)` → `predefined.create-not-allowed` (`<red>You can only create predefined factions on this server.`).
- Name length: must be `>=3 && <=32` else hardcoded `<red>Faction name must be between 3 and 32 characters.`
- `factionService.createFaction(name, uuid)`: if present → hardcoded `<green>Faction <white>{name}<green> created!`; else hardcoded `<red>A faction with that name already exists.` Catches `IllegalStateException` → hardcoded `<red>An internal error occurred. Please try again.`

### 7.2 CmdDisband — `/f disband`
- Perm `factions.cmd.disband`; Player yes.
- `requireFaction` → `requireOwner`.
- Predefined block: if PCM non-null AND enabled AND `.isBlockDisband()` AND `.isPredefinedName(faction.name)` → `predefined.disband-blocked` (`<red>Predefined factions cannot be disbanded.`).
- `disbandFaction(id)`: true → `custom.faction.disbanded` (`<yellow>Your faction has been disbanded.`); false → `custom.faction.disband-failed`.

### 7.3 CmdLeave — `/f leave`
- Perm `factions.cmd.leave`; Player yes.
- `getFactionByPlayer` empty → `general.not-in-faction`.
- If `faction.isOwner(uuid)` → `custom.member.owner-cannot-leave` (`<red>You are the owner. Transfer ownership or disband...`).
- `removeMember(id, uuid)`: true → `member.left` (`<yellow>You left faction <yellow>{faction}</yellow>.`); false → `custom.member.leave-failed`.

### 7.4 CmdKick — `/f kick <player>`
- Perm `factions.cmd.kick`; Player yes; required `<player>`.
- `requireFaction` → `requireOfficerOrAbove`.
- Target: `Bukkit.getPlayerExact(arg0)`; null → `general.player-not-found` (`{name}`).
- Self-kick → `member.cannot-kick-self`. Target is owner (`isOwner`) → `member.cannot-kick-leader`.
- `kickMember(actorUuid, targetUuid)`: true → `custom.member.kick-actor` (`Kicked {player}`) to actor + `member.kicked` (`{faction}`,`{kicker}`) to target (faction name re-resolved via actor's faction, fallback `"faction"`; kicker name fallback `"Unknown"`). false → `member.not-member` (`{player} is not a member...`).
- **Complete** arg0: online player names.

### 7.5 CmdPromote — `/f promote <player>`
- Perm `factions.cmd.promote`; Player yes; required `<player>`.
- `requireOfficerOrAbove` (NO requireFaction — officer check implies faction).
- Target = `Bukkit.getOfflinePlayer(arg0)` (no null/not-found guard).
- `promoteMember(actor, target)`: true → `custom.member.promoted` (`Promoted {name}`); false → `custom.member.promote-failed`.

### 7.6 CmdDemote — `/f demote <player>`
- Perm `factions.cmd.demote`; Player yes; required `<player>`.
- `requireOfficerOrAbove`. Target `getOfflinePlayer(arg0)`.
- `demoteMember`: true → hardcoded `<yellow>Demoted <white>{arg0}<yellow>.`; false → hardcoded `<red>Could not demote that player.` (NOT keyed.)

### 7.7 CmdLeader — `/f leader <player> [confirm]`
- Perm `factions.cmd.leader`; Player yes; required `<player>`, optional `[confirm]`.
- `requireOwner`. Target `getOfflinePlayer(arg0)`.
- Self-transfer guard: if `target.uuid == owner.uuid` AND `arg1 != "confirm"` (case-insensitive) → `custom.member.leader-confirm-self` (`Use /f leader {name} confirm...`).
- `transferOwnership(owner, target)`: true → `custom.member.leader-transferred` (`{name}`); false → `custom.member.leader-transfer-failed`.

### 7.8 CmdRename — `/f rename <name>`
- Perm `factions.cmd.rename`; Player yes; required `<name>`.
- `requireOwner`. Length `3..32` else hardcoded `<red>Faction name must be between 3 and 32 characters.`
- `renameFaction(uuid, newName)`: true → hardcoded `<green>Faction renamed to <white>{newName}<green>.`; false → hardcoded `<red>Could not rename faction (name may already be taken).`

### 7.9 CmdDesc — `/f desc <text...>`
- Perm `factions.cmd.desc`; Player yes; required `<text...>`.
- `requireOwner`. `description = String.join(" ", args).trim()`. Length `>250` → `custom.faction.desc-too-long` (max 250).
- `setFactionDescription(uuid, desc)`: true → `custom.faction.desc-updated`; false → `custom.faction.desc-update-failed`.

### 7.10 CmdMotd — `/f motd [clear | <text...>]`
- Perm `factions.cmd.motd`; Player yes; optional `[clear | <text...>]`.
- `requireFaction`.
- **No args (view)**: `motd = faction.getMotd()`; if null/empty → `motd.none`; else `motd.header` (`<gold>== <yellow>Faction MOTD</yellow> ==`) then `motd.display` (`{motd}`).
- **Set** (requires `requireOfficerOrAbove`):
  - `arg0 == "clear"` (ci) → `setFactionMotd(uuid, "")`: true `motd.cleared`; false `motd.set-failed`.
  - else `motd = join(args).trim()`, length `>250` → `motd.too-long`; `setFactionMotd(uuid, motd)`: true `motd.set`; false `motd.set-failed`.

### 7.11 CmdRole — `/f role <list|create|rename|setpriority|setprefix|delete|assign>` (GROUP)
- Perm `factions.cmd.role`; Player yes. No own `perform` → default prints usage `general.invalid-args`.
- Children (out of scope detail): `CmdRoleList, CmdRoleCreate, CmdRoleRename, CmdRoleSetPriority, CmdRoleSetPrefix, CmdRoleDelete, CmdRoleAssign` — added in that order (drives usage `<list|create|...>`).

### 7.12 CmdHome — `/f home`
- Perm `factions.cmd.home`; Player yes.
- `requireFaction`. `essentialsInterop.isJailed(player)` → `home.jailed`.
- `home = getFactionHome(uuid)`; null or `home.getWorld()==null` → `home.no-home`.
- Teleport: `essentialsInterop.teleport(player, home, onSuccess=home.teleported, onFail=home.teleport-failed)`. If interop handled it (returns true) → return. Else fallback `player.teleport(home)` + `home.teleported`.

### 7.13 CmdSetHome — `/f sethome`
- Perm `factions.cmd.sethome`; Player yes.
- `requireFaction` → `requireOfficerOrAbove`.
- `territoryGuard.canModifyTerritory(player, player.getLocation())` false → `custom.home.set-protected`.
- `setFactionHome(uuid, loc)`: true `custom.home.set`; false `custom.home.set-failed`.

### 7.14 CmdUnsetHome — `/f unsethome [confirm]`
- Perm `factions.cmd.sethome` (SHARES sethome node); Player yes; optional `[confirm]`.
- `requireOfficerOrAbove` (NO requireFaction).
- If `arg0 != "confirm"` (ci) → hardcoded `<red>Use /f unsethome confirm to remove faction home.`
- `unsetFactionHome(uuid)`: true hardcoded `<yellow>Faction home removed.`; false hardcoded `<red>Failed to remove faction home.`

### 7.15 CmdFly — `/f fly`
- Perm `factions.cmd.fly`; Player yes.
- `requireFaction`. Config `isFlyEnabled()` false → `custom.fly.disabled-global`.
- If `isFlyRequireOwnTerritory()` AND NOT in own territory → `custom.fly.own-territory-required`. Own-territory check: `repos.board().findByChunk(world, chunkX, chunkZ)` present AND `factionId == entry.factionId`; on `StorageException` logs warning and treats as NOT own (false).
- Toggle: `newState = !isFactionFlyEnabled(uuid)`; `setFactionFlyEnabled(uuid, newState)`; `player.setAllowFlight(newState)`; if disabling and `player.isFlying()` → `setFlying(false)`. Msg `fly.enabled`/`fly.disabled`.
- **Config keys**: `factions.fly.enabled` (def true), `factions.fly.require-own-territory` (def true).

### 7.16 CmdGui — `/f gui [menu]` (alias `menu`)
- Perm `factions.cmd.gui`; Player yes; optional `[menu]`.
- Uses `ctx.requirePlayer()` (redundant). `menu = arg0.isBlank() ? guiConfig.getDefaultMenu() : arg0`.
- `guiManager.openMenu(player, menu)` false → `custom.gui.menu-not-found` (`{menu}`).
- **Complete** arg0: keys of config section `gui.menus` from `guiConfig.raw()`.

### 7.17 CmdMerge — `/f merge <send|accept>` (GROUP with default help)
- Perm `factions.cmd.merge`; Player yes. Children `CmdMergeSend`, `CmdMergeAccept`.
- `perform` (no/unmatched arg): prints 3 help lines `custom.merge.help-title`, `custom.merge.help-send`, `custom.merge.help-accept`.

### 7.18 CmdFlag — `/f flag [list|set]` (GROUP, default = list)
- Perm `factions.cmd.flag`; Player yes; optional `[list|set]`.
- Children `CmdFlagList` (also held as field `cmdFlagList`), `CmdFlagSet`.
- `perform` (no arg): `requireFaction`, then `cmdFlagList.execute(ctx)` (delegates to list child directly).

### 7.19 CmdPredefined — `/f predefined <create|claim|sethome|reload|list>` (alias `prefined`) (GROUP)
- Perm `factions.cmd.predefined`; NOT player-only at parent level. No own `perform` → usage.
- Children `CmdPredefinedCreate, CmdPredefinedClaim, CmdPredefinedSetHome, CmdPredefinedReload, CmdPredefinedList`.

### 7.20 CmdAudit — `/f audit [page] [--action=<action>]`
- Perm `factions.cmd.audit`; Player yes; optional `[page]`, `[--action=<action>]`.
- `requireFaction`, then explicit `isOfficerOrAbove` check (inline, not the guard helper but sends same `general.must-be-officer`).
- `parseArguments(args, {"action"})`; on error → send `parsed.errorMessage()`.
- `pageSize = max(1, config.getAuditPageSize())` (`factions.audit.page-size`, def 10). `page = parsePage(positional[0])` (bad/blank → 1, else `max(1, parseInt)`). `offset = (page-1)*pageSize`. `actionFilter = optionValue("action")`.
- Fetch: if actionFilter non-blank → validate via `FactionAuditAction.fromId`; unknown → hardcoded `<red>Unknown action '...'. Valid: {validIds}` and abort. Then `repos.auditLogs().findByFactionAndAction(id, actionId, pageSize, offset)`; else `findByFaction(id, pageSize, offset)`.
- Header hardcoded: `<gold>== Faction Audit Log: <yellow>{name}<gold>{filterNote} (Page {page}) ==`. Empty page → hardcoded `<yellow>No audit entries found on this page.` Each entry via `renderEntry`: `<dark_aqua>{MM-dd HH:mm}  <white>{actor}  <aqua>{action}  <gray>{detail}`. Actor resolution: null/blank → `"System"`; else `Bukkit.getOfflinePlayer(UUID).getName()` or first 8 chars of UUID; bad UUID → raw string. Time formatted `MM-dd HH:mm` in default TZ.
- `StorageException` → hardcoded `<red>Could not load audit log.` + logger warning.

### 7.21 CmdClaim — `/f claim [one|auto|square|circle|fill|nearby|at]`
- Perm `factions.cmd.claim`; Player yes; optional mode.
- **Single** (no args or `one`): `territoryGuard.canModifyTerritory(player, loc)` false → hardcoded `<red>You cannot claim land in this protected region.`; else `engineChunkChange.claim(player, chunk)` true → hardcoded `<green>Chunk claimed!` (false = engine already messaged, silent).
- **`auto [on|off]`**: `enabled = arg1.blank ? (autoModeCache.getMode(uuid) != CLAIM) : "on".equals(arg1)`. `newMode = enabled ? CLAIM : OFF`. `autoModeCache.setMode(uuid, newMode)` false → hardcoded `<red>Failed to persist auto-claim preference.`; else hardcoded `<green>Auto-claim enabled (basic mode).` / `<yellow>Auto-claim disabled.`
- **`at`** → `claimAt`: parse `arg1`=chunkX, `arg2`=chunkZ (`Integer.MIN_VALUE` sentinel on parse fail) → hardcoded usage `<red>Usage: /f claim at <chunkX> <chunkZ>`. `world.getChunkAt(x,z)`, guard check on block (8, playerY, 8), then claim → hardcoded `<green>Chunk claimed at <white>{x}<gray>,<white>{z}<green>.`
- **Area** (`square|circle|fill|nearby`): `max = max(1, config.getLandMaxPerCommand())` (`factions.land.max-per-command`, def 200). Center = player's chunk.
  - `square`/`nearby`: `collectSquare(center, parseRadius(arg1), max)` (radius def 1 on bad parse, min 1).
  - `circle`: `collectCircle` (Euclidean `dx²+dz² <= r²`).
  - `fill`: `collectSquare(center, 1, max)`.
  - For each chunk: guard check on block (8, playerY, 8); skip if denied; else claim, count successes. Result hardcoded `<green>Claimed <white>{success}<green> chunk(s).`
- **Collectors**: iterate `dx,dz in [-r,r]`, dedupe by `"x:z"`, stop when `out.size() >= max`.
- **Complete**: arg0 = `[one,auto,square,circle,fill,nearby,at]`; arg1 if `auto` = `[on,off]`; arg1 if `at` = current chunk X; arg2 if `at` = current chunk Z.

### 7.22 CmdUnclaim — `/f unclaim [one|auto|square|circle|fill|all]`
- Perm `factions.cmd.unclaim`; Player yes.
- **Single** (no args/`one`): `engineChunkChange.unclaim(player, chunk)` true → hardcoded `<yellow>Chunk unclaimed.`
- **`auto [on|off]`**: mirror of claim but `UNCLAIM` mode; msgs `<green>Auto-unclaim enabled (basic mode).`/`<yellow>Auto-unclaim disabled.`/`<red>Failed to persist auto-unclaim preference.`
- **`all`**: `requireOwner`; if `arg1 != "confirm"` (ci) → hardcoded `<red>Use /f unclaim all confirm to unclaim all faction land.`; `getFactionByPlayer` empty → hardcoded `<red>You are not in a faction.`; load `repos.board().findByFactionId(id)` (Exception → hardcoded `<red>Failed to load claims.`); iterate each `BoardEntry` → `world.getChunkAt(chunkX, chunkZ)` → unclaim, count; result `<yellow>Unclaimed <white>{removed}<yellow> chunk(s).`
- **Area** (`square|circle|fill`): same collectors as claim; NO territoryGuard check (unclaim doesn't check protection); result `<yellow>Unclaimed <white>{success}<yellow> chunk(s).`
- **Complete**: arg0 = `[one,auto,square,circle,fill,all]`; arg1 if `auto` = `[on,off]`; arg1 if `all` = `[confirm]`.

### 7.23 CmdInvite — `/f invite [player|list|revoke|accept|decline|declineall]` (alias `inv`) (GROUP + default = send invite)
- Perm `factions.cmd.invite`; Player yes; optional. Children: `CmdInviteList, CmdInviteRevoke, CmdInviteAccept, CmdInviteDecline, CmdInviteDeclineAll`.
- `perform` (arg0 is a player name, not a child):
  - No args → usage `general.invalid-args`.
  - `requireFaction` → `requireOfficerOrAbove`.
  - `target = Bukkit.getPlayer(arg0)` (prefix, online); null → `general.player-not-found`.
  - `isInFaction(target)` → `custom.invite.target-already-in-faction`.
  - `inviteService.sendInvite(factionId, inviterUuid, targetUuid)`: true → `custom.invite.sent` (`{player}`) + rich notify: load `repos.players().findOrCreate(targetUuid)`; if `targetModel.hasInviteNotifications()` → send `MsgUtil.inviteNotification`. On `StorageException` → send notification anyway via `target.sendMessage(...)`. false → `custom.invite.already-pending`.
- **Complete** arg0: online player names (merged with child names at position 0 by base class).

### 7.24 CmdJoin — `/f join [factionName]`
- Perm `factions.cmd.join`; Player yes; optional `[factionName]`.
- `isInFaction(uuid)` → hardcoded `<red>You are already in a faction.`
- **No args (list invites)**: `inviteService.listActiveInvitesForPlayer(uuid)`; empty → hardcoded `<yellow>You have no pending invites.`; else hardcoded header `<gold>You have <white>{n}<gold> pending faction invite(s):` and one `MsgUtil.inviteListEntry` per invite (faction name via `getFactionById`, fallback `"Unknown"`; inviter via `getOfflinePlayer(UUID)` name or raw id).
- **With name**: `getFactionByName(arg0)` empty → hardcoded `<red>Faction not found.`; `isFactionFull(id)` → `member.faction-full` (`{faction}`); if `flagService != null && getFlag(faction, OPEN)` → `joinFaction(id, uuid)` true `<green>You joined <white>{name}<green>!` / false `<red>Could not join that faction.`; else `inviteService.acceptInvite(id, uuid)` present → `<green>You joined...!` / empty → `<red>You do not have a pending invite from that faction.`
- **Complete** arg0: all faction names.

### 7.25 CmdList — `/f list [page] [sort]`
- Perm `factions.cmd.list`; NOT player-only (console OK); optional `[page]`, `[sort]`.
- `parseArgs(arg0, arg1)`: if arg0 blank → page 1, sort from arg1. If arg0 is a sort keyword (`members|power|land|bank|name`) → page 1, sort=arg0. Else page = `parsePage(arg0)` (bad→1, min 1), sort from arg1. `parseSort`: blank/unknown → `"name"`.
- Sort comparators: `bank` desc, `members` desc (repo player count), `land` desc (`board.countByFactionId`), `power` desc (`faction.powerBoost + Σ member.power`), default `name` asc (lowercased).
- Empty → `custom.list.none`. `pageSize = max(1, config.getListPageSize())` (`factions.list.page-size`, def 8). `start=(page-1)*pageSize`, `end=min(size, start+pageSize)`.
- Output: `custom.list.separator`, `custom.list.header` (`{page}`,`{sort}`), rows `custom.list.row` (`#{rank}` = i+1, `{name}`,`{members}`,`{land}`,`{bank}`=`%.2f`), trailing `custom.list.separator`. `memberCount/landCount/totalPower` swallow exceptions → 0.
- **Complete** arg0 & arg1: `[members,power,land,bank,name]`.

### 7.26 CmdTop — `/f top [page] [sort]`
- Perm `factions.cmd.top`; NOT player-only; optional `[page]`,`[sort]`.
- Like `list` but sort keywords = `power|bank|land` (default `power`). Comparators: `bank` desc, `land` desc, else `power` desc (`powerBoost + Σ member.power`).
- Empty → `custom.list.none` (reuses list key). `pageSize = max(1, config.getTopPageSize())` (`factions.top.page-size`, def 8).
- Output: `custom.list.separator`, `custom.top.header`, rows `custom.top.row` (`{power}`=`%.1f`, `{land}`, `{bank}`=`%.2f`), trailing separator.
- **Complete** arg0 & arg1: `[power,bank,land]`.

### 7.27 CmdNotify — `/f notify [status|invites|territory|tax|motd|all] [on|off]` (alias `notifications`)
- Perm `factions.cmd.notify`; Player yes.
- `model = repos.players().findOrCreate(uuid)`. `type = arg0.blank ? "status" : arg0.lower`.
- `status` → `sendStatus` (header `custom.notify.status-header` + 4 lines `custom.notify.status-line` for invites/territory/tax/motd with `{type}`,`{value_color}`=green|red,`{value}`=on|off,`{desc}`).
- else `value = arg1.lower`; not on/off → `custom.notify.usage`. `enabled = "on"==value`. Switch: `invites`→`setInviteNotifications`, `territory`→`setTerritoryTitles`, `tax`→`setBankTaxNotifications`, `motd`→`setMotdNotifications`, `all`→all four; unknown → `custom.notify.unknown-type`. Then `players().save(model)`, `custom.notify.updated` (`{type}`,`{value}`), then `sendStatus`.
- `StorageException` → `custom.notify.update-failed`.
- **Complete** arg0 = `[status,invites,territory,tax,motd,all]`; arg1 = `[on,off]`.

### 7.28 CmdMap — `/f map [on|off|once] [--size=<size>]`
- Perm `factions.cmd.map`; Player yes.
- `parseArguments(args, {"size"})`; error → send message. `positional.size() > 1` → `map.error.usage`. `mode = positional[0].lower` or `""`.
- `--size`: parse int; NaN → `map.error.size-nan`; `<1` → `map.error.size-small`.
- `model = players().findOrCreate(uuid)`. `on` → `setTerritoryTitles(true)`+save+`map.enabled`. `off` → false+save+`map.disabled`. `StorageException` on save → `map.error.save-failed`.
- Otherwise (`once` or empty): `radius = size ?? max(1, config.getMapOnceRadius())` (`factions.map.once-radius`, def 3) → `renderOnce`.
- `renderOnce`: separator/header (`{world}`,`{x}`,`{z}`)/hint keys; grid rows for `z in [cz-r, cz+r]`, cols `x in [cx-r, cx+r]`, each cell = `cellTag`; legend key; separator.
- `cellTag`: current chunk = white ■ (click→`/f map once`); wilderness (board empty) = dark_gray ■ (click→`/f claim at x z`); `SAFEZONE_ID` = aqua ■; `WARZONE_ID` = red ■; own faction = green ■ (click suggest `/f info {name}`); other faction = yellow ■. `StorageException` per cell → dark_gray ■ with "Failed to load claim data" hover. Player faction id via `players().find(uuid).factionId`.
- **Complete**: at argIndex<=1, offer `[on,off,once]` (unless a mode token already present) + `[--size=, --size=1..5]` (unless a size token present).

### 7.29 CmdInfo — `/f info [name]` (aliases `i`, `show`; also on `/fa`)
- **No permission node** (open). NOT player-only. Optional `[name]`. Child: `CmdInfoPage` (`page`).
- Resolve faction: arg present → `getFactionByName(arg0)`; else if player → `getFactionByPlayer(uuid)`; else (console, no arg) → hardcoded `<red>Usage: {getUsage()}`. Empty → hardcoded `<red>Faction not found.`
- Build `FactionInfoSnapshot` (members, relations map, land count, bank, powers, detailLines). Remember faction id in `lastFactionBySender` (`player:{uuid}` or `sender:{name}`).
- Output block (all hardcoded except `info.details-hint`): `MsgUtil.infoHeader(name)`, dashes, Leader (owner name via OfflinePlayer or raw id or "Unknown"), Members line (hover lists names, `{count}/{maxMembers}`), Power `%.1f/%.1f` (totalPower / `memberCount * config.getMaxPower()`), Land, Bank (`%.2f`), Home (`world (x,y,z)` `%.1f` or "Not set"), relation lines (Allies/Truces/Neutrals/Enemies gated by `info.relations.show-*` config), Description (if non-blank), `info.details-hint` (`{pages}`), dashes.
- `StorageException` → hardcoded `<red>An internal error occurred.` + `logger.severe`.
- **Config keys**: `factions.max-members` (def 50), `factions.power.per-player-max`/max-power (def 10.0), `factions.land.per-power` (def 1.0), `factions.land.max` (def 500), `factions.info.relations.show-allies` (def true), `.show-truces/.show-neutrals/.show-enemies` (def false).
- **Detail lines** (5 per page, `DETAIL_LINES_PER_PAGE=5`, `DAY_MILLIS=86_400_000`): Online Members (hover), Rank Distribution (per-rank counts via `ranks().findByFactionId`, default rank "Member"), Activity (inactive `>7d` = `lastActivity < now - 7*DAY`, last-seen), Relations Summary (`A/T/N/E` counts), Claim Capacity (`land/maxLand`, RAIDABLE if `faction.isRaidable() || land>maxLand` else STABLE; `maxLand = landPerPower<=0 ? config.maxLand : min(maxLand, (int)(totalPower/landPerPower))`), Power Breakdown, Top Power Members (top 3 by power), Newest Member (max joinedAt), Most Recent Activity (max lastActivity), Navigation line. Age format: `<1h`→`{m}m`, `<48h`→`{h}h`, else `{d}d`; `<=0`→"just now".

#### 7.29.1 CmdInfoPage (child `page`) — `/f info page <number> [name]`
- No perm; required `<number>`, optional `[name]`.
- Parse number: NaN or `<1` → `info.invalid-page` (`{max}`="1"). Resolve faction: `arg1` (name) or `lastFactionBySender` cache; none → `info.page-no-context`. Build snapshot; `requestedPage > totalPages` → `info.invalid-page` (`{max}`=totalPages). `info.page-title` (`{name}`,`{page}`,`{pages}`), then detail lines `[start,end)`, then `info.page-next`/`info.page-prev` links. `StorageException` → hardcoded internal error + severe log.
- **Complete** arg0 = `[1,2,3,4]`; arg1 = faction names.

### 7.30 CmdRelation — `/f relation <faction> <ally|truce|neutral|enemy>` (alias `relationship`) (GROUP + default set)
- Perm `factions.cmd.relation`; Player yes; required `<faction>`,`<relation>`. Children `CmdRelationList`, `CmdRelationWishes`.
- 4 overloaded ctors for partial dependency injection; full form used in bootstrap.
- `perform`: `requireFaction` → `requireOfficerOrAbove`. `getFactionByName(arg0)` empty → hardcoded `<red>Faction not found.` Same faction → hardcoded `<red>You cannot set a relation with your own faction.` Parse `Relation.valueOf(arg1.toUpperCase)`; bad → hardcoded `<red>Invalid relation. Use ally, truce, neutral, or enemy.`; `MEMBER` → hardcoded `<red>Invalid relation.`
- `setRelation(uuid, targetName, relation)` empty → `relation.set-failed`.
- **ALLY/TRUCE**: `isMutual` (both factions' relations JSON map each other to same relation) → `relation.mutual-established` to actor + notify target members `relation.mutual-established-target` + `sendRelationAnnouncement`. Else (one-sided) → `relation.pending-wish` to actor + notify target `relation.pending-received`.
- **NEUTRAL/ENEMY**: `relation.set` to actor + notify target `relation.updated-by-other`. If ENEMY → `sendRelationAnnouncement`.
- `notifyFactionMembers`: `FactionMemberNotifier.notifyMembers(null, repos, logger, factionId, member->true, message)`.
- `sendRelationAnnouncement`: ENEMY key `ezcountdown.relation-enemy` (`<red>⚔ {source} declared war on {target}!`), else `ezcountdown.relation-{name}` (`<green>🤝 {source} and {target} are now {display}!`). If `ezCountdownNotifier` enabled AND `notificationsConfig.isEzCountdownEnabled()` → `sendAnnouncement(msg, durationSeconds, displayTypes)`; else broadcast to all online. If `discordSrvNotifier` enabled AND `factionsConfig != null` → per-relation enabled flag + template (`isDiscordSrvRelationEnemy/Ally/TruceEnabled` + `getDiscordSrvRelation*Message`), `{source}`/`{target}` substituted.
- `parseRelations(json)`: hand-rolled `{ "key":"VALUE", ... }` parser (strip braces, split on `,`, split `:` limit 2, strip surrounding quotes, `Relation.valueOf`, ignore invalid). Same parser duplicated in CmdInfo.
- **Complete** arg0 = faction names; arg1 = `[ally,truce,neutral,enemy]`.
- **Relation enum** ordinal (hostility low→high): `MEMBER < ALLY < TRUCE < NEUTRAL < ENEMY`. `displayName()` = Member/Ally/Truce/Neutral/Enemy. Helpers: `isFriendly()` (MEMBER|ALLY), `isHostile()` (ENEMY), `isNeutralOrBetter()` (!ENEMY), `isAtLeast/isAtMost` (ordinal compare).

### 7.31 CmdLanguage — `/f language [code|reset]` (aliases `lang`, `locale`)
- Perm `factions.cmd.language`; Player yes; optional.
- `MessagesConfig` null → `language.system-unavailable`. `pm = players().findOrCreate(uuid)`; `StorageException` → `language.profile-load-failed`.
- **No args**: if `config.isLanguageCommandOpensGui()` AND `openLanguageMenu` succeeds → return; else `sendStatus`.
- If `!config.isLanguagePlayerOverrideEnabled()` → `language.override-disabled` + status, return.
- `reset` (ci): `pm.setLocale(null)` + save + `language.reset-success`; if `isLanguageCommandOpensGuiAfterSet()` → open menu; status.
- else `normalized = MessagesConfig.normalizeLocale(input)`; if not in `resolveVisibleLocales` → `language.invalid-code` (`{code}`) + status. Else `setLocale(normalized)` + save + `language.set-success` (`{code}`); post-set GUI; status.
- `sendStatus`: `language.current`, `language.default`, `language.available` (sorted list), `language.usage`. Save failure → `language.save-failed`.
- `resolveVisibleLocales`: `config.getLanguageVisibleLocales()`; empty → all available; else normalized intersection with available (empty result falls back to all).
- **Config keys**: `factions.language.allow-player-override` (def true), `.command-opens-gui` (def true), `.command-open-gui-after-set` (def true), `.visible-locales` (list), `.default` (def "en").
- **Complete** arg0: visible locales + `reset` (or just `reset` if messages null).

### 7.32 CmdHelp — `/f help [page]` (alias `?`)
- **No permission**; NOT player-only; optional `[page]`.
- `parsePage`: blank→1, clamp `1..3` (`TOTAL_PAGES=3`), bad→1.
- `help.title` gradient header. Switch page 1/2/3 (default→1). Footer.
- **Page 1**: `help.start-here` + 4 start-steps + `help.separator`; `sendSection("Core", [help,info,list,map,top,gui,language])`; `sendSection("Faction Setup", [create,rename,desc,disband])`.
- **Page 2**: sections "Members & Invites" `[invite,join,leave,kick,promote,demote,leader,role]`, "Land & Navigation" `[claim,unclaim,home,sethome,unsethome,warp,fly]`, "Economy & Utility" `[bank,notify,relation]`.
- **Page 3**: if `hasPermission("factions.cmd.kick")` → officer tips (`help.officer-title` + 4 lines). If `hasPermission("factions.admin")` → `help.admin-title` + `helpEntry` links for `/fa help|bypass|claim|unclaim|disband|reload`; `safezone`/`warzone` entries gated by their perms. Always `help.tip-notify`.
- `sendSection(title, keys)`: section header key `help.section.{slug}` (`slug` = lower, spaces→`-`, `&`→`and`); for each key look up `commandRegistry.get(key)`, skip if absent or perm not held, else `helpEntry(usage, description)`.
- **Footer**: `help.footer` (`{page}/{total}`, `{next}`) if `page<3`, else `help.footer-last`.
- **Complete** arg0 = `[1,2,3]`.

---

## 8. `FactionModel` constants referenced
- `FactionModel.SAFEZONE_ID`, `FactionModel.WARZONE_ID` — special faction ids used by `CmdMap` cell rendering. Model getters used across commands: `getName, getId, getOwnerId, getMotd, getDescription, getBank, getPowerBoost, getRelationsJson, isOwner(uuid), isRaidable, hasHome, getHomeWorld/X/Y/Z`.
- `PlayerModel`: `getPower, getRankId, getFactionId, getLocale, getLastActivity, getJoinedAt, getId`, notify flags `has/set{Invite,Territory→TerritoryTitles,BankTax,Motd}Notifications`.

---

## 9. Permission Node Catalog (from plugin.yml, scoped to in-scope commands)

| Node | Default | Command |
|---|---|---|
| `factions.admin` | op | `/fa` root (grants many children — see plugin.yml) |
| `factions.cmd.create` | true | create |
| `factions.cmd.disband` | true | disband |
| `factions.cmd.disband.other` | op | admin disband |
| `factions.cmd.rename` | true | rename |
| `factions.cmd.desc` | true | desc |
| `factions.cmd.motd` | true | motd |
| `factions.cmd.list` | true | list |
| `factions.cmd.map` | true | map |
| `factions.cmd.notify` | true | notify |
| `factions.cmd.language` | true | language |
| `factions.cmd.gui` | true | gui |
| `factions.cmd.top` | true | top |
| `factions.cmd.invite` | true | invite |
| `factions.cmd.join` | true | join |
| `factions.cmd.flag` | true | flag (list) |
| `factions.cmd.flag.set` | true | flag set |
| `factions.cmd.audit` | op | audit |
| `factions.cmd.leave` | true | leave |
| `factions.cmd.kick` | true | kick |
| `factions.cmd.promote` | true | promote |
| `factions.cmd.demote` | true | demote |
| `factions.cmd.leader` | true | leader |
| `factions.cmd.role` (+ .list/.create/.edit/.delete/.assign) | op | role |
| `factions.cmd.relation` | true | relation |
| `factions.cmd.merge` | true | merge |
| `factions.cmd.predefined` (+children) | op | predefined |
| `factions.cmd.claim` | true | claim |
| `factions.cmd.claim.other` | op | admin claim |
| `factions.cmd.unclaim` | true | unclaim |
| `factions.cmd.home` | true | home |
| `factions.cmd.sethome` | true | sethome, unsethome (shared) |
| `factions.cmd.fly` | true | fly |
| `factions.cmd.bank` (+.transfer op/.history) | true | bank |
| `factions.cmd.safezone` / `.warzone` | op | admin |
| `info` | (no node — open) | info, `?`/help (no node) |

`info` and `help` deliberately have `setPermission` unset (null) → accessible by all, including console.

---

## 10. Config Key Reference (defaults) used by in-scope commands
| Key | Default | Used by |
|---|---|---|
| `factions.max-members` | 50 | info |
| `factions.power.per-player-max` (getMaxPower) | 10.0 | info |
| `factions.land.max-per-command` | 200 | claim, unclaim |
| `factions.land.per-power` | 1.0 | info (max land calc) |
| `factions.land.max` | 500 | info |
| `factions.map.once-radius` | 3 | map |
| `factions.list.page-size` | 8 | list |
| `factions.top.page-size` | 8 | top |
| `factions.audit.page-size` | 10 | audit |
| `factions.info.relations.show-allies` | true | info |
| `factions.info.relations.show-truces` | false | info |
| `factions.info.relations.show-neutrals` | false | info |
| `factions.info.relations.show-enemies` | false | info |
| `factions.fly.enabled` | true | fly |
| `factions.fly.require-own-territory` | true | fly |
| `factions.language.allow-player-override` | true | language |
| `factions.language.command-opens-gui` | true | language |
| `factions.language.command-open-gui-after-set` | true | language |
| `factions.language.visible-locales` | (list) | language |

---

## 11. Reimplementation Checklist / Gotchas
1. `execute` runs the child's FULL pipeline (child perm re-checked) — a group command's parent perm does NOT gate children; each child has (or lacks) its own node.
2. `getUsage()` always prefixes `/f` even on `/fa` commands (no override in-scope).
3. `arg(i)` never throws — commands routinely read `arg(1)`/`arg(2)` that may be `""`; guard on `.isBlank()`.
4. "Confirm" is a literal case-insensitive `confirm` token check, no time window/state.
5. `unsethome` shares the `factions.cmd.sethome` node and skips `requireFaction` (only `requireOfficerOrAbove`).
6. `promote/demote/leader` use `Bukkit.getOfflinePlayer(name)` — never null — so bad names silently fail at the service call (no player-not-found message). `kick` uses exact-online, `invite` uses prefix-online.
7. Many success/error strings are hardcoded MiniMessage (NOT localizable) — reproduce exact strings for parity (create, rename, demote, unsethome, claim/unclaim area results, join, info body, relation validation).
8. `CmdInfo` keeps a per-instance `ConcurrentHashMap` `lastFactionBySender` for `/f info page`; the SAME `CmdInfo` instance is NOT shared between `/f` and `/fa` (two separate `new CmdInfo` in bootstrap), so page context is per-command-tree.
9. Relations JSON is parsed by a bespoke string parser (not a JSON lib) in both `CmdInfo` and `CmdRelation` — format `{"factionId":"RELATION",...}`.
10. TeamsAPI dispatch is a FALLBACK only when no registered `/f` subcommand matches `args[0]`; it can shadow nothing already registered.
11. `map --size` and `audit --action` are the only two in-scope commands using the `parseArguments` long-option parser.
12. Empty `/f` with a player opens the default GUI (if `guiManager.openDefault` succeeds) BEFORE falling back to help; empty `/fa` always shows help.
