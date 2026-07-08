# FableFactions Spec — Bank, Chest, Invite, Merge, Warp, Predefined, GUI

Clean-room behavioral spec derived from `the reference implementation`. Scope: command packages
`command/sub/{bank,chest,invite,merge,warp,predefined}`, the `predefined` package, and the `gui`
package, plus every service/engine/model they touch. An implementer who has never seen the original
must be able to reproduce behavior exactly from this document.

---

## 0. Cross-cutting concepts

### 0.1 Command framework (`FactionCommand`)
Every command is a `FactionCommand` node with:
- **name** (primary label), **aliases** (`setAliases(...)`), **permission** (`setPermission`), **description**.
- **required args** (`setRequiredArgs("<a>","<b>")`) and **optional args** (`setOptionalArgs("[x]")`); `getUsage()` is derived from these.
- `setRequiresPlayer(true)` — console senders are rejected before `perform` (console-safe commands omit it: `predefined list/reload`).
- Child dispatch: a group command holds children via `addChild`. When arg0 matches a child name/alias, that child runs; otherwise the group's own `perform` runs. Tab-completion for a group merges child names with the group's `complete(ctx, argIndex)` output.
- `CommandContext ctx` exposes: `getSender()`, `isPlayer()`, `arg(i)` (returns the i-th positional argument as String; out-of-range behavior treated as null/empty by callers via guards), `getArgs()` (List<String>), `getConfig()` (FactionsConfig), `getRepos()` (Repositories), `getLogger()`.

### 0.2 Guards (`CommandGuards`) — exact messages
| Guard | Check | Failure message key / default |
|---|---|---|
| `requireFaction(player)` | `factionService.getFactionByPlayer(uuid)` present | `general.not-in-faction` → `<red>You are not in a faction.` |
| `requireOwner(player)` | `factionService.isOwner(uuid)` | `general.must-be-owner` → `<red>Only the faction owner can do that.` |
| `requireOfficerOrAbove(player)` | `factionService.isOfficerOrAbove(uuid)` | `general.must-be-officer` → `<red>Only officers or above can do that.` |

### 0.3 Messaging (`MsgUtil`)
- `MsgUtil.send(sender, miniMessageString)` — direct send (MiniMessage tags like `<red>`, `<gold>`, `<white>`).
- `MsgUtil.sendKey(sender, key, defaultMiniMessage, k1, v1, k2, v2, ...)` — look up localized message by `key`; if absent, use the provided default; then substitute `{placeholder}` tokens with the supplied key/value varargs.
- `MsgUtil.message(key, default)` / `MsgUtil.message(player, key, default)` — resolve string without sending (sender variant is locale-aware).
- `MsgUtil.replace(str, k, v, ...)` — token substitution helper.
- `MsgUtil.toLegacy(mini)` — MiniMessage → legacy §-color string (used for inventory titles/item meta).
- Placeholder token syntax is `{name}` (curly braces), NOT `%name%`.

### 0.4 Money parsing (`MoneyParser.parse`) — EXACT
Input trimmed + lowercased. Last char is a magnitude suffix:
`k → 1_000`, `m → 1_000_000`, `b → 1_000_000_000`, `t → 1_000_000_000_000`, any other char → `1` (no suffix stripped).
Numeric portion = full string if multiplier==1, else string minus last char. Result = `Double.parseDouble(numeric) * multiplier`. Returns `OptionalDouble.empty()` on null/blank/`NumberFormatException`. Note: negative values parse fine here; callers reject `<= 0` afterward.

### 0.5 FactionModel bank field
`getBank()`/`setBank()` are **aliases** for `getMoney()`/`setMoney()` — a single `money` DOUBLE column. Any code that says "money" and "bank" refers to the same balance.
Identity helpers: `isWilderness()`, `isSafeZone()`, `isWarZone()` compare `getId()` to the reserved IDs `WILDERNESS_ID`/`SAFEZONE_ID`/`WARZONE_ID`. `isNormal()` = not any of those three (used to skip system factions during tax).

### 0.6 Config keys referenced by this scope (FactionsConfig + defaults)
| Getter | YAML key | Default |
|---|---|---|
| `getMaxMembers` | `factions.max-members` | 50 (0 = unlimited) |
| `getMaxWarps` | `factions.max-warps` | 10 |
| `getMaxTeamChests` | `factions.max-team-chests` | 1 |
| `getDefaultTeamChestName` | `factions.team-chest.default-name` | `default` |
| `getInviteTtlHours` | `factions.invites.ttl-hours` | 72 |
| `getBankHistoryPageSize` | `factions.bank.history.page-size` | 8 |
| `getWarpListPageSize` | `factions.warp.list.page-size` | 8 |
| `isEconomyEnabled` / `isBankEnabled` (alias) | `factions.economy.enabled` | true |
| `isTaxEnabled` | `factions.economy.tax.enabled` | false |
| `getTaxRate` | `factions.economy.tax.rate` | 0.05 |
| `getTaxIntervalHours` | `factions.economy.tax.interval-hours` | 24 |
| `getTaxMinBankBalance` | `factions.economy.tax.min-bank-balance` | 0.0 |
| `getTaxMinChargeAmount` | `factions.economy.tax.min-charge-amount` | 0.01 |
| `isMergeEnabled` | `factions.merge.enabled` | false |
| `isLanguagePlayerOverrideEnabled` | `factions.language.allow-player-override` | true |

NotificationsConfig (`notifications.yml`):
`member.notify-player-joined` (default true), `economy.tax.notify-members` (default true).
Per-player toggles on PlayerModel (default ON when unset): `notify_invites` (`hasInviteNotifications`), `notify_bank_tax` (`hasBankTaxNotifications`).

---

## 1. BANK — `/f bank`

Group `CmdBank` (children: deposit, withdraw, transfer, history). Dispatcher to `EngineEconomy`.

### 1.1 `/f bank` (group root)
- perm `factions.cmd.bank` (default `true`); requires player; optional args `[deposit|withdraw|transfer|history]`.
- Flow: `requireFaction`; then prints two lines:
  - `<gold>Faction bank balance: <white>{bank formatted %.2f, Locale.ROOT}</white>`
  - `<gray>Use <yellow>/f bank history</yellow> to view transactions.`

### 1.2 `/f bank deposit <amount>`
- name `deposit`; perm `factions.cmd.bank`; required `<amount>`; requires player.
- Flow: `requireFaction`; `MoneyParser.parse(arg0)` → empty ⇒ `<red>Invalid amount.`; `amount <= 0` ⇒ `<red>Amount must be positive.`; else `engineEconomy.deposit(player, factionId, amount)`.

### 1.3 `/f bank withdraw <amount>`
- Identical structure to deposit but calls `engineEconomy.withdraw(player, factionId, amount)`. Same two validation messages.

### 1.4 `/f bank transfer <faction> <amount>`
- name `transfer`; perm `factions.cmd.bank.transfer` (default **`op`**); required `<faction> <amount>`; requires player.
- Flow: `requireFaction(source)`; `requireOfficerOrAbove` (else stop); resolve target `factionService.getFactionByName(arg0)` → empty ⇒ `<red>Faction not found.`; if `source.id == target.id` ⇒ `<red>You cannot transfer to your own faction.`; parse `arg1` → empty ⇒ `<red>Invalid amount.`; `<=0` ⇒ `<red>Amount must be positive.`; call `engineEconomy.transfer(player.uuid, sourceId, targetId, amount)`.
  - success ⇒ `<green>Transferred <white>{%.2f Locale.ROOT}<green> to faction <white>{targetName}<green>.`
  - failure ⇒ `<red>Transfer failed.`
- Tab-complete arg0: all faction names (`getAllFactions().map(name)`).

### 1.5 `/f bank history [page]`
- name `history`; perm `factions.cmd.bank.history` (default `true`); optional `[page]`; requires player.
- Flow: `requireFaction`; `pageSize = max(1, getBankHistoryPageSize())`; `page = parsePage(arg0)` (non-numeric/blank/null → 1; else `max(1, parseInt)`); `offset = (page-1)*pageSize`.
- Query `repos.bankTransactions().findRecentByFactionId(factionId, pageSize, offset)`. On `StorageException` ⇒ `<red>Could not load bank history.`.
- Empty rows ⇒ `<yellow>No bank transactions found for this page.`
- Else header `<gold>== Faction Bank History (Page {page}) ==` then per row:
  - amount `%.2f Locale.ROOT`; color RED if `type.contains("OUT") || type.equals("WITHDRAW")`, else GREEN.
  - detail = note if non-blank else type. Actor name via `Bukkit.getOfflinePlayer(UUID)` name, fallback to raw UUID / `Unknown` if null/blank/invalid.
  - Line: `<gray>{yyyy-MM-dd HH:mm, default TZ} {color}{amount}<gray> - {detail} <dark_gray>by <white>{actor}`.

### 1.6 EngineEconomy semantics (the load-bearing logic)

**Preconditions checked in every deposit/withdraw call, in order:** `amount<=0` → `<red>Amount must be positive.`; `!vaultEconomy.isEnabled()` → `<red>Economy is not available.`; `!config.isBankEnabled()` → `<red>Faction banks are disabled.`.

**deposit(player, factionId, amount):**
1. Load faction; missing ⇒ `<red>Faction not found.`
2. `vaultEconomy.getBalance(player) < amount` ⇒ `<red>You do not have enough money.`
3. Fire `FactionBankTransactionEvent(faction, uuid, DEPOSIT, amount)`; if cancelled ⇒ return false silently. `finalAmount = event.getAmount()` (listeners may mutate amount).
4. Re-check `vaultEconomy.isEnabled()`.
5. `vaultEconomy.withdraw(player, finalAmount)` — if false ⇒ `<red>Could not withdraw money from your account.` (bank untouched).
6. `faction.setBank(getBank()+finalAmount)`; `repos.factions().save`; record tx `DEPOSIT`, note `"Player deposit"`.
7. `<green>Deposited <white>{%.2f}<green> into the faction bank.`

**withdraw(player, factionId, amount):**
1. Load faction; missing ⇒ `<red>Faction not found.`
2. `faction.getBank() < amount` ⇒ `<red>The faction bank does not have enough funds.`
3. Fire event type `WITHDRAW`; cancel ⇒ false. `finalAmount = event.getAmount()`.
4. Re-check Vault enabled.
5. **Debit bank first** (`setBank(bank-finalAmount)`, save), then `vaultEconomy.deposit(player, finalAmount)`. If deposit fails ⇒ **roll back** bank (`setBank(bank+finalAmount)`, save) and `<red>Could not credit your account.`
6. Record tx `WITHDRAW`, note `"Player withdraw"`; `<green>Withdrew <white>{%.2f}<green> from the faction bank.`

**transfer(invokerUuid, fromId, toId, amount) → boolean:**
- Guards: `amount<=0`, `!Vault`, `!bankEnabled` all return false (NO user message — the command prints "Transfer failed.").
- Wrapped in `repos.factions().transaction(...)`:
  - Reload both factions inside tx; either missing ⇒ abort (success stays false).
  - `from.getBank() < amount` ⇒ abort.
  - Re-check Vault enabled inside tx ⇒ abort if disabled.
  - `from.setBank(-amount)`, `to.setBank(+amount)`, save both.
  - Record two txs: on `from`: `TRANSFER_OUT`, counterparty=toId, note `"Transfer to {toName|toId}"`; on `to`: `TRANSFER_IN`, counterparty=fromId, note `"Transfer from {fromName|fromId}"`.
  - `success=true`.
- After tx, if success: reload `from` and fire `FactionBankTransactionEvent(from, invoker, TRANSFER, amount)` (informational; not cancellable at this point). Return success.
- **Note:** transfer does NOT touch Vault wallets and does NOT check officer rank itself — the command layer enforces officer+. It moves money bank→bank only.

**recordTransaction(factionId, actorUuid|null, type, amount, counterpartyId|null, note):** creates `BankTransactionModel(randomUUID)` with `type.toUpperCase(ROOT)`, `createdAt=System.currentTimeMillis()`, actor `uuid.toString()` or null. No-op if `repos.bankTransactions()==null`.

### 1.7 Periodic tax scheduler
- `startTaxScheduler(scheduler)`: stops any existing; returns early unless `isBankEnabled() && isTaxEnabled()`. `intervalHours = max(1, getTaxIntervalHours())`; `intervalTicks = intervalHours * 60 * 60 * 20` (async repeating timer). Logs `Faction bank tax enabled: rate=..., intervalHours=...`.
- `applyFactionTaxesNow()` (also callable manually) — EXACT math:
  - Return 0 unless bankEnabled && taxEnabled. `rate = getTaxRate()`; if `rate <= 0` return 0.
  - `minBank = max(0, getTaxMinBankBalance())`; `minCharge = max(0, getTaxMinChargeAmount())`.
  - For each faction in `findAll()`: skip if `!faction.isNormal()` (skips wilderness/safezone/warzone system factions). Let `currentBank = getBank()`; skip if `currentBank <= minBank`.
  - `computed = roundMoney(currentBank * rate)` where `roundMoney(v) = Math.round(v*100)/100.0`.
  - Skip if `computed <= 0 || computed < minCharge`.
  - `taxAmount = min(currentBank, computed)`; `newBank = roundMoney(max(0, currentBank - taxAmount))`; save.
  - Record tx `TAX`, actor null, note `"Periodic bank tax ({roundMoney(rate*100)}%)"`; increment count; notify members.
  - `notifyTaxedMembers`: only if `notificationsConfig.isEconomyTaxNotifyMembers()`; message key `bank.tax-charged` default `<gold>Faction bank tax charged: <yellow>{amount}<gold>. New bank: <yellow>{balance}<gold>.` (amount/balance formatted `%.2f Locale.US`); delivered only to members whose `hasBankTaxNotifications()` is true, via `FactionMemberNotifier`.

### 1.8 BankTransactionModel schema (`bank_transactions`)
Columns: `id VARCHAR(36)`, `faction_id`, `actor_uuid` (nullable), `type`, `amount DOUBLE`, `counterparty_faction_id` (nullable), `created_at BIGINT`, `note`. Query used: `findRecentByFactionId(factionId, limit, offset)` ordered most-recent first.
Transaction types seen: `DEPOSIT`, `WITHDRAW`, `TRANSFER_OUT`, `TRANSFER_IN`, `TAX`.

---

## 2. FACTION CHEST — `/f chest`

Group `CmdChest` (children: create, delete, list, open). Storage in DB, not physical blocks.

### 2.1 Storage model
- `TeamChestModel` (`team_chests`): `id`, `faction_id`, `name VARCHAR(64)`, `contents TEXT` (Base64), `created_at BIGINT`.
- Contents serialized by `TeamChestSerialization`: `BukkitObjectOutputStream` writes an `int` count then each `ItemStack` (including nulls for empty slots) → Base64. Decode reverses it. Empty/blank string decodes to empty list.
- Chest UI size is **fixed 54 slots** (`EngineTeamChests.CHEST_SIZE = 54`), regardless of stored content length.
- Chest **name normalization**: `trim().toLowerCase(Locale.ROOT)`; blank → invalid (null). Lookup is case-insensitive.

### 2.2 `/f chest` (group root — open default chest)
- perm `factions.cmd.chest` (default true); requires player.
- Flow: `requireFaction`; `chestName = getDefaultTeamChestName()` (default `"default"`); `ensureChestExistsForOpen(factionId, chestName)`:
  - returns existing name if a case-insensitive match exists; else if `count >= maxTeamChests` returns empty; else creates the chest and returns the normalized name.
  - empty ⇒ `chest.limit-reached` → `<red>Your faction has reached the maximum number of team chests ({max}).` with `max = getMaxTeamChests()`.
- `title = "Faction Chest: " + name`; `teamChestsEngine.openChest(...)` false ⇒ `chest.not-found` → `<red>Chest <yellow>{name}</yellow> was not found.`; success ⇒ `chest.opened` → `<green>Opened chest <yellow>{name}</yellow>.`

### 2.3 `/f chest create <name>`
- perm `factions.cmd.chest.create` (default true); required `<name>`; requires player; **officer+**.
- `name = arg0.toLowerCase(ROOT)`. Validity: non-null, non-blank, `length<=32`, regex `[a-z0-9_-]+` → else `chest.invalid-name` → `<red>Invalid chest name.`
- If a chest with that name exists (case-insensitive over `getChestNames`) ⇒ `chest.already-exists` → `<red>Chest <yellow>{name}</yellow> already exists.`
- If `current.size() >= getMaxTeamChests()` ⇒ `chest.limit-reached` (as above).
- `teamChestService.createChest` false ⇒ `chest.create-failed` → `<red>Could not create chest <yellow>{name}</yellow>.`; success ⇒ `chest.created` → `<green>Created chest <yellow>{name}</yellow>.`
- Service-side `createChest` re-checks duplicate + limit (defense in depth) and stores empty contents.

### 2.4 `/f chest delete <name>`
- perm `factions.cmd.chest.delete` (default true); required `<name>`; requires player; **officer+**.
- `name = arg0.toLowerCase(ROOT)`. `deleteChest` false (not found) ⇒ `chest.not-found`; success ⇒ `chest.deleted` → `<green>Deleted chest <yellow>{name}</yellow>.`

### 2.5 `/f chest list`
- perm `factions.cmd.chest` (default true); requires player. No officer requirement.
- Names via `getChestNames` (sorted `CASE_INSENSITIVE_ORDER`). Empty ⇒ `chest.none` → `<yellow>Your faction has no team chests.`; else header `chest.list-header` → `<gold>== Team Chests ==` then per name `chest.list-entry` → `<gray>- <yellow>{name}</yellow>`.

### 2.6 `/f chest open <name>`
- perm `factions.cmd.chest` (default true); required `<name>`; requires player. No officer requirement (any member may open).
- `requestedName = arg0.toLowerCase(ROOT)`; `ensureChestExistsForOpen` (so opening a non-existent name will CREATE it if under limit, else `chest.limit-reached`). Then same open/not-found/opened messages as §2.2.

### 2.7 Open/persist engine (`EngineTeamChests`, a Bukkit `Listener`)
- `openChest(player, factionId, name, title)`: load contents (`getChestContents`), missing chest ⇒ false. Create a 54-slot inventory owned by the player; copy up to 54 stored items into slots 0..n. Register a session `sessions.put(playerUuid, {factionId, name})` (a `ConcurrentHashMap<UUID, session>`). `player.openInventory`.
- `onInventoryClose`: pop the session for that player (`sessions.remove`). If none, ignore (not a faction chest). Snapshot `event.getInventory().getContents()` into a list and `setChestContents(factionId, name, items)`; on failure log a warning.
- **Concurrency / dupe caveat (reproduce as-is or fix intentionally):** sessions are keyed by **player UUID only**. If a player somehow opens two chests, only the last is tracked. Two different players can open the *same* named chest simultaneously; on close, **last writer wins** — the second player's close overwrites with their snapshot, which can duplicate or erase items. There is no per-chest lock or "already open" guard. A clean-room reimplementation should add a per-(faction,chest) open guard or merge-on-close to prevent item dupe/loss.

---

## 3. INVITE — `/f invite`

Group `CmdInvite` (alias `inv`); children: list, revoke, accept, decline, declineall. Backed by `InviteService`/`InviteServiceImpl` and `InvitationModel`.

### 3.1 Invitation model + lifecycle
- `InvitationModel` (`invitations`): `id`, `faction_id`, `invitee_id` (UUID str), `inviter_id` (UUID str), `created_at BIGINT`.
- **TTL:** `isActive(invite)` = `now <= createdAt + max(1, getInviteTtlHours())*3600_000ms`. Default TTL 72h.
- **Uniqueness:** at most one pending invite per (faction, invitee); `sendInvite` returns false if one already exists.
- **Pruning:** most read/write ops first call `pruneExpiredInvitesForPlayer(invitee)` which deletes expired rows for that player. `pruneAllExpiredInvites()` sweeps everything (used by a scheduler elsewhere). Expired invites are treated as absent.

### 3.2 `/f invite <player>` (group root — send invite)
- perm `factions.cmd.invite` (default true); optional `[player|list|revoke|accept|decline|declineall]`; requires player; alias `inv`.
- No args ⇒ `general.invalid-args` → `<red>Usage: {usage}` with `{usage}=getUsage()`.
- `requireFaction`; `requireOfficerOrAbove`.
- Target must be **online** (`Bukkit.getPlayer(arg0)`); null ⇒ `general.player-not-found` → `<red>Player <yellow>{name}</yellow> not found.`
- If target already in a faction ⇒ `custom.invite.target-already-in-faction` → `<red>That player is already in a faction.`
- `inviteService.sendInvite(factionId, inviterUuid, targetUuid)`:
  - success ⇒ `custom.invite.sent` → `<green>Invited <white>{player}<green>.`; THEN if the target's `hasInviteNotifications()` is on, send them a rich `MsgUtil.inviteNotification(target, factionName)` (an `[Accept]` clickable that runs `/f join <faction>`). On StorageException reading the target model, fall back to sending the raw notification.
  - false ⇒ `custom.invite.already-pending` → `<red>Could not invite that player (already pending?).`
- Tab-complete arg0: online player names.

### 3.3 `/f invite accept <faction>`
- perm `factions.cmd.join` (default true); required `<faction>`; requires player.
- If already in a faction ⇒ `<red>You are already in a faction.` (raw, no key).
- Resolve faction by name; missing ⇒ `<red>Faction not found.`
- `inviteService.acceptInvite(factionId, uuid)` returns `Optional<FactionModel>`:
  - present ⇒ `<green>You joined <white>{name}<green>!`
  - empty ⇒ `<red>You do not have a pending invite from that faction.`
- **acceptInvite internals:** prune expired for player; find invite by (faction, invitee) — empty ⇒ empty. Load faction — if faction gone, delete invite and return empty. Require a default rank (`repos.ranks().findDefaultRank`) — empty ⇒ empty. **Delete the invite**, then `factionService.joinFaction(factionId, uuid)`; if join fails return empty; else return reloaded faction.
- Note: there is also `/f join` handled elsewhere; the rich invite notification button targets `/f join <faction>`.

### 3.4 `/f invite decline <faction>`
- perm `factions.cmd.invite` (default true); required `<faction>`; requires player.
- Resolve faction by name; missing ⇒ `<red>Faction not found.`
- `declineInvite` (which just calls `revokeInvite`): true ⇒ `<yellow>Invite declined.`; false ⇒ `<red>You do not have a pending invite from that faction.`
- Tab-complete arg0: names of factions the player has invites from.

### 3.5 `/f invite declineall`
- perm `factions.cmd.invite` (default true); requires player.
- `declineAllInvites(uuid)` prunes expired, counts current invites, `deleteByInviteeId`, returns count. Message: `>0` ⇒ `<yellow>Declined <white>{n}<yellow> invite(s).`; else `<yellow>You have no pending invites.`

### 3.6 `/f invite list [faction]`
- perm `factions.cmd.invite.list` (default true); optional `[faction]`; requires player.
- **No arg (self):** `listActiveInvitesForPlayer(uuid)` (prunes then filters active). Empty ⇒ `<yellow>You have no pending invites.`; else header `<gold>You have <white>{n}<gold> pending faction invite(s):` and per invite `MsgUtil.inviteListEntry(player, factionName, inviterName)` where factionName resolved from id (fallback `Unknown`) and inviter resolved via OfflinePlayer name (fallback raw id).
- **With arg (other faction) — requires `factions.admin` permission** (not officer rank). Missing perm ⇒ `<red>You do not have permission to list another faction's invites.` Resolve faction; missing ⇒ `<red>Faction not found.` `listInvitesForFaction(id)` (active only). Empty ⇒ `<yellow>No pending invites for <white>{name}<yellow>.`; else header `<gold>== Pending Invites: <white>{name}<gold> ==` and per invite `<yellow>- <white>{inviteeName|inviteeId}`.

### 3.7 `/f invite revoke <player>`
- perm `factions.cmd.invite.revoke` (default true); required `<player>`; requires player; `requireFaction` + **officer+**.
- `Bukkit.getOfflinePlayer(arg0)`; if uuid null ⇒ `<red>Player not found.`
- `revokeInvite(factionId, targetUuid)` (prunes; find by faction+invitee; delete): true ⇒ `<yellow>Invite revoked for <white>{arg0}<yellow>.`; false ⇒ `<red>No pending invite for that player.`
- Tab-complete arg0: invitee names for the actor's faction's pending invites.

### 3.8 Join side effects (`FactionServiceImpl.joinFaction`)
- Load faction + default rank (both required). **Member cap:** `maxMembers = getMaxMembers()`; if `>0` and current member count `>= maxMembers` ⇒ false (join rejected). Inside a transaction: `findOrCreate` PlayerModel, set faction, set rank to default rank, `joinedAt = now`; `deleteByInviteeId(playerId)` (clears all their pending invites). After tx: notify existing faction members (if `member.notify-player-joined`) with `member.player-joined` → `<green>{player} joined your faction.` (excluding the joiner), and fire `FactionJoinEvent`.

---

## 4. MERGE — `/f merge`

Group `CmdMerge` (perm `factions.cmd.merge`, default true; children send, accept). Root prints help:
`custom.merge.help-title` `<gold>== Faction Merge ==`; `custom.merge.help-send` `<yellow>/f merge send <faction> <gray>- Send a merge request`; `custom.merge.help-accept` `<yellow>/f merge accept <faction> <gray>- Accept a merge request`.

Backed by `MergeService`/`MergeServiceImpl`, `MergeRequestModel`, and `FactionServiceImpl.mergeFaction`.

### 4.1 MergeRequestModel (`merge_requests`)
`id`, `sender_faction_id`, `target_faction_id`, `actor_id`, `created_at`. **No TTL** — requests persist until accepted or cleaned up. `sender` = faction that dissolves; `target` = faction that absorbs.

### 4.2 `/f merge send <faction>`
- perm `factions.cmd.merge`; required `<faction>`; requires player.
- If `!isMergeEnabled()` ⇒ `merge.disabled` → `<red>Faction merging is not enabled on this server.`
- `requireFaction(sender)`; `requireOfficerOrAbove`.
- Resolve target by name; missing ⇒ `merge.target-not-found` → `<red>Faction <yellow>{faction}<red> not found.` (`{faction}`=arg0).
- If `sender.id == target.id` ⇒ `merge.self-merge` → `<red>You cannot merge your faction into itself.`
- `mergeService.sendMergeRequest(senderId, targetId, actorUuid)`:
  - false (duplicate existing sender→target request, or either faction gone) ⇒ `merge.already-requested` → `<red>A merge request to <yellow>{faction}<red> is already pending.` (`{faction}`=targetName).
  - true ⇒ `merge.request-sent` → `<green>Merge request sent to <yellow>{faction}<green>.`; then notify ALL members of the **target** faction (`member -> true`) with `merge.request-received` → `<yellow>{faction}<green> has sent a merge request to your faction. Use <white>/f merge accept {faction}<green> to absorb them.` (`{faction}`=sender name).

### 4.3 `/f merge accept <faction>`
- perm `factions.cmd.merge`; required `<faction>` (the SENDER faction's name); requires player.
- If `!isMergeEnabled()` ⇒ `merge.disabled`.
- `requireFaction(target = accepting faction)`; `requireOfficerOrAbove`.
- Resolve sender by name; missing ⇒ `merge.target-not-found`.
- `mergeService.acceptMergeRequest(senderId, targetId, actorUuid)`:
  - Checks a pending `findBySenderAndTarget(senderId, targetId)` exists; else empty ⇒ `merge.no-request-found` → `<red>No pending merge request from <yellow>{faction}<red> was found.` (`{faction}`=sender name).
  - On success: notify all members of the merged-into (target) faction with `merge.accepted` → `<yellow>{faction}<green> has merged into your faction!`; then send actor `merge.merged-into` → `<green>Successfully merged <yellow>{faction}<green> into your faction.`

### 4.4 What actually merges (`FactionServiceImpl.mergeFaction`) — EXACT
Preconditions: both factions exist; target has a default rank. Then, inside a single `repos.factions().transaction`:
1. **Bank/money:** `target.setMoney(target.getMoney() + sender.getMoney())`, save target. (Sender's whole balance moves.)
2. **Claims/land:** every board entry with `faction_id = senderId` is re-pointed to `targetId` and saved.
3. **Warps:** every warp with `faction_id = senderId` is re-pointed to `targetId`.
4. **Members:** every PlayerModel in sender is re-pointed to target, `rankId = target default rank`, `joinedAt = now`.
5. **Cleanup of sender:** delete sender's invitations (`deleteByFactionId`); delete merge requests where sender is sender (`deleteBySenderFactionId`) or target (`deleteByTargetFactionId`); delete sender's ranks (`deleteByFactionId`); `clearIncomingRelations(senderId)`; `repos.factions().delete(senderId)`.
- After the tx (outside): fire `FactionJoinEvent(target, uuid)` for each migrated member (collected BEFORE the tx). Audit `MERGE_ACCEPT` with sender name on the target.
- **NOT merged / caveats:** sender's own *outgoing/held* relations map is discarded (only incoming references to sender are cleared via `clearIncomingRelations`; the target keeps its own relations, no union). Bank transaction history rows are NOT migrated (they still reference the deleted sender id). Team chests are NOT migrated (only claims, warps, members, money). There is no member-cap check during merge (can exceed `max-members`). Merge accept requires officer+ of TARGET only; sender-side never re-confirms.

---

## 5. WARPS — `/f warp`

Group `CmdWarp` (perm `factions.cmd.warp`, default true). Children: set, delete (alias `remove`), list, password, cost. Backed by `WarpService`/`WarpServiceImpl` and `WarpModel`.

### 5.1 WarpModel (`warps`)
`id`, `faction_id`, `name VARCHAR(64)`, `world`, `x/y/z DOUBLE` (defaults 0/64/0), `yaw/pitch FLOAT`, `creator_id` (nullable), `created_at`, `password VARCHAR(64)` (nullable), `use_cost DOUBLE` default 0.
- `hasPassword()` = password non-null non-empty. `hasCost()` = `use_cost > 0`. `toLocation()` = null if world name null or world not loaded.

### 5.2 `/f warp` (root: list OR teleport)
- perm `factions.cmd.warp`; requires player. `requireFaction`.
- **Jail gate:** `essentialsInterop.isJailed(player)` ⇒ `warp.jailed` → `<red>You cannot use warps while jailed.` (applies to both list and teleport paths since checked first).
- **No args ⇒ list:** `warpService.getWarps(id)`. Empty ⇒ `custom.warp.none` → `<yellow>Your faction has no warps.`; else header `custom.warp.header` → `<gold>== Faction Warps ==` and each `MsgUtil.warpEntry(name)`.
- **arg0 = warp name ⇒ teleport:** `warpName = arg0.toLowerCase()`. `getWarp(id, warpName)` empty ⇒ `warp.not-found` → `<red>Warp <yellow>{name}</yellow> not found.`
  - **Password:** if `warp.hasPassword()`, supplied = arg1 (or null). If null or `!password.equals(supplied)` ⇒ `warp.password-required` → `<red>This warp requires a password: /f warp {name} <password>` (plain-text equality; case-sensitive; no hashing).
  - **Cost:** if `warp.hasCost()`: if `vaultEconomy==null || !isEnabled()` ⇒ `warp.cost-no-economy` → `<red>An economy plugin is required to use this warp.` `cost = getUseCost()`; `balance < cost` ⇒ `warp.cost-insufficient` → `<red>You need <gold>{cost}</gold> to use this warp (balance: <gold>{balance}</gold>).` (both `%.2f`). Else `vaultEconomy.withdraw(player, cost)` and `warp.cost-charged` → `<green>Charged <gold>{cost}</gold> for warp <yellow>{name}</yellow>.` (also passes `balance` = new balance). **Note:** withdraw return value is not checked; charge assumed to succeed after the balance check.
  - **Destination:** `dest = warp.toLocation()`; if null or world null ⇒ `custom.warp.world-not-loaded` → `<red>Warp world not loaded.` (cost was already charged — no refund).
  - **Teleport:** `essentialsInterop.teleport(player, dest, onSuccess, onFailure)`. If it returns true, the interop owns the result (async): onSuccess ⇒ `warp.teleported` → `<green>Teleported to warp <yellow>{name}</yellow>.`; onFailure ⇒ `warp.teleport-failed` → `<red>Warp teleport failed.`. If interop returns false (no Essentials / no user), fall back to `player.teleport(dest)` then send `warp.teleported`.
  - **No warmup/cancel-on-move:** EssentialsX interop uses `getAsyncTeleport().now(dest, true, COMMAND, future)` — an immediate teleport (it does record `setLastLocation()` so `/back` works). There is NO countdown, NO movement/damage cancellation implemented in this plugin.
- Tab-complete arg0: the faction's warp names (merged with child names by the framework).

### 5.3 `/f warp set <name> [x] [y] [z] [world]`
- perm `factions.cmd.setwarp` (default true); required `<name>`, optional `[x][y][z][world]`; requires player; **officer+**.
- Location resolution: if fewer than 4 args ⇒ player's current location. Else parse x/y/z as doubles (NumberFormatException ⇒ fall back to player location); world = arg4 if non-blank and loaded (unloaded/blank ⇒ player's world / player location fallback). Yaw/pitch taken from player's current facing.
- **Territory guard:** `territoryGuard.canModifyTerritory(player, target)` false ⇒ `custom.warp.set-protected` → `<red>You cannot set a warp in this protected region.`
- `warpService.setWarp(id, name, target, uuid)`: true ⇒ `warp.set` → `<green>Warp <yellow>{name}</yellow> set.`; false ⇒ `custom.warp.set-failed` → `<red>Could not set warp (limit reached?).`
- **setWarp internals:** faction must exist and `location.getWorld() != null`. New warp (name not already present) is rejected if `currentCount >= getMaxWarps()` (limit applies only to NEW warps; overwriting an existing name is always allowed). Stores world name + coords + yaw/pitch; sets `creatorId` if provided; sets `createdAt=now` only for new warps. Case: `findByFactionIdAndName` — name matching handled by repo (typically exact/normalized as stored).

### 5.4 `/f warp delete <name>` (alias `remove`)
- perm `factions.cmd.setwarp` (default true); required `<name>`; requires player; **officer+**.
- `deleteWarp(id, name)`: true ⇒ `warp.deleted` → `<yellow>Warp <yellow>{name}</yellow> deleted.`; false (not found) ⇒ `warp.not-found`.
- Tab-complete arg0: faction warp names.

### 5.5 `/f warp list [page]`
- perm `factions.cmd.warp` (default true); optional `[page]`; requires player. `requireFaction`.
- Empty warps ⇒ `custom.warp.none`. Else `pageSize = max(1, getWarpListPageSize())` (default 8); `page = max(1, parseInt(arg0))` (non-numeric ⇒ 1); `start = max(0,(page-1)*pageSize)`; `end = min(size, start+pageSize)`. Header `custom.warp.header-page` → `<gold>== Faction Warps (Page {page}) ==`; each `MsgUtil.warpEntry(name)`.

### 5.6 `/f warp password <name> [password | clear]`
- perm `factions.cmd.warp.password` (default true); required `<warpName>`, optional `[password|clear]`; requires player; **officer+**.
- No args ⇒ `general.invalid-args` → `<red>Usage: /f warp password <warpName> [password | clear]` (also passes `usage`).
- `warpName = arg0.toLowerCase()`; unknown warp ⇒ `warp.not-found`.
- If only the name (arg count < 2) ⇒ show status: `<yellow>Warp <white>{name}</white> has a password set.` or `... has no password.` (raw, no key).
- arg1 == `clear` (case-insensitive) ⇒ `setWarpPassword(id, name, null)`, `warp.password-cleared` → `<yellow>Password cleared for warp <yellow>{name}</yellow>.`
- else ⇒ `setWarpPassword(id, name, value)`, `warp.password-set` → `<green>Password set for warp <yellow>{name}</yellow>.`
- `setWarpPassword` treats null/empty as "clear" (stores null). Passwords stored/compared in **plaintext**.
- Tab-complete: arg0 warp names; arg1 → `["clear"]`.

### 5.7 `/f warp cost <warpName> <amount>`
- perm `factions.cmd.warp.cost` (default true); required `<warpName> <amount>`; requires player; **officer+**.
- If arg count < 2 ⇒ `general.invalid-args` → `<red>Usage: /f warp cost <warpName> <amount>`.
- `warpName = arg0.toLowerCase()`; unknown ⇒ `warp.not-found`.
- Parse arg1 as double; NumberFormatException or `< 0` ⇒ `bank.invalid-amount` → `<red>Amount must be a non-negative number.`
- `setWarpCost(id, name, amount)` (stored as `max(0, amount)`); `warp.cost-set` → `<green>Use cost for warp <yellow>{name}</yellow> set to <gold>{cost}</gold>.` (`%.2f`). `0` makes the warp free.
- Tab-complete arg0: warp names.

---

## 6. PREDEFINED FACTIONS — `/f predefined` and the `predefined` package

`CmdPredefined` (alias `prefined`; perm `factions.cmd.predefined`, default **`op`**). Children: create, claim, sethome, reload, list. Backed by `PredefinedConfigManager` (a process-wide singleton) reading `pre-defined.yml`.

### 6.1 What predefined factions ARE
This is NOT a safezone/warzone system. Safezone/warzone are separate system factions (reserved IDs in FactionModel, §0.5). Predefined factions are a **whitelist of allowed faction names** plus optional seed data (home + pre-claimed chunks). When enabled, it restricts which faction names players may create and can protect them from disband. They are seeded on first creation.

### 6.2 `pre-defined.yml` schema
Default file (auto-created if missing):
```yaml
enabled: false
case-sensitive: false
block-disband: true
factions: {}
```
Per-faction entries live under `factions.<key>`:
- `name` (string, defaults to the section key)
- `created` (boolean; flips to true after the seed is applied once)
- `home`: `{world, x, y, z, yaw, pitch}` (home parsed only if `world` non-blank; yaw/pitch default 0)
- `claims`: list of `{world, x, z}` (chunk coordinates; x/z coerced to int)
- Name lookup key = `normalize(name)` = lowercased unless `case-sensitive: true`.

### 6.3 `PredefinedConfigManager` behavior
- Singleton via `setInstance`/`getInstance` (may be null if not initialized).
- `initialize()`: create default file if absent, then `reload()`.
- `reload()`: reloads `enabled`, `case-sensitive`, `block-disband`, and rebuilds an immutable `LinkedHashMap` of presets keyed by normalized name (insertion-ordered).
- `savePreset(preset)`: writes name/created/home/claims back to YAML and updates the in-memory map.
- `setCreated(name, bool)`, `setHome(name, Location)` (creates a preset shell if absent), `addClaim(name, world, x, z)` (dedups identical claims).
- `getPreset(name)`, `isPredefinedName(name)`, `presetNames()` (distinct display names, insertion order), `allPresets()`, `toLocation(home)` (null if world unloaded).
- Persistence failures log SEVERE but don't throw.

### 6.4 Enable-gate for subcommands
`PredefinedCommandSupport.requireEnabled(ctx)` returns null (and sends `predefined.disabled` → `<red>Predefined factions are disabled.`) unless the singleton exists AND `isEnabled()`. `create/claim/sethome/list` require this; `reload` does NOT (it works even when disabled, only failing if the singleton itself is null → `predefined.reload-failed` → `<red>Predefined manager is not available.`).

### 6.5 `/f predefined create <faction>`
- perm `factions.cmd.predefined.create` (default op); required `<faction>`; requires player.
- requireEnabled; if player already in a faction ⇒ `<red>You are already in a faction.` (raw).
- If `!isPredefinedName(arg0)` ⇒ `predefined.unknown` → `<red>Unknown predefined faction: <white>{faction}`.
- `factionService.createFaction(name, uuid)` present ⇒ `faction.created` → `<green>Faction <yellow>{name}</yellow> created.`; empty ⇒ `faction.name-taken` → `<red>That faction name is already taken.`
- Tab-complete arg0: sorted preset names.

### 6.6 `/f predefined claim <faction>`
- perm `factions.cmd.predefined.claim` (default op); required `<faction>`; requires player.
- requireEnabled; unknown name ⇒ `predefined.unknown`.
- Uses the player's current chunk: `manager.addClaim(faction, world, chunk.x, chunk.z)`. Message `predefined.claim-saved` → `<green>Saved predefined claim for <yellow>{faction}</yellow> at <white>{x}</white>,<white>{z}</white>.` This edits `pre-defined.yml` only; it does NOT claim land live.
- Tab-complete: sorted preset names.

### 6.7 `/f predefined sethome <faction>`
- perm `factions.cmd.predefined.sethome` (default op); required `<faction>`; requires player.
- requireEnabled; unknown ⇒ `predefined.unknown`. `manager.setHome(faction, player.getLocation())`. Message `predefined.home-saved` → `<green>Saved predefined home for <yellow>{faction}</yellow>.`
- Tab-complete: sorted preset names.

### 6.8 `/f predefined list`
- perm `factions.cmd.predefined.list` (default op); console-allowed (no requiresPlayer).
- requireEnabled; empty ⇒ `predefined.none` → `<yellow>No predefined factions configured.`; else `<gold>Predefined factions:</gold> <white>{comma-joined names}</white>`.

### 6.9 `/f predefined reload`
- perm `factions.cmd.predefined.reload` (default op); console-allowed.
- Singleton null ⇒ `predefined.reload-failed`. Else `manager.reload()` + `predefined.reload-success` → `<green>Predefined factions reloaded.`

### 6.10 Seeding on creation (`FactionServiceImpl.applyPredefinedSeedIfNeeded`)
Called during `createFaction` (after the faction row exists). No-op if manager null or `!isEnabled()`, or the name has no preset, or `preset.created()` is already true. Otherwise, inside a transaction:
- If `preset.home() != null`: set the faction's home world/x/y/z/yaw/pitch from the preset and save.
- For each claim `{world,x,z}`: if that chunk is unclaimed (`board().findByChunk` empty), `board().claimChunk(world, x, z, factionId)` (skips already-claimed chunks; no conflict/overwrite).
Then `manager.setCreated(preset.name(), true)` so it seeds only once. Failures log WARNING.

### 6.11 Interplay with create/disband (outside this package, but load-bearing)
- **`/f create <name>`:** when predefined is enabled, a non-predefined name is rejected with `predefined.create-not-allowed` → `<red>You can only create predefined factions on this server.` (Normal name-length rule: 3–32 chars still applies afterward.)
- **`/f disband` and `/fa disband`:** if predefined enabled AND `block-disband` AND the faction's name is a predefined name ⇒ `predefined.disband-blocked` → `<red>Predefined factions cannot be disbanded.` (blocks both self-disband and admin disband).

---

## 7. GUI — `FactionsGuiManager`

A Bukkit `Listener` that renders config-driven menus from `gui.yml` under `gui.menus.<id>`. Opened via `/f` (default menu) and by GUI actions. Not a subcommand itself but part of the `gui` package in scope.

### 7.1 Opening menus
- `openDefault(player)`: no-op returning false if `!guiConfig.isEnabled()`; else `openMenu(player, guiConfig.getDefaultMenu())`.
- `openMenu(player, menuId)`: reads section `gui.menus.<menuId>`; missing ⇒ false. `size = normalizeSize(section.getInt("size",54))` where normalizeSize caps to [9,54] then rounds UP to a multiple of 9. Title rendered (MiniMessage → legacy). Inventory owned by a private `MenuHolder(menuId)` (used to identify the plugin's menus on click/close).
- Items: iterate `items.<key>`; each has `slot` (must be `0 <= slot < size`, else skipped). `shouldHideItem` may skip language items when overrides disabled or locale unsupported. Build item and place.
- Tracks the open menu per player in `openMenus` (ConcurrentHashMap).

### 7.2 Item building
- `material` via `Material.matchMaterial(section.getString("material","PAPER"))`, fallback PAPER.
- Display name: prefer localized `gui.items.<key>.name` from MessagesConfig (locale of player), else `section.name`, else `<white>Factions`. Lore: prefer localized `gui.items.<key>.lore` list, else `section.lore`.
- `glow: true` ⇒ enchant glint override. Always add `HIDE_ATTRIBUTES, HIDE_ENCHANTS`. Name/lore rendered through placeholder substitution then `toLegacy`.

### 7.3 Placeholders (`render`)
Substituted (all curly-brace tokens): `{player}`, `{faction}` (name or `Wilderness`), `{faction_members}` (count), `{faction_land}` (`board().countByFactionId`), `{faction_bank}` (`%.2f` US), `{power}` (self player power, `%.2f`), `{max_power}` (`cfg.getMaxPower()`, `%.2f`), `{language_current}`, `{language_default}`, `{language_available}` (comma list). On StorageException, a safe fallback substitutes zeros/`Wilderness`/`en`.

### 7.4 Click actions (`onClick` cancels the click, matches by rawSlot)
`action` (upper-cased, default `NONE`):
- `RUN_COMMAND`: close inventory, `player.performCommand(command)` (strips leading `/`). Default command `f help`.
- `SUGGEST_COMMAND`: close, send `custom.gui.suggested-command` → `<gray>Suggested: <yellow>{command}`.
- `OPEN_MENU`: open `menu` (default = default menu). If open fails ⇒ `custom.gui.menu-not-configured` → `<red>Menu '{menu}' is not configured.`
- `LANGUAGE_SET`: if `!isLanguagePlayerOverrideEnabled()` ⇒ `language.override-disabled`. If MessagesConfig null ⇒ `language.system-unavailable`. Normalize requested locale; if not in visible locales ⇒ `language.invalid-code` → `<red>Unsupported language code: <white>{code}</white>.` Else save `PlayerModel.locale`, send `language.set-success` → `<green>Language updated to <white>{code}</white>.`, and re-open the current menu. StorageException ⇒ `language.save-failed`.
- `LANGUAGE_RESET`: same gating; sets locale to null, `language.reset-success`, re-opens.
- `CLOSE`: close inventory. `REFRESH`: re-open current menu. default ⇒ no-op.

### 7.5 Visible locale resolution
`resolveVisibleLocales`: intersect `MessagesConfig.getAvailableLocales()` with configured `cfg.getLanguageVisibleLocales()` (normalized); if configured empty/none-valid, fall back to all available. `shouldHideItem` hides LANGUAGE_SET/RESET items when overrides disabled, and hides a LANGUAGE_SET item whose target locale is blank or not visible.

---

## 8. Permission node summary (defaults from plugin.yml)

| Node | Default | Commands |
|---|---|---|
| `factions.cmd.bank` | true | bank, deposit, withdraw |
| `factions.cmd.bank.transfer` | **op** | bank transfer |
| `factions.cmd.bank.history` | true | bank history |
| `factions.cmd.chest` | true | chest (root), chest list, chest open |
| `factions.cmd.chest.create` | true | chest create (officer+) |
| `factions.cmd.chest.delete` | true | chest delete (officer+) |
| `factions.cmd.invite` | true | invite (root/send), decline, declineall |
| `factions.cmd.invite.list` | true | invite list |
| `factions.cmd.invite.revoke` | true | invite revoke |
| `factions.cmd.join` | true | invite accept |
| `factions.admin` | op | invite list `<faction>` (other faction) |
| `factions.cmd.merge` | true | merge send/accept |
| `factions.cmd.warp` | true | warp (root/teleport), warp list |
| `factions.cmd.setwarp` | true | warp set, warp delete |
| `factions.cmd.warp.password` | true | warp password (officer+) |
| `factions.cmd.warp.cost` | true | warp cost (officer+) |
| `factions.cmd.predefined` | op | predefined (root) |
| `factions.cmd.predefined.{create,claim,sethome,reload,list}` | op each | respective |

Officer-or-above (rank-based, NOT permission) additionally gates: bank transfer, chest create/delete, invite send/revoke, merge send/accept, warp set/delete/password/cost.

---

## 9. Edge cases & reimplementation notes (checklist)

- Money format: bank uses `Locale.ROOT` for `%.2f` in command/history lines; tax notifications and GUI use `Locale.US`. Both render `.` decimals but keep this consistent to match snapshots.
- `MoneyParser` accepts negatives; positivity enforced separately with two distinct messages (`Invalid amount.` vs `Amount must be positive.`).
- Deposit withdraws from the player wallet BEFORE crediting the bank; withdraw debits the bank BEFORE crediting the wallet and rolls back on wallet-credit failure. Transfer is fully transactional bank→bank with no wallet involvement.
- Tax skips system factions (`!isNormal()`), factions at/below `min-bank-balance`, and charges below `min-charge-amount`; rounds to 2 dp via `Math.round(v*100)/100`.
- Team chest: fixed 54 slots; names lowercased; opening a missing name auto-creates it (subject to `max-team-chests`); **concurrent open by two players → last-close-wins overwrite (dupe/loss risk)** — add locking in the rewrite.
- Invite TTL default 72h; expiry lazily pruned on access; one invite per (faction, invitee); accepting deletes the invite then joins (respecting member cap; join failure leaves the invite deleted — the player must be re-invited).
- Invite list for another faction is gated by `factions.admin` permission, not officer rank.
- Merge: transfers money + claims + warps + members (reset to default rank, joinedAt=now); deletes sender's ranks/invites/merge-requests/relations-in and the sender faction; does NOT migrate bank history or team chests, does NOT union relations, does NOT enforce member cap; no TTL on merge requests.
- Warps: password + cost are plaintext, per-warp; teleport is immediate (no warmup/no cancel-on-move); jail blocks all warp use; cost charged before world-loaded check (no refund if world missing); `max-warps` applies only to new warps; territory guard blocks setting warps in protected regions.
- Predefined: whitelists creatable names when enabled, seeds home+claims once (`created` flag), can block disband; distinct from safezone/warzone system factions.
- GUI: menus fully config-driven; click always cancelled; language actions gated by `allow-player-override` and visible-locale set; sizes normalized to a 9-multiple in [9,54].
