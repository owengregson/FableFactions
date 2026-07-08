# FableFactions Research: Services, Config, Registry, Bootstrap, API, Util, Metrics

Reverse-engineered from `com.reference.factions` (package `the reference implementation`). This document specifies **behavior** completely enough to reimplement without the source. Scope: `service/`, `config/`, `registry/`, `bootstrap/`, `scheduler/`, `util/`, `metrics/`, root-package files, and `api/` (TeamsAPI adapters). Cross-referenced engines (`EnginePower`, `EngineEconomy`, `EngineChunkChange`, `EngineProtection`) are documented where they carry the load-bearing power/claim/economy/fly math.

Persistence is via **Jaloquent** (`com.github.ezframework.jaloquent`), an ORM whose `StorageException` is thrown by all repository calls. `repos.factions().transaction(Runnable)` runs a DB transaction. The service layer is deliberately **TeamsAPI-free**: commands/engines depend only on the internal interfaces; the `api/` package wraps the impls when TeamsAPI is present.

---

## 1. Root-package domain types

### 1.1 `Relation` (enum)
Ordinal order (low→high hostility): `MEMBER(0) < ALLY(1) < TRUCE(2) < NEUTRAL(3) < ENEMY(4)`.

| Method | Semantics |
|---|---|
| `isAtLeast(other)` | `this.ordinal() <= other.ordinal()` (lower ordinal = friendlier = "at least") |
| `isAtMost(other)` | `this.ordinal() >= other.ordinal()` |
| `isFriendly()` | `this == MEMBER || this == ALLY` |
| `isNeutralOrBetter()` | `this != ENEMY` |
| `isHostile()` | `this == ENEMY` |
| `displayName()` | "Member"/"Ally"/"Truce"/"Neutral"/"Enemy" |
| `colorTag()` | MiniMessage: MEMBER `<green>`, ALLY `<aqua>`, TRUCE `<yellow>`, NEUTRAL `<gray>`, ENEMY `<red>` |

### 1.2 `FactionFlag` (enum) — per-faction boolean flags
Each has `id` (JSON/config key), `displayName`, `description`, `defaultValue`.

| Enum | id | default | Meaning |
|---|---|---|---|
| `PVP` | `pvp` | `true` | Allow PvP in this faction's territory |
| `FRIENDLY_FIRE` | `friendly-fire` | `false` | Allow members to harm each other (checked everywhere, not just claims) |
| `EXPLOSIONS` | `explosions` | `false` | Allow explosions to destroy terrain in territory |
| `FIRE_SPREAD` | `fire-spread` | `false` | Allow fire spread in territory |
| `OPEN` | `open` | `false` | Anyone may join without an invite |

`byId(String)` → case-insensitive `Optional<FactionFlag>`. `ids()` → list of all id strings (tab-completion).

### 1.3 `FactionAuditAction` (enum) — audit log categories
`id` is lower-kebab. Values (id): `CLAIM(claim)`, `UNCLAIM(unclaim)`, `RELATION_CHANGE(relation-change)`, `MEMBER_KICK(kick)`, `MEMBER_PROMOTE(promote)`, `MEMBER_DEMOTE(demote)`, `ROLE_CREATE(role-create)`, `ROLE_RENAME(role-rename)`, `ROLE_PRIORITY_SET(role-priority-set)`, `ROLE_PREFIX_SET(role-prefix-set)`, `ROLE_DELETE(role-delete)`, `ROLE_ASSIGN(role-assign)`, `BANK_DEPOSIT(bank-deposit)`, `BANK_WITHDRAW(bank-withdraw)`, `BANK_TRANSFER(bank-transfer)`, `MERGE_REQUEST(merge-request)`, `MERGE_ACCEPT(merge-accept)`, `MOTD_SET(motd-set)`.
`fromId(id)` → case-insensitive `Optional`. `validIds()` → comma-joined list of all ids (for error messages).

### 1.4 `ReferenceFactions extends JavaPlugin`
Pure lifecycle delegation. `onEnable()`: `bootstrap = new Bootstrap(this); if (!bootstrap.start()) disablePlugin(this);`. `onDisable()`: `if (bootstrap != null) bootstrap.stop();`. Exposes `getBootstrap()`.

### Referenced model constants (from `data.model`, needed by services)
- `RankModel`: `PRIORITY_MEMBER`, `PRIORITY_OFFICER`, `PRIORITY_OWNER` (ascending ints); `RANK_MEMBER="member"`, `RANK_OFFICER="officer"`, `RANK_OWNER="owner"`; `isOwner()` = priority ≥ owner; `isOfficerOrAbove()` = priority ≥ officer.
- `FactionModel`: `SAFEZONE_ID`, `WARZONE_ID` sentinel faction-id strings; `isNormal()` (a real player faction, not a zone); `isRaidable()`/`setRaidable()`; `isShieldActive()`; `getPowerBoost()`, `getMoney()`/`getBank()` (bank money), `getRelationsJson()`, `getFlagsJson()`, home fields, MOTD/description.

---

## 2. Config system

Config objects are **typed wrappers** over Bukkit `FileConfiguration`. Instantiated in `InfrastructureBootstrapComponent`; re-created on `/fa reload`. All getters read live from the underlying config with hard-coded defaults (defaults ARE the spec).

### 2.1 `FactionsConfig` — wraps `config.yml` (+ `roles.yml`)
Two `FileConfiguration`s: `cfg` (config.yml) and `rolesCfg` (roles.yml). Single-arg ctor uses `cfg` for both. Role getters read `rolesCfg`; everything else reads `cfg`.

**Faction limits**

| Key | Default | Getter |
|---|---|---|
| `factions.max-members` | 50 | `getMaxMembers()` (0 = unlimited) |
| `factions.max-warps` | 10 | `getMaxWarps()` |
| `factions.max-team-chests` | 1 | `getMaxTeamChests()` |
| `factions.team-chest.default-name` | `"default"` | `getDefaultTeamChestName()` |
| `factions.max-allies` | 5 | `getMaxAllies()` |
| `factions.max-truces` | 5 | `getMaxTruces()` |
| `factions.invites.ttl-hours` | 72 | `getInviteTtlHours()` |

**Language**

| Key | Default | Getter |
|---|---|---|
| `factions.language.default` | `"en"` | `getDefaultLanguage()` |
| `factions.language.allow-player-override` | true | `isLanguagePlayerOverrideEnabled()` |
| `factions.language.command-opens-gui` | true | `isLanguageCommandOpensGui()` |
| `factions.language.command-open-gui-after-set` | true | `isLanguageCommandOpensGuiAfterSet()` |
| `factions.language.visible-locales` | (empty list) | `getLanguageVisibleLocales()` |

**Power** — see §5 for math. Note the derived defaults.

| Key | Default | Getter |
|---|---|---|
| `factions.power.per-player-max` | 10.0 | `getPowerPerPlayerMax()` = `getMaxPower()` |
| `factions.power.regen-per-second` | 0.1 | `getPowerRegenPerSecond()` |
| `factions.power.regen-online` | `regenPerSecond*60` (=6.0) | `getPowerRegenOnline()` |
| `factions.power.regen-offline` | `regenPerSecond*30` (=3.0) | `getPowerRegenOffline()` |
| `factions.power.loss-on-death` | 4.0 | `getPowerLossOnDeath()` |
| `factions.power.grace-period-seconds` | 3600 | `getPowerGracePeriodSeconds()` |
| `factions.power.tick-interval-seconds` | 60 | `getPowerTickIntervalSeconds()` |
| `factions.power.gain-on-kill.enabled` | true | `isPowerGainOnKillEnabled()` |
| `factions.power.gain-on-kill.amount` | 2.0 | `getPowerGainOnKill()` |
| `factions.power.gain-on-kill.scale.enabled` | false | `isKillScaleEnabled()` (F3) |
| `factions.power.gain-on-kill.scale.min-factor` | 0.25 | `getKillScaleMinFactor()` |
| `factions.power.gain-on-kill.scale.max-factor` | 2.0 | `getKillScaleMaxFactor()` |
| `factions.power.inactive-exclusion.enabled` | false | `isPowerInactiveExclusionEnabled()` (F1) |
| `factions.power.inactive-exclusion.days` | 7 | `getPowerInactiveDays()` |
| `factions.power.death-streak.enabled` | false | `isDeathStreakEnabled()` (F2) |
| `factions.power.death-streak.window-seconds` | 600 | `getDeathStreakWindowSeconds()` |
| `factions.power.death-streak.multiplier` | 1.5 | `getDeathStreakMultiplier()` |
| `factions.power.buy.enabled` | false | `isPowerBuyEnabled()` |
| `factions.power.buy.cost-per-point` | 100.0 | `getPowerBuyCostPerPoint()` |
| `factions.power.buy.max-per-purchase` | 5.0 | `getPowerBuyMaxPerPurchase()` |
| `factions.power.sources.regen-online.enabled` | true | `isPowerSourceRegenOnlineEnabled()` |
| `factions.power.sources.regen-offline.enabled` | true | `isPowerSourceRegenOfflineEnabled()` |
| `factions.power.sources.death-loss.enabled` | true | `isPowerSourceDeathLossEnabled()` |
| `factions.power.sources.kill-gain.enabled` | true | `isPowerSourceKillGainEnabled()` |
| `factions.power.sources.buy.enabled` | true | `isPowerSourceBuyEnabled()` |
| `factions.power.sources.regen-online.amount` | `getPowerRegenOnline()` | `getPowerSourceRegenOnlineAmount()` |
| `factions.power.sources.regen-offline.amount` | `getPowerRegenOffline()` | `getPowerSourceRegenOfflineAmount()` |
| `factions.power.sources.death-loss.amount` | `getPowerLossOnDeath()` | `getPowerSourceDeathLossAmount()` |
| `factions.power.sources.kill-gain.amount` | `getPowerGainOnKill()` | `getPowerSourceKillGainAmount()` |
| `factions.power.constraints.min-power` | 0.0 | `getPowerMin()` |
| `factions.power.constraints.max-power` | `getMaxPower()` (=10.0) | `getPowerMax()` |
| `factions.power.constraints.max-change-per-event` | 0.0 (0 = no clamp) | `getPowerMaxChangePerEvent()` |
| `factions.power.multipliers.worlds.<world>` | 1.0 | `getPowerWorldMultiplier(world)` — returns 1.0 for null/blank world |
| `factions.power.multipliers.zones.<zoneKey>` | 1.0 | `getPowerZoneMultiplier(zoneKey)` — zoneKey is lowercased `ZoneContext` name |
| `factions.power.freeze.blocks-automatic` | true | `isPowerFreezeBlocksAutomatic()` |
| `factions.power.freeze.blocks-regen` | true | `isPowerFreezeBlocksRegen()` |
| `factions.power.freeze.allow-admin-bypass` | true | `isPowerFreezeAllowAdminBypass()` |
| `factions.power.notifications.actor` | true | `isPowerNotifyActor()` |
| `factions.power.notifications.faction` | false | `isPowerNotifyFaction()` |
| `factions.power.notifications.staff` | false | `isPowerNotifyStaff()` |

**Land**

| Key | Default | Getter |
|---|---|---|
| `factions.land.buffer-zone` | 0 | `getLandBufferZone()` — **DEFINED BUT UNUSED** (no buffer/adjacency spacing is enforced anywhere) |
| `factions.land.max-per-command` | 200 | `getLandMaxPerCommand()` — cap on chunks processed per `/f claim square/circle/fill/nearby` and unclaim/admin variants |
| `factions.land.per-power` | 1.0 | `getLandPerPower()` — chunks granted per unit power |
| `factions.land.max` | 500 | `getMaxLand()` — hard ceiling on chunks a faction may hold |
| `factions.map.once-radius` | 3 | `getMapOnceRadius()` |
| `factions.list.page-size` | 8 | `getListPageSize()` |
| `factions.top.page-size` | 8 | `getTopPageSize()` |
| `factions.info.relations.show-allies` | true | `isInfoShowAllies()` |
| `factions.info.relations.show-truces` | false | `isInfoShowTruces()` |
| `factions.info.relations.show-neutrals` | false | `isInfoShowNeutrals()` |
| `factions.info.relations.show-enemies` | false | `isInfoShowEnemies()` |
| `factions.bank.history.page-size` | 8 | `getBankHistoryPageSize()` |
| `factions.warp.list.page-size` | 8 | `getWarpListPageSize()` |
| `factions.audit.page-size` | 10 | `getAuditPageSize()` |

**Roles** (from `rolesCfg`)

| Key | Default | Getter |
|---|---|---|
| `roles.custom.enabled` | true | `isCustomRolesEnabled()` |
| `roles.overrides.enabled` | false | `isRoleFactionOverridesEnabled()` |
| `roles.custom.min-priority` | 11 (`RankDefaults.MIN_CUSTOM_PRIORITY`) | `getMinCustomRolePriority()` |
| `roles.custom.max-priority` | 99 (`RankDefaults.MAX_CUSTOM_PRIORITY`) | `getMaxCustomRolePriority()` |
| `roles.custom.max-per-faction` | 0 (0 = unlimited) | `getMaxCustomRolesPerFaction()` |
| `roles.prefix.enabled` | true | `isRolePrefixesEnabled()` |
| `roles.prefix.max-length` | 32 | `getMaxRolePrefixLength()` |
| `roles.defaults.owner.prefix` | `""` | `getDefaultOwnerRolePrefix()` |
| `roles.defaults.officer.prefix` | `""` | `getDefaultOfficerRolePrefix()` |
| `roles.defaults.member.prefix` | `""` | `getDefaultMemberRolePrefix()` |

**Economy**

| Key | Default | Getter |
|---|---|---|
| `factions.economy.enabled` | true | `isEconomyEnabled()` = `isBankEnabled()` |
| `factions.economy.cost-create` | 50.0 | `getCostCreate()` — **DEFINED BUT UNUSED** (creation is not charged) |
| `factions.economy.cost-claim` | 100.0 | `getCostClaim()` — **DEFINED BUT UNUSED** (claiming is not charged) |
| `factions.economy.tax.enabled` | false | `isTaxEnabled()` |
| `factions.economy.tax.rate` | 0.05 | `getTaxRate()` |
| `factions.economy.tax.interval-hours` | 24 | `getTaxIntervalHours()` |
| `factions.economy.tax.min-bank-balance` | 0.0 | `getTaxMinBankBalance()` |
| `factions.economy.tax.min-charge-amount` | 0.01 | `getTaxMinChargeAmount()` |

**Fly**

| Key | Default | Getter |
|---|---|---|
| `factions.fly.enabled` | true | `isFlyEnabled()` |
| `factions.fly.disable-on-threat` | true | `isFlyDisableOnThreat()` — **DEFINED BUT UNUSED** (no threat scanner exists) |
| `factions.fly.require-own-territory` | true | `isFlyRequireOwnTerritory()` — used by `/f fly` |

**Chat**

| Key | Default | Getter |
|---|---|---|
| `factions.chat.show-tag` | false | `isChatTagEnabled()` = `isChatFormatEnabled()` |
| `factions.chat.tag-format` | `"<gray>[<gold>{faction_name}</gold>]</gray> "` | `getChatTagFormat()` |

**Integrations**

| Key | Default | Getter |
|---|---|---|
| `integrations.vault` | true | `isVaultEnabled()` |
| `integrations.worldguard` | true | `isWorldGuardEnabled()` |
| `integrations.worldguard-sync-regions` | false | `isWorldGuardSyncRegions()` |
| `integrations.dynmap` | true | `isDynmapEnabled()` |
| `integrations.placeholderapi` | true | `isPlaceholderApiEnabled()` |
| `integrations.essentialsx.enabled` | false | `isEssentialsXEnabled()` |
| `integrations.lwc.enabled` | true | `isLwcEnabled()` |
| `integrations.lwc.require-build-rights-to-create` | true | `isLwcRequireBuildRightsToCreate()` |
| `integrations.lwc.remove-if-no-build-rights` | true | `isLwcRemoveIfNoBuildRights()` |
| `integrations.lwc.remove-on-claim-change` | true | `isLwcRemoveOnClaimChange()` |
| `integrations.discordsrv.enabled` | false | `isDiscordSrvEnabled()` |
| `integrations.discordsrv.channel-id` | `""` | `getDiscordSrvChannelId()` |
| `integrations.discordsrv.events.faction-created.enabled` | true | `isDiscordSrvFactionCreatedEnabled()` |
| `integrations.discordsrv.events.faction-created.message` | `"**{faction}** was founded!"` | `getDiscordSrvFactionCreatedMessage()` |
| `integrations.discordsrv.events.faction-disbanded.enabled` | true | (message default `"**{faction}** was disbanded."`) |
| `integrations.discordsrv.events.relation-ally.*` | true / `":handshake: **{source}** and **{target}** are now allies!"` | |
| `integrations.discordsrv.events.relation-truce.*` | true / `":white_flag: **{source}** and **{target}** agreed to a truce."` | |
| `integrations.discordsrv.events.relation-enemy.*` | true / `":crossed_swords: **{source}** declared war on **{target}**!"` | |

**Metrics / Zones / Overclaim / F4-F6 / Merge / Updates / Flags**

| Key | Default | Getter |
|---|---|---|
| `factions.metrics.bstats.enabled` | true | `isBstatsEnabled()` |
| `factions.metrics.bstats.plugin-id` | 31240 | `getBstatsPluginId()` |
| `factions.zones.safe-zone.enabled` | true | `isSafeZoneEnabled()` |
| `factions.zones.war-zone.enabled` | true | `isWarZoneEnabled()` |
| `factions.overclaiming.enabled` | false | `isOverclaimingEnabled()` |
| `factions.overclaiming.require-enemy-relation` | true | `isOverclaimRequireEnemyRelation()` |
| `factions.overclaiming.offline-protection.enabled` | false | `isOfflineProtectionEnabled()` (F5) |
| `factions.raidable.broadcast.enabled` | true | `isRaidableBroadcastEnabled()` (F4) |
| `factions.raidable.broadcast.server-wide` | false | `isRaidableBroadcastServerWide()` |
| `factions.war.shield.enabled` | false | `isWarShieldEnabled()` (F6) |
| `factions.war.shield.max-duration-hours` | 8 | `getWarShieldMaxDurationHours()` |
| `factions.merge.enabled` | false | `isMergeEnabled()` |
| `factions.updates.enabled` | true | `isUpdateCheckEnabled()` |
| `factions.updates.notify-ops-on-join` | true | `isUpdateNotifyOpsOnJoin()` |
| `factions.updates.modrinth-slug` | `"the reference implementation"` | `getUpdateModrinthSlug()` |
| `factions.updates.github-owner` | `"the reference project"` | `getUpdateGithubOwner()` |
| `factions.updates.github-repo` | `"the reference implementation"` | `getUpdateGithubRepo()` |
| `factions.flags.<id>.default` | flag's enum default | `getFlagDefault(FactionFlag)` |
| `factions.flags.<id>.player-editable` | true | `isFlagPlayerEditable(FactionFlag)` |

### 2.2 `DatabaseConfig` — wraps `database.yml`
`getType()` → `"h2"` (default) or `"mysql"`. H2: `h2.file` = `"data/factions"`. MySQL: `mysql.host`=`localhost`, `mysql.port`=3306, `mysql.database`=`factions`, `mysql.username`=`root`, `mysql.password`=`""`, `mysql.pool-size`=10. `debug.jaloquent-logging`=false (`isJaloquentLoggingEnabled()`).

### 2.3 `NotificationsConfig` — wraps `notifications.yml`
`inbox.enabled`=true, `inbox.max-per-login`=20, `member.notify-player-joined`=true, `economy.tax.notify-members`=true, `ezcountdown.enabled`=true, `ezcountdown.announcement-duration-seconds`=8 (long), `ezcountdown.display-types`=`["ACTION_BAR"]` if the list is empty.

### 2.4 `GuiConfig` — wraps `gui.yml`
`raw()` exposes the `FileConfiguration`. `gui.enabled`=true, `gui.default-menu`=`"main"`, `gui.language-menu`=`"language"`.

### 2.5 `MessagesConfig` — locale-aware message bundles
Holds `Map<normalizedLocale, FileConfiguration>` plus a `defaultLocale`, and (settable) `Repositories`. Constant `ENGLISH_LOCALE="en"`.

- **`normalizeLocale(String)`** (static): null/blank → `"en"`; trim; replace `_`→`-`; special-case `"pt-br"`→`"pt-BR"`; otherwise lowercase (ROOT). The bundle map is keyed by normalized locale in ctor.
- `getAvailableLocales()`, `getDefaultLocale()`, `isSupportedLocale(locale)` (checks normalized key present).
- **`resolveSenderLocale(sender)`**: if sender is a `Player` and repos set, look up the player's stored `getLocale()`; if it's a supported locale, return it normalized; otherwise (or on any exception) return `defaultLocale`.
- **`get(path, fallback[, preferredLocale])`**: read from preferred → default → English → `fallback`. String only, no placeholder substitution.
- `getForSender(sender, path, fallback)` = `get(path, fallback, resolveSenderLocale(sender))`.
- `getStringList(path, fallback, preferredLocale)`: same waterfall but only accepts a bundle where `isList(path)` and the list is non-empty; else falls through. `getStringListForSender(...)` variant.
- `read(locale, path)`: returns `cfg.getString(path)` or null if bundle absent.

---

## 3. Registry pattern

### 3.1 `Registry<K,V>` (generic)
Backed by a `LinkedHashMap<K,V>` (insertion-ordered). Methods: `register(k,v)` (overwrites), `get(k)`→`Optional<V>`, `get(k, Class<T>)`→typed `Optional<T>` (returns empty if absent or wrong type via `isInstance`), `contains(k)`, `unregister(k)`, `clear()`, `size()`, `protected store()` (raw map for subclasses).

### 3.2 `CommandRegistry extends Registry<String, FactionCommand>`
- `register(FactionCommand)`: registers under `cmd.getName().toLowerCase()` **and** every `cmd.getAliases()` lowercased — same command object under multiple keys.
- `getAll()`: `store().values().stream().distinct().toList()` — deduped, registration order.
- `completionNames(partial, sender)`: keys `startsWith(partial)` AND (command permission null OR `sender.hasPermission(perm)`), mapped to the key strings. Alias-aware tab completion at arg 0.

### 3.3 `ServiceRegistry` — POJO holder of internal service impls
Setter/getter pairs for: `FactionService`, `InviteService`, `WarpService`, `FlagService`, `AuditService`, `PowerService`, `MergeService`, `TeamChestService`. TeamsAPI adapters are **not** stored here (tracked in `BootstrapContext`).

### 3.4 `EngineRegistry` — engines needed across subsystems
Holds: `EngineChunkChange`, `EngineEconomy`, `AutoTerritoryModeCache`, `FactionsGuiManager`, `EngineTeamChests`. Engines that are pure Listeners (`EngineProtection`, `EnginePlayerMove`, `EngineChat`, `EnginePower`) are NOT stored — they self-register and need no reference.

### 3.5 `InfraRegistry` — core infra holder
Holds config objects (`FactionsConfig`, `GuiConfig`, `MessagesConfig`, `DatabaseConfig`, `NotificationsConfig`), `DatabaseManager`, `Repositories`, `VaultEconomy`, `EzCountdownNotifier`, `EssentialsInterop` (default `NoopEssentialsInterop`), `DiscordSrvNotifier`, `TerritoryGuard` (default `NoopTerritoryGuard`), `LwcInterop` (default `NoopLwcInterop`), `PredefinedConfigManager`, `TaskScheduler`. Setter/getter POJO.

---

## 4. Bootstrap / wiring

`Bootstrap` owns startup/shutdown. It creates three registries (`InfraRegistry`, `ServiceRegistry`, `EngineRegistry`), one `BootstrapContext` wrapping plugin + registries, and an **ordered immutable list of components**:

1. `InfrastructureBootstrapComponent` (name `"infrastructure"`)
2. `ServicesBootstrapComponent` (`"services"`)
3. `EnginesBootstrapComponent` (`"engines"`)
4. `CommandsBootstrapComponent` (`"commands"`)
5. `OptionalHooksBootstrapComponent` (`"optional-hooks"`)

### 4.1 `BootstrapComponent` / `AbstractBootstrapComponent`
Interface: `String name()`, `boolean start(ctx)`, `default void stop(ctx)` (no-op). Abstract base adds `logger(ctx)` = `ctx.plugin().getLogger()`.

### 4.2 `BootstrapContext`
Wraps `Plugin plugin` (mockable), `plugin()` and `javaPlugin()` (cast to `JavaPlugin` for `saveDefaultConfig`/`saveResource`/`getCommand`). Accessors `infra()`, `services()`, `engines()`. Mutable feature flags set during startup: `teamsRegistrar`, `teamsApiEnabled`, `vaultEnabled`, `placeholderApiEnabled`, `dynmapEnabled`, `lwcEnabled`, `ezCountdownEnabled`, `discordSrvEnabled`.

### 4.3 `Bootstrap.start()` sequence
Logs a banner, then iterates components in order calling `component.start(context)`. On any `false` return: log `"Bootstrap phase failed: <name>"`, call `stopStartedComponents()`, return `false` (caller disables plugin). On success each is added to `startedComponents`. Ends with `logStartupSummary()`.

`stop()` → `stopStartedComponents()` (iterates started list **in reverse**, calls `stop(ctx)`, catches+warns on exceptions, clears list) + log line.

**`reloadServices()`** (used by `/fa reload`): finds the `"services"` component. Walks `startedComponents` from the end, stopping each component; components started **after** services are collected (prepended) into `toRestart`; when it reaches `"services"` it stops it and breaks. Then restarts `"services"` (fail → severe log + `false`), then restarts each collected component in original order. This lets a reload re-instantiate services/engines/commands/hooks with a freshly re-read config.

### 4.4 InfrastructureBootstrapComponent.start
Order:
1. **`initScheduler`**: pick `FoliaTaskScheduler` if `PlatformDetector.isFolia()` else `BukkitTaskScheduler`; store in infra; log `"Platform scheduler: Folia|Bukkit"`.
2. **`initConfig`**:
   - `saveDefaultConfig()` (config.yml).
   - Save `roles.yml` if missing; build `FactionsConfig(plugin.getConfig(), YamlConfiguration.loadConfiguration(rolesFile))`.
   - Save `gui.yml` if missing → `GuiConfig`.
   - Save `database.yml` if missing.
   - Load messages (see below) → `MessagesConfig`; call `MsgUtil.setMessagesConfig(...)`.
   - `DatabaseConfig` from `database.yml`.
   - `PredefinedConfigManager` created against data folder, `initialize()`, set as static singleton, stored.
   - Save `notifications.yml` if missing → `NotificationsConfig`.
   - **Messages loading**: ensure `messages/` dir exists; shipped locales `{"en","es","de","fr","pt-BR","zh","ru","ja"}` each saved to `messages/messages_<locale>.yml` if missing; then load every `messages/messages_*.yml` present, keying by the locale extracted from the filename (`messages_` prefix, `.yml` suffix) normalized. Default locale = `config.getDefaultLanguage()`.
3. **`initDatabase`**: `DatabaseManager.initialize(dbCfg, dataFolder, logger)`; if `!isInitialized()` → severe log, return `false` (fatal). Else store manager + `Repositories(db.getStore())`; wire repos into `MessagesConfig`.
4. **`initVault`**: `VaultEconomy.setup()`; log found/not-found; set `vaultEnabled` flag; store vault.
5. **`initEssentialsInterop`** / **`initTerritoryGuard`** / **`initLwcInterop`** via factory classes. LWC: `register(plugin)` and set `lwcEnabled = !(instanceof NoopLwcInterop)`.

`stop`: `lwcInterop.unregister()`; if DB present `db.close()`.

### 4.5 ServicesBootstrapComponent.start
Constructs internal service impls with repos+config+logger:
- `FactionServiceImpl(plugin, repos, cfg, notifCfg, logger)`
- `InviteServiceImpl(factionImpl, repos, cfg, logger)`
- `WarpServiceImpl(repos, cfg, logger)`
- `TeamChestServiceImpl(repos, cfg, logger)`
- `PowerServiceImpl(repos, cfg)`
- `FlagServiceImpl(repos, cfg, logger)`
- `AuditServiceImpl(repos, logger)` → then `factionImpl.setAuditService(auditImpl)`
- `MergeServiceImpl(factionImpl, repos, logger)`

Registers each into `ServiceRegistry`.

**TeamsAPI optional wiring**: `isTeamsApiAvailable(ctx)` = `Class.forName("com.skyblockexp.teamsapi.api.TeamsAPI")` succeeds AND plugin `"TeamsAPI"` present. If absent → log "running standalone", `teamsApiEnabled=false`, return true. If present → `Class.forName("com.reference.factions.api.TeamsApiRegistrarImpl")`, instantiate, call `register(plugin, factionImpl, inviteImpl, warpImpl, teamChestImpl)`. On success store registrar + `teamsApiEnabled=true`; on `false` or `ReflectiveOperationException|LinkageError` → warn + disabled. Reflection isolation ensures the class loads without TeamsAPI on classpath. `stop`: `registrar.unregister()`.

### 4.6 EnginesBootstrapComponent.start
Creates and registers (Listeners + timers):
- `EngineChunkChange(repos, cfg, logger)` → stored.
- `EngineEconomy(plugin, repos, cfg, notifCfg, vault, scheduler, logger)` → stored; `economy.startTaxScheduler(scheduler)`.
- `AutoTerritoryModeCache(repos, logger)` → stored.
- `EngineProtection(repos, cfg, flagService, territoryGuard, logger).register(plugin)`.
- `EngineAuditLog(auditService).register(plugin)`.
- If `isWorldGuardSyncRegions()` AND WorldGuard present: `WorldGuardRegionSync(repos, plugin, logger).register()` + `.syncAll()`.
- `EnginePlayerMove(repos, cfg, logger).register(plugin)`.
- `EngineChat(repos, cfg, logger).register(plugin)`.
- `EnginePower(repos, cfg, logger, scheduler, powerService).start(plugin)` (field kept for `stop`).
- `EngineNotifications(inviteSvc, factionSvc, repos, logger, notifCfg).register(plugin, scheduler)`.
- `EngineAutoTerritory(chunkChange, territoryGuard, autoTerritoryModeCache).register(plugin)`.
- `EngineTeamChests(teamChestService, logger).register(plugin)` → stored.
- If `isUpdateCheckEnabled()`: build `ChainedUpdateChecker` (Modrinth primary with loaders `paper/folia/spigot`, GitHub backup) using plugin version; `UpdateNotificationManager.checkAsync()`; `EngineUpdateNotifier(cfg, updateManager).register(plugin)`.
- `FactionsGuiManager(plugin, guiConfig, repos, factionSvc, cfg, logger).register()` → stored.

`stop`: `powerEngine.stop()` (cancel tick timer) and `economy.stopTaxScheduler()`.

### 4.7 CommandsBootstrapComponent.start
Builds a `CommandRegistry` for player commands and registers ~35 subcommands (each constructed with the services it needs — see `registry.register(new Cmd…)` list). Notable wirings:
- `CmdClaim(chunkChange, territoryGuard, autoModeCache)`, `CmdUnclaim(chunkChange, factionSvc, autoModeCache)`.
- `CmdHome(factionSvc, essentialsInterop)`, `CmdSetHome(factionSvc, territoryGuard)`, `CmdUnsetHome(factionSvc)`, `CmdFly(factionSvc)`.
- `CmdRelation(factionSvc, ezCountdownNotifier, notifCfg, discordSrvNotifier, cfg)`.
- `CmdMerge(factionSvc, mergeSvc)`, `CmdWarp(factionSvc, warpSvc, territoryGuard, essentialsInterop, vaultEconomy)`.
- `CmdChest(factionSvc, teamChestService, teamChests, cfg)`, `CmdBank(factionSvc, economy)`.
- `CmdPower(vaultEconomy, cfg, repos, powerService)`, `CmdPowerHistory()`.
- `CmdHelp(commandRegistry)` (needs the registry).

Player command roots registered on aliases `{"f","faction","factions"}` via `FactionCommandExecutor` + `FactionTabCompleter` (both take a `TeamsCommandBridge` loaded reflectively when `teamsApiEnabled`, else null).

A second `CommandRegistry` for admin: `CmdInfo`, `CmdAdminBypass`, `CmdAdminClaim`, `CmdAdminUnclaim`, `CmdAdminDisband`, `CmdAdminReload`, `CmdAdminSafezone`, `CmdAdminWarzone`, `CmdAdminShield`, `CmdAdminFlag`, `CmdAdminAudit`, `CmdAdminPower`, `CmdAdminHelp`. Registered on aliases `{"fa","factionadmin"}` via `AdminCommandExecutor` + `AdminTabCompleter`.

`loadTeamsCommandBridge`: returns null if `!teamsApiEnabled`, else `Class.forName("com.reference.factions.command.TeamsCommandBridgeImpl")` instantiated (warn+null on failure).

### 4.8 OptionalHooksBootstrapComponent.start
Never fatal. Order: `initBstats`, `initPlaceholderApi`, `initDynmap`, `initEzCountdown`, `initDiscordSrv`.
- **bStats**: skip if `!isBstatsEnabled()` (log); skip if `pluginId <= 0` (warn); else create `BStatsMetricsManager(plugin, repos, dbConfig, taskScheduler, logger)` + `start(pluginId)` (try/catch warn). Field kept for `stop()` → `metricsManager.stop()`.
- **PlaceholderAPI**: if plugin present, `new FactionsPlaceholders(repos, logger).register()`, set flag true; else flag false.
- **dynmap**: if plugin present + enabled, `DynmapLayer(repos, taskScheduler, logger).start(plugin)`; flag = start result; try/catch warn.
- **EzCountdown**: `EzCountdownNotifier(logger)`; if `!notificationsConfig.isEzCountdownEnabled()` OR `!notifier.setup()` → store notifier, flag false; else flag true.
- **DiscordSRV**: skip if `!isDiscordSrvEnabled()`. Else `DiscordSrvNotifier(logger, channelId)`; if `!setup()` → flag false; else store + register `DiscordSrvFactionListener(notifier, cfg)`, flag true.

### 4.9 `logStartupSummary`
Logs command roots `/f` and `/fa`, member/warp/ally/truce limits, and integration states (`TeamsAPI/Vault/PlaceholderAPI/Dynmap/LWC` enabled|disabled).

### 4.10 Scheduler abstraction (Folia/Paper/Spigot)

**`PlatformDetector.isFolia()`**: static-init `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` success → Folia, else not. There is no separate Paper-vs-Spigot detection at the scheduler level; Paper and Spigot both use `BukkitTaskScheduler`. (Paper-vs-Spigot IS distinguished by `MsgUtil.ADVENTURE` — see §8.)

**`TaskScheduler`** interface: `runAsync`, `runSync`, `runSyncForPlayer(player,task)`, `runSyncLater(task, delayTicks)`, `scheduleAsyncTimer(task, delayTicks, periodTicks)→CancelableTask`. `CancelableTask` = single `cancel()`.

- **`BukkitTaskScheduler`**: delegates to `Bukkit.getScheduler()`. `runSyncForPlayer` → `runTask` (global main thread). `scheduleAsyncTimer` → `runTaskTimerAsynchronously`, wrap `BukkitTask::cancel`.
- **`FoliaTaskScheduler`**: `runAsync`→`getAsyncScheduler().runNow`; `runSync`→`getGlobalRegionScheduler().run`; `runSyncForPlayer`→`player.getScheduler().run(...)`, and if that returns null (player left) fall back to global region; `runSyncLater`→`getGlobalRegionScheduler().runDelayed`. `scheduleAsyncTimer` converts ticks→ms (`ticks*50`) and uses `getAsyncScheduler().runAtFixedRate(..., MILLISECONDS)`; wrap `ScheduledTask::cancel`. Class only loaded on Folia (lazy loading keeps Folia API off Paper/Spigot classpath).

---

## 5. PowerService (the power math)

### 5.1 Interface `PowerService`
Enums:
- `Source`: `REGEN_ONLINE, REGEN_OFFLINE, DEATH, KILL, BUY, ADMIN_SET, ADMIN_ADD, ADMIN_REMOVE, ADMIN_RESET`.
- `ZoneContext`: `SAFEZONE, WARZONE, OWN_CLAIMED, ENEMY_CLAIMED, WILDERNESS`.

`record Request(String playerId, Source source, double baseDelta, String actorName, String reason, String world, ZoneContext zone, boolean bypassFreeze)`.
`record Result(boolean changed, boolean blockedByFreeze, double before, double requestedDelta, double effectiveDelta, double after, String reasonCode)`.

Methods: `Optional<PlayerModel> findPlayerByNameOrUuid(String)`, `Result apply(Request)`, `double getFactionPower(String factionId)`.

### 5.2 `findPlayerByNameOrUuid`
Try to parse input as UUID → `repos.players().find(uuid.toString())`. On `IllegalArgumentException` treat as name: `Bukkit.getOfflinePlayer(name)`; if `!hasPlayedBefore() && !isOnline()` → empty; else `repos.players().find(op.getUniqueId().toString())`.

### 5.3 `apply(Request)` — the exact algorithm
1. Load player; if absent → `Result(false,false,0,baseDelta,0,0,"PLAYER_NOT_FOUND")`.
2. `before = model.getPower()`; `requestedDelta = baseDelta`.
3. **Freeze gate**: if `!bypassFreeze && model.isPowerFrozen() && sourceAffectedByFreeze(source)` → `notifyBlockedByFreeze(...)`, return `Result(false, true, before, requestedDelta, 0, before, "FROZEN")`.
   - `sourceAffectedByFreeze`: `REGEN_ONLINE/REGEN_OFFLINE → isPowerFreezeBlocksRegen()`; `DEATH/KILL/BUY → isPowerFreezeBlocksAutomatic()`; all `ADMIN_* → false`.
4. `sourceAmount = sourceAmount(source, baseDelta)` (below). If `!sourceEnabled(source)` → `Result(false,false,before,requestedDelta,0,before,"SOURCE_DISABLED")`.
5. `delta = sourceAmount`. If `source == DEATH || KILL` → `delta = applyMultipliers(delta, world, zone)`.
6. `delta = applyEventClamp(delta)`.
7. `after = clamp(before + delta, minPower, maxPower)` where `minPower=getPowerMin()`, `maxPower=getPowerMax()`. `effectiveDelta = after - before`.
8. If `abs(effectiveDelta) < 0.00001` → `Result(false,false,before,requestedDelta,0,before,"NO_CHANGE")`.
9. Else `model.setPower(after)`; save; `repos.powerHistory().record(id, effectiveDelta, reasonCode, after)`; `notifyPowerChange(...)`; return `Result(true,false,before,requestedDelta,effectiveDelta,after,reasonCode)`.

**`sourceEnabled(source)`**:
- `REGEN_ONLINE → isPowerSourceRegenOnlineEnabled()`
- `REGEN_OFFLINE → isPowerSourceRegenOfflineEnabled()`
- `DEATH → isPowerSourceDeathLossEnabled()`
- `KILL → isPowerSourceKillGainEnabled()`
- `BUY → isPowerBuyEnabled() && isPowerSourceBuyEnabled()`
- all `ADMIN_* → true`

**`sourceAmount(source, requested)`** — the delta magnitude BEFORE multipliers/clamp:
- `REGEN_ONLINE → getPowerSourceRegenOnlineAmount()` (default 6.0)
- `REGEN_OFFLINE → getPowerSourceRegenOfflineAmount()` (default 3.0)
- `DEATH → -abs(getPowerSourceDeathLossAmount())` (always negative)
- `KILL → +abs(getPowerSourceKillGainAmount())` (always positive)
- `BUY → requested` (caller passes exact points)
- all `ADMIN_* → requested`

Note: regen sources IGNORE the caller's `baseDelta` and always apply the configured full amount; the tick engine passes `baseDelta=0.0`.

**`applyMultipliers(delta, world, zone)`** (DEATH/KILL only): `delta * getPowerWorldMultiplier(world) * getPowerZoneMultiplier(zone.name().toLowerCase(ROOT))`.

**`applyEventClamp(delta)`**: `maxAbs = getPowerMaxChangePerEvent()`; if `maxAbs <= 0` return delta unchanged; else clamp to `[-maxAbs, +maxAbs]`.

**`reasonCode(request)`**: if `reason` non-null and non-blank → `reason.trim()`; else `source.name()`.

### 5.4 `getFactionPower(factionId)`
`total = faction.getPowerBoost()` (or 0 if faction absent). For each `PlayerModel` in faction: skip if `isExcludedByInactivity(pm)`, else add `pm.getPower()`.
**`isExcludedByInactivity`**: false if `!isPowerInactiveExclusionEnabled()`; else `inactiveMs = getPowerInactiveDays() * 24 * 3600 * 1000`; return `last > 0 && (now - last) > inactiveMs` where `last = pm.getLastActivity()`.

### 5.5 Static `zoneFromClaim(Optional<BoardEntry> claim, String factionId)`
- claim empty → `WILDERNESS`
- claim faction == `SAFEZONE_ID` → `SAFEZONE`
- == `WARZONE_ID` → `WARZONE`
- == `factionId` (player's) → `OWN_CLAIMED`
- else → `ENEMY_CLAIMED`

### 5.6 Notifications (side-effects of `apply`)
- **`notifyBlockedByFreeze`**: to the online actor if `isPowerNotifyActor()`, key `power.blocked-frozen` with `{source}`. If `isPowerNotifyStaff()`, broadcast `power.staff-blocked-frozen` with `{player}/{source}/{actor}` (actor "system" if null).
- **`notifyPowerChange`**: formats delta/before/after as `%.2f` (ROOT). If `isPowerNotifyActor()` and player online → key `power.change-actor` (`{before}/{after}/{delta}`). If `isPowerNotifyFaction()` and player in faction → send `power.change-faction` to every online member. If `isPowerNotifyStaff()` → broadcast `power.change-staff`.
- **`broadcastStaff(msg)`**: send to every online player with `factions.admin` permission, plus console with `MsgUtil.stripTags(msg)`.

### 5.7 Power tick cadence (`EnginePower`)
Runs as an **async repeating timer**: `intervalSeconds = max(1, getPowerTickIntervalSeconds())` (default 60), `intervalTicks = intervalSeconds*20`; `scheduleAsyncTimer(this, intervalTicks, intervalTicks)`. Also registers Bukkit listeners.
- **`run()`**: iterate all players; `tickPower(pm)`; if `isRaidableBroadcastEnabled()` → `checkRaidableTransitions()`.
- **`tickPower(pm)`**: `source = online ? REGEN_ONLINE : REGEN_OFFLINE`; only if `current < max` call `powerService.apply(Request(id, source, 0.0, "system", source.name(), null, null, false))`.
- **`onQuit`** (event): schedules `applyPowerOnQuit` async — a no-op (loss handled on death).
- **`onDeath`** (`PlayerDeathEvent`, MONITOR, ignoreCancelled): captures dead/killer ids, world, chunk X/Z; runs `applyDeathPower` async.
- **`applyDeathPower`**:
  - **Grace window**: if `now - startedAt < getPowerGracePeriodSeconds()*1000` → return (no loss during grace after server start).
  - Resolve claim + dead player faction; `zone = zoneFromClaim(...)`. If `isSafeZoneEnabled()` and chunk is a SAFEZONE claim → return (no power change in safezone).
  - **Death loss** (F2 death streak): base `loss = getPowerLossOnDeath()`; if `isDeathStreakEnabled()`: `windowMs = window*1000`; if `lastDeath > 0 && now-lastDeath <= windowMs` then `streak = deathStreak + 1` else 0. If `streak > 0`: `loss = loss * pow(getDeathStreakMultiplier(), streak)`. Persist `lastDeathAt=now`, `deathStreak=streak` on the model. Apply `Request(deadId, DEATH, -loss, "system","DEATH", world, zone, false)`; `actualLoss = abs(result.effectiveDelta())`. Sync-message the dead player: streak variant `power.death-streak-penalty` (`{streak}` = `streak+1`, `{amount}` = `%.1f`) if streak>0, else `power.lost-on-death`.
  - **Kill gain** (F3 scaling): if `killerId != null && isPowerGainOnKillEnabled()`: base `gain = getPowerGainOnKill()`; if `isKillScaleEnabled()`: `killerPower = killerModel.getPower()`; if `>0`: `ratio = victimPowerBefore / killerPower`, `factor = clamp(ratio, minFactor, maxFactor)`, `gain *= factor`; else `gain *= minFactor`. Apply `Request(killerId, KILL, gain, "system","KILL", world, zone, false)`; sync-message killer `power.kill-gained` (`{amount}`=`%.1f` of effectiveDelta).
- **`checkRaidableTransitions`** (F4): for each `isNormal()` faction: `totalPower = getFactionPower(id)`; `maxLand = landPerPower<=0 ? getMaxLand() : min(getMaxLand(), (int)(totalPower/landPerPower))`; `currentLand = countByFactionId`; `nowRaidable = currentLand > maxLand`. If unchanged vs `faction.isRaidable()` skip. Else set+save; message members `raidable.became-raidable` / `raidable.no-longer-raidable`; if `isRaidableBroadcastServerWide()` broadcast `raidable.server-announce` / `raidable.server-announce-recovered` (`{faction}`).
- **`computeTotalPower(factionId)`** = `powerService.getFactionPower(factionId)`.

### 5.8 Power buy (`/f power buy <amount>`, `CmdPowerBuy`)
Gate: `isPowerBuyEnabled()`, Vault enabled. `maxPerPurchase = getPowerBuyMaxPerPurchase()`. Parse amount via `MoneyParser`; invalid if empty/≤0/`>maxPerPurchase`. Load player; if `power >= getMaxPower()` → already max. `actualAmount = min(amount, maxPower - power)`. **`cost = actualAmount * getPowerBuyCostPerPoint()`**. Check balance, `vaultEconomy.withdraw(cost)`, then `powerService.apply(Request(id, BUY, actualAmount, playerName, "BUY", null, null, false))`. Success message `power.buy-success` (`{amount}`=`%.1f`, `{cost}`=`%.2f`).

---

## 6. Claim service (`EngineChunkChange`) — adjacency/overclaim/max-land

Not literally a "service" class; `EngineChunkChange` is the claim engine (stored in `EngineRegistry`, used by `CmdClaim`/`CmdUnclaim`). Per-chunk concurrency via `ConcurrentHashMap<String,Object>` locks keyed `world:x:z` (`buildKey`). Lock removed in `finally`.

### 6.1 `claim(player, chunk)` algorithm
1. Resolve player model; if absent or `!isInFaction()` → "not in a faction", false.
2. Re-fetch faction inside lock (NPE-after-disband guard); if gone → "no longer exists".
3. **Existing-claim / overclaim handling**: look up `board().findByChunk`. If present:
   - `!isOverclaimingEnabled()` → "already claimed", false.
   - existing is SAFEZONE/WARZONE → "already claimed", false.
   - existing == own faction → "already claimed by your faction", false.
   - `isOverclaimRequireEnemyRelation() && getRelation(faction, existing) != ENEMY` → "can only overclaim…at war", false.
   - Resolve victim faction; `victimLand = countByFactionId`, `victimMaxLand = computeMaxLand(victim)`. If `victimLand <= victimMaxLand` → `claim.enemy-not-raidable` ("still has enough power"), false.
   - **F5 offline protection**: if `isOfflineProtectionEnabled()` and NO victim member is online (`Bukkit.getPlayer(uuid) != null`) → `claim.enemy-offline-protected`, false.
   - **F6 war shield**: if `isWarShieldEnabled() && victim.isShieldActive()` → `claim.shield-active`, false.
   - Set `victimFaction = candidate`. (If victim row missing, `victimFaction` stays null → claim proceeds to clean up the stale entry.)
4. **Land/power check**: `currentLand = countByFactionId(factionId)`; `maxLand = computeMaxLand(faction, factionId)`; if `currentLand >= maxLand` → "not enough power to claim more land", false.
5. **Border check** (skipped when overclaiming, i.e. `victimFaction != null`): `isValidBorder(...)`; on fail → "may only claim land that borders your own territory or wilderness", false.
6. Fire cancellable `FactionChunkClaimEvent(faction, playerUUID, world, x, z, victimFaction)`; if cancelled → false.
7. Persist: `board().claimChunk(world, x, z, factionId)`.
8. On overclaim: message claimer `claim.overclaimed` (`{faction}`); message each online victim member `claim.overclaimed-victim` (`{attacker}`, `{remaining}` = victim's remaining chunk count).

### 6.2 `computeMaxLand(faction, factionId)` — the land formula
`totalPower = faction.getPowerBoost()`; for each member: if `isPowerInactiveExclusionEnabled()` and `last>0 && now-last > inactiveDays*24*3600*1000` skip; else add `pm.getPower()`. Then:
```
landPerPower = getLandPerPower()
if (landPerPower <= 0) return getMaxLand()
return min(getMaxLand(), (int)(totalPower / landPerPower))
```
(Integer truncation of the division; hard ceiling `getMaxLand()`.)

### 6.3 `isValidBorder(chunk, factionId, faction)` — adjacency
- If `countByFactionId(factionId) == 0` → **true** (first claim is always valid).
- Else check the 4 cardinal neighbors `{(1,0),(-1,0),(0,1),(0,-1)}`. Valid if **any** neighbor is: wilderness (unclaimed), OR own faction, OR a faction whose relation to us is **not ENEMY**. Invalid only when ALL four neighbors are ENEMY territory. **No buffer-zone spacing is applied** (`getLandBufferZone()` is unused).

### 6.4 `unclaim(player, chunk)`
Player must be in a faction; chunk must be claimed; chunk must belong to player's faction (else "can only unclaim your own faction's territory"). If faction row gone → unclaim anyway (cleanup), true. Fire cancellable `FactionChunkUnclaimEvent`; if not cancelled → `board().unclaimChunk`.

### 6.5 `getRelation(faction, otherFactionId)`
SAFEZONE/WARZONE → NEUTRAL; other faction missing → NEUTRAL; else lightweight substring parse of `relations_json` for token `"<id>":"` and `Relation.valueOf(...)`, defaulting NEUTRAL on any miss/parse error.

### 6.6 Batch claim (`CmdClaim`)
`max = max(1, getLandMaxPerCommand())` bounds the chunk set. Modes: `square`/`circle`/`nearby` (radius arg), `fill` (radius 1 square), default = single center chunk. Also `auto`/`at` submodes via `AutoTerritoryModeCache`. Each chunk is first gated by `territoryGuard.canModifyTerritory(...)` then `engineChunkChange.claim`. Reports count claimed. **Claiming is not economically charged** (`getCostClaim` unused).

---

## 7. Other internal services

### 7.1 `FactionService` / `FactionServiceImpl`
Single source of truth for faction mutations. Fires **internal** events (`FactionCreateEvent`, `FactionDisbandEvent`, `FactionJoinEvent`, `FactionLeaveEvent`, `FactionBankTransactionEvent` via engine). Constructor variants: `(plugin, repos, config, logger)` and `(plugin, repos, config, notificationsConfig, logger)`. `setAuditService` (defaults `AuditService.NOOP`). Exposes `getPlugin/getRepos/getConfig/getLogger` for adapters. Holds `flyStateByPlayer: ConcurrentHashMap<UUID,Boolean>` (in-memory, non-persistent).

Key behaviors:
- **`createFaction(name, ownerUUID)`**: if name taken → empty. In a transaction: create `FactionModel` (createdAt now, powerBoost 0, money 0), and three ranks (member/officer/owner) each with the configured default prefix (`normalizeDefaultPrefix`: null if blank else trimmed) at priorities `PRIORITY_MEMBER/OFFICER/OWNER`. Owner player: `findOrCreate`, set faction/ownerRank, joinedAt now, power 0, powerBoost 0, lastActivity 0, overriding false, territoryTitles true, title "" if null, autoTerritoryMode preserved. After commit: `applyPredefinedSeedIfNeeded(faction)`, fire `FactionCreateEvent`. On `StorageException` → throw `IllegalStateException`.
- **`disbandFaction(id)`**: fire `FactionDisbandEvent` first, then transaction deleting board, warps, teamChests, invitations, ranks, `clearFactionMembers`, `clearIncomingRelations`, then delete faction. Returns false if faction absent.
- **`removeMember(factionId, uuid)`**: clears player faction/rank, fires `FactionLeaveEvent`. **No audit entry**.
- **`kickMember(actor, target)`**: same-faction check; both have ranks; `RankAuthority.canManage(actorRank, targetRank)` (actor priority > target priority). Clears target; audits `MEMBER_KICK` with target name.
- **`joinFaction(factionId, uuid)`** (public, used by invite accept & TeamsAPI adapter): faction must exist, default rank must exist; enforce member cap (`getMaxMembers() > 0 && count >= max` → false); transaction: `findOrCreate` player, set faction/defaultRank/joinedAt, delete that player's invitations; then `notifyFactionMembersOnJoin` (respects `member.notify-player-joined`, key `member.player-joined`, excludes the joiner), fire `FactionJoinEvent`.
- **`setRelation(actor, targetName, relation)`**: source & target must exist and differ. Parse both relation maps. Enforce **limits** via `withinRelationLimit`: only for ALLY/TRUCE and only when changing to a new value; count existing of that type; max = `getMaxAllies()`/`getMaxTruces()` (floored at 0); reject if `count >= max`. Save source relation; audit `RELATION_CHANGE`. **ENEMY and NEUTRAL are always mirrored** onto the target. Friendly (ALLY/TRUCE) is only mirrored (promoted to active) when the target already declared the same relation back.
- **`transferOwnership(owner, newOwner)`**: both same faction; actor must be owner rank; find owner rank + officer rank; transaction demotes old owner to officer rank, promotes new owner to owner rank, sets `faction.ownerId`.
- **`promoteMember`/`demoteMember`** → `changeMemberRank(actor, target, promote)`: same faction, both ranked, `canManage(actor, target)`. Rank list ordering: index of target's rank in `findByFactionId` list; `nextIdx = promote ? idx-1 : idx+1`; bounds-checked. Cannot promote **into** owner rank. `canManage(actor, newRank)` also required. Audits `MEMBER_PROMOTE`/`MEMBER_DEMOTE`.
- **Roles** (`createRole`/`renameRole`/`setRolePriority`/`setRolePrefix`/`deleteRole`/`assignRole`): gated by `isCustomRolesEnabled()` and/or `isRoleFactionOverridesEnabled()` (prefix ops need `isRolePrefixesEnabled()`). Protected builtins (owner/officer/member) cannot be renamed/repriced/deleted. `createRole` returns a `CreateRoleResult` (see §7.7). Priority validity via `isValidCustomRolePriority`: within `[getMinCustomRolePriority, getMaxCustomRolePriority]`, but if `min > max` falls back to `RankAuthority.MIN_CUSTOM_PRIORITY..MAX_CUSTOM_PRIORITY`. Custom-role count cap via `withinCustomRoleCountLimit` (0 = unlimited; counts non-builtin ranks). Role mutations call `RoleChangeNotifierHolder.getNotifier().role*()` to mirror to TeamsAPI. `deleteRole` refuses if any member currently uses the rank. Prefix normalization: null if blank; reject (null) if length > `getMaxRolePrefixLength()`.
- **`mergeFaction(sender, target, actor)`**: both exist; target default rank exists. Collect sender members (for post-tx events). Transaction: add sender money to target; reassign all sender board entries + warps to target; migrate members to target at default rank (joinedAt now); delete sender invitations, sender merge-requests (both directions), sender ranks; `clearIncomingRelations(sender)`; delete sender faction. After commit fire `FactionJoinEvent` per migrated member; audit `MERGE_ACCEPT`.
- **`isFactionFull(id)`**: `getMaxMembers() <= 0` → false; else `memberCount >= max`.
- **Relation JSON**: stored as `{"<factionId>":"RELATION",...}` (string values), parsed/serialized with hand-rolled scanners (`parseRelations`/`serializeRelations`/`stripQuotes`). `clearIncomingRelations(id)` removes id from every other faction's map.
- Fly prefs: `setFactionFlyEnabled`/`isFactionFlyEnabled` are in-memory only (default false).

### 7.2 `InviteService` / `InviteServiceImpl`
Depends on `FactionServiceImpl`, repos, config, logger. Invites stored as `InvitationModel(factionId, inviterId, inviteeId, createdAt)`.
- `sendInvite`: prunes expired for invitee first; faction must exist; reject duplicate `(faction,invitee)`; save new invite.
- `acceptInvite`: prune; find invite; faction must exist (else delete invite, empty); default rank must exist; delete invite; `factionService.joinFaction`; return the faction.
- `listInvitesForPlayer` (all), `listActiveInvitesForPlayer` (prune + filter active), `listInvitesForFaction` (filter active).
- `revokeInvite` (prune + find + delete), `declineInvite` = `revokeInvite`, `declineAllInvites` (count then `deleteByInviteeId`).
- `pruneExpiredInvitesForPlayer` / `pruneAllExpiredInvites` — delete non-active rows, return removed count.
- **`isActive`**: `ttlHours = max(1, getInviteTtlHours())`; `expiry = createdAt + ttlHours*3600*1000`; active iff `now <= expiry`.

### 7.3 `WarpService` / `WarpServiceImpl`
Repos+config+logger. Exposes `getRepos/getConfig/getLogger`.
- `getWarps(factionId)`, `getWarp(factionId, name)`.
- `setWarp(factionId, name, location, creatorUUID)`: faction must exist and location world non-null; **warp cap**: for a NEW warp, reject if `currentCount >= getMaxWarps()`. Create or update `WarpModel` (random UUID id if new); copy world/x/y/z/yaw/pitch; set creator (if non-null) and createdAt (only for new). Save.
- `deleteWarp`, `setWarpPassword` (null/empty clears), `setWarpCost` (`useCost = max(0, cost)`). All catch StorageException → log + false.
- Warp teleport/cost logic lives in `CmdWarp`: if warp has a password, require it; if `useCost > 0` require economy + balance ≥ cost, then charge. Teleport delegated to `EssentialsInterop` (warmup/delay is Essentials-provided; the plugin itself has no warmup timer).

### 7.4 `FlagService` / `FlagServiceImpl`
Per-faction flags stored sparsely in `FactionModel.getFlagsJson()` as `{"flag-id":true,...}`.
- `getFlag(faction, flag)`: parsed map value if present, else `config.getFlagDefault(flag)`.
- `setFlag(faction, flag, value)`: put into parsed map, re-serialize, save.
- `getAllFlags(faction)`: `EnumMap<FactionFlag,Boolean>` covering every flag (override or default).
- `isFlagEditable(flag)`: `config.isFlagPlayerEditable(flag)`.
- JSON helpers: `parseFlags` (tolerant `{}`/brace-scan; only recognizes literal `true`/`false`); `serializeFlags` (compact `{"id":true,...}`, unquoted boolean). Consumed by `EngineProtection` for pvp/friendly-fire/explosions/fire-spread and by join (OPEN).

### 7.5 `MergeService` / `MergeServiceImpl`
`MergeRequestModel(senderFactionId, targetFactionId, actorId, createdAt)`.
- `sendMergeRequest`: both factions exist; reject duplicate `(sender,target)`; save.
- `acceptMergeRequest`: request must exist; delegate to `factionService.mergeFaction`; on success return target faction.
- `listRequestsBySender`, `listRequestsForTarget`.

### 7.6 `AuditService` / `AuditServiceImpl`
Interface has a `NOOP` no-op instance. `record(factionId, actorUUID, action, detail)`: build `AuditLogModel` (random id, factionId, actorUuid string or null, `action.getId()`, detail or "", createdAt now), save. Storage failures → warning (never throws).

### 7.7 `CreateRoleResult` (enum)
`SUCCESS, FEATURE_DISABLED, NOT_IN_FACTION, INVALID_NAME, NAME_TAKEN, PRIORITY_OUT_OF_RANGE, ACTOR_RANK_INSUFFICIENT, ROLE_LIMIT_REACHED, STORAGE_ERROR`. Returned by `createRole`.

### 7.8 `RankAuthority` (static helpers)
`MIN_CUSTOM_PRIORITY = PRIORITY_MEMBER+1`, `MAX_CUSTOM_PRIORITY = PRIORITY_OWNER-1`. `canManage(actor, target)` = `actor.priority > target.priority`. `findByName(ranks, name)` (case-insensitive normalized). `isProtectedBuiltin(name)` (owner/officer/member). `normalizeName` = trim+lowercase(ROOT) (null→"").

### 7.9 `TeamChestService` / `TeamChestServiceImpl` / `TeamChestSerialization`
Chests stored as `TeamChestModel(factionId, name, contents Base64, createdAt)`. Names normalized to trimmed lowercase(ROOT); blank → null (rejected).
- `getChestNames` (sorted CASE_INSENSITIVE).
- `createChest`: reject duplicate (case-insensitive) or if `existing.size() >= getMaxTeamChests()`; encode empty list into contents.
- `deleteChest`, `getChestContents` (decode), `setChestContents` (encode, null→empty).
- `ensureChestExistsForOpen(factionId, requested)`: return existing name if present; else if under cap, `createChest` and return normalized name; else empty.
- **Serialization**: `encode` writes an int count then each `ItemStack` via `BukkitObjectOutputStream`, Base64-encodes; `decode` reverses; blank/null → empty list. Throws `IOException`/`ClassNotFoundException` (handled by service).

---

## 8. Util classes

### 8.1 `MsgUtil` — MiniMessage rendering + rich text
- **`ADVENTURE`** (static boolean): true iff `net.kyori.adventure.text.Component` AND `...minimessage.MiniMessage` resolve AND `CommandSender.sendMessage(Component)` exists. This is the true **Paper vs Spigot** distinguisher: Paper exposes the adventure overload (native path), Spigot does not (legacy path).
- `setMessagesConfig`/`getMessagesConfig` (volatile).
- **`send(sender, mm)`**: `AdventureOps.send` if ADVENTURE else `LegacyOps.send`.
  - `AdventureOps`: cached MethodHandles resolve the server's native MiniMessage (`deserialize(String, TagResolver[])` vararg — the single-arg form was removed in Adventure 4.20.0) and `sendMessage(Component)`. On any failure falls back to `sender.sendMessage(stripTags(mm))`. Uses string literals so the shade relocator does NOT rewrite them.
  - `LegacyOps`: uses the **shaded** (relocated `com.reference.lib.adventure.*`) MiniMessage + `LegacyComponentSerializer.legacySection()` + a `GsonComponentSerializer` initialized under the plugin classloader. For players, serializes to JSON→BungeeCord `BaseComponent[]` and sends via `player.spigot().sendMessage(...)` (preserves hover/click on Spigot); for non-players uses legacy §-codes.
- `sendKey(sender, key, fallback, kv…)`: `message(sender,key,fallback)` then `replace` then `send`.
- `message(key, fallback)` / `message(sender, key, fallback)`: pull from `MessagesConfig` (default-locale vs sender-locale) or return fallback if config null.
- `replace(template, kv…)`: `{key}`→value token substitution over alternating pairs.
- `stripTags(mm)`: two-pass regex removing MiniMessage tags (first quoted-arg tags `<[a-zA-Z_][^'<>]*'[^']*'[^>]*>`, then `<[^>]*>`).
- `toLegacy(mm)` → §-string via `LegacyOps`.
- `mmEscape(value)`: escape `\` then `'` for single-quoted MiniMessage tag args.
- Rich builders (all return MiniMessage strings): `helpEntry` (hover+suggest_command), `infoHeader`, `warpEntry` (run_command `/f warp`), `inviteNotification` (Accept run_command `/f join`, Deny hover-only), `inviteListEntry`, `unknownCommand` (`[Help]` run_command `/f help`), `factionInfoHover`.

### 8.2 `MoneyParser`
`parse(String)→OptionalDouble`. Trim+lowercase. Suffix multiplier: `k`=1e3, `m`=1e6, `b`=1e9, `t`=1e12, else 1. Strip suffix if multiplied; `Double.parseDouble(numeric)*multiplier`; empty on null/blank/parse error.

### 8.3 `ChunkPos`
Value object `(world, x, z)`. `toEntryId()` = `world:x:z`. `fromEntryId(id)` splits on `:` (limit 3). Correct `equals`/`hashCode`. `toString` = entry id.

---

## 9. Metrics — `BStatsMetricsManager` (implements `Listener`)
Constants: `RELATION_REFRESH_TICKS = 20*60*15` (15 min). Two ctors (with/without `TaskScheduler`). State: `AtomicInteger createdFactionsSinceStartup`, `AtomicInteger totalFactions`, `volatile Map<String,Integer> relationCounts` (default all-relation-lowercase→0), `volatile boolean active`.
- `stop()`: `active=false` (async tasks then skip DB work).
- `start(pluginId)`: register events; `warmCacheAsync()` (async: set totalFactions from `countAll`, refresh relations); `scheduleRelationRefresh()` (async timer every 15 min); create bStats `Metrics(plugin, pluginId)` and add charts:
  - `SingleLineChart("created_factions")` → `createdFactionsSinceStartup.getAndSet(0)`.
  - `SingleLineChart("total_factions")` → `totalFactions.get()`.
  - `DrilldownPie("relationship_type")` → snapshot of `relationCounts` keyed by relation name, drill value = plugin version.
  - `DrilldownPie("database_type")` → `{normalizedDbType: {version: 1}}`.
- Listeners: `onFactionCreate` (both counters +1), `onFactionDisband` (`totalFactions = max(0, cur-1)`).
- `refreshRelationCache`: scan all factions' `relations_json`, count each relation value (lowercased). `parseRelations`/`stripQuotes` are private copies of the JSON scanner. `normalizeDatabaseType` lowercases (null/blank→"unknown"). Falls back to `Bukkit.getScheduler()` async when `taskScheduler` is null.

---

## 10. Public API surface (`api/` — TeamsAPI adapters)

Design contract: **the entire `api/` package (except the notifier holder/interface) is loaded only when TeamsAPI is present.** TeamsAPI lives at `com.skyblockexp.teamsapi.*`. Isolation strategy: `TeamsApiRegistrar` (interface, no TeamsAPI imports) is referenced by bootstrap; `TeamsApiRegistrarImpl` (has TeamsAPI imports) is loaded only via `Class.forName`. Services added in later TeamsAPI versions (chest 2.3, relation 1.6, notification 1.7, power-history 1.8) are registered via **reflection** and stored as `Object` to avoid bytecode-verifier resolution on older TeamsAPI.

### 10.1 `TeamsApiRegistrar` / `TeamsApiRegistrarImpl`
`register(plugin, factionImpl, inviteImpl, warpImpl, teamChestImpl)→boolean`; `unregister()`.
Impl `register`: instantiate + register core providers `TeamsAPI.registerProvider(FactionsTeamsService)`, `registerInviteProvider`, `registerWarpProvider`, `registerClaimProvider`, `registerPowerProvider`; `TeamsCustomRoleRegistry.registerAll`; install a runtime `RoleChangeNotifier` (via holder) that mirrors role create/update/rename/delete to `TeamsAPI.registerCustomRole`/`unregisterCustomRole`. Any exception in this block → `unregister()` + return false. Then optionally register chest/relation/notification/power-history providers reflectively (ClassNotFound = silently skip old TeamsAPI; other reflective errors = warn). `unregister`: unregister each provider defensively (try/catch ignore), reflectively for the optional ones; always `TeamsCustomRoleRegistry.unregisterAllForPluginData`; `RoleChangeNotifierHolder.clearNotifier()`; null out repos/logger.

### 10.2 Wrapper models
- **`FactionTeam implements Team`**: wraps `FactionModel` + repos/config/logger. `getId` (UUID from model id), name/displayName = faction name, ownerUUID (parsed, null-safe), `getMembers()` lazily loads + caches `List<TeamMember>` (`invalidateCache()` clears), `getMemberUUIDs`, `getSize`, `getMaxSize`=`getMaxMembers()`, `getMember`, `isMember`, `isOwner`. Static `roleToPriority` delegates to `TeamRoleMapper`.
- **`FactionTeamMember implements TeamMember`**: wraps player+rank. `getRole` via `TeamRoleMapper.rankToRole`. `getJoinedAt` from `joinedAt` millis. `getRoleDefinition`: if rank null → `TeamRoleDefinition.of(role)`; else `TeamsAPI.getCustomRole(roleKey)` or fallback.
- **`FactionTeamClaim implements TeamClaim`**: wraps `BoardEntry`. `getTeamId`: SAFEZONE/WARZONE → deterministic `UUID.nameUUIDFromBytes(sentinel)`, else `UUID.fromString(factionId)`. `getTerritoryType`: SAFE_ZONE/WAR_ZONE/TEAM. `getOwningTeamId`: present only for TEAM. `getClaimedAt` always `Instant.EPOCH` (not persisted).
- **`FactionTeamWarp implements TeamWarp`**: wraps `WarpModel`; `getLocation` builds Bukkit `Location`; creator/createdAt from model.

### 10.3 Service adapters (delegate to impls, additionally fire TeamsAPI events)
- **`FactionsTeamsService implements TeamsService`**: read queries delegate to `FactionServiceImpl`. Mutations fire TeamsAPI events after impl succeeds: `createTeam`→`TeamCreateEvent`, `deleteTeam`→`TeamDeleteEvent` (wrap before disband), `addMember`→`TeamJoinEvent`, `removeMember`→`TeamLeaveEvent`. `setMemberRole`: resolves internal rank for a `TeamRole` via `TeamRoleMapper.resolveRankForRole`, saves rankId, fires `TeamRoleChangeEvent`. `getMemberRole`/`getMemberInfo` map back. `getTeamIds` (TeamsAPI 2.5.0+).
- **`FactionsTeamsInviteService`**: `invitePlayer` fires `TeamInviteEvent` (cancellable) then `impl.sendInvite`; `acceptInvite` delegates then fires `TeamJoinEvent`; `declineInvite` deletes the invitation row directly via repos.
- **`FactionsTeamsWarpService`**: `setWarp` fires `TeamWarpSetEvent` (cancellable), `removeWarp` fires `TeamWarpDeleteEvent`; `getWarp`/`getWarps` map to `FactionTeamWarp`.
- **`FactionsTeamsClaimService`**: `claimChunk` (fires cancellable `TeamClaimEvent`, rejects already-claimed), `unclaimChunk` (fires `TeamUnclaimEvent`), `unclaimAll` (batch, no per-chunk events), `getClaimAt`, `getTeamClaims`, `getClaimCount`, `isClaimed`, `isClaimedBy`. **`getTeamMaxClaims`**: `totalPower = powerBoost + Σ member power`; `landPerPower<=0 → getMaxLand()`; else `min(getMaxLand(), (int)(totalPower/landPerPower))` — mirrors `EngineChunkChange.computeMaxLand` **but without inactive-exclusion**. Special-zone ops: `claimSafeZone`/`claimWarZone` (write sentinel faction ids, overwrite), `unclaimSpecialZone` (only removes SAFEZONE/WARZONE chunks). TeamsAPI 1.7+.
- **`FactionsTeamsPowerService`**: `getPlayerPower` (model power or 0), `getPlayerMaxPower` = `getMaxPower()`, `setPlayerPower` clamps to `[0, getMaxPower()]` and saves directly (bypasses `PowerService.apply` — no multipliers/freeze/history). `getTeamPower` = `powerBoost + Σ member power` (no inactive exclusion). `getTeamMaxPower` = `getMaxPower() * memberCount`.
- **`FactionsTeamsRelationService`** (1.6+): `setRelation` fires cancellable `TeamRelationChangeEvent`; NEUTRAL removes the entry, else stores mapped internal `Relation`. **Only stores the outgoing (from-side) relation — does NOT mirror** (unlike `FactionServiceImpl.setRelation`). `getRelation` (self→MEMBER; missing→NEUTRAL), `getRelations` (non-neutral, non-member only), `clearRelations` (own map + all incoming references). Mapping: internal↔`TeamRelation` with `MEMBER→MEMBER, ALLY→ALLY, TRUCE→TRUCE, ENEMY→ENEMY, else NEUTRAL`; reverse honors TeamsAPI 2.1+ custom `RelationNature` (FRIENDLY→ALLY, HOSTILE→ENEMY).
- **`FactionsTeamsChestService`** (2.3+): `DEFAULT_CHEST_ID` used for no-name overloads. `getChestIds`, `getContents`, `setContents`, `addItem`/`removeItem` (read-modify-write via impl). Resolves faction id via `factionImpl.getFactionById`.
- **`FactionsTeamsNotificationService`** (1.7+): delivers to online players only (`player.sendMessage`, false if offline). Only `TEAM_INVITE` maps to a persisted pref (`PlayerModel.hasInviteNotifications`/`setInviteNotifications`); all other/custom types default enabled and `setNotificationEnabled` returns false (unsupported).
- **`FactionsTeamsPowerHistoryService`** (1.8+): player history from `powerHistory` repo (limit ≤0 → 50), team history = combined member history sorted desc. `addPowerHistoryEntry` inserts (reason uppercased). `updatePowerHistoryEntry` always false (unsupported). `removePowerHistoryEntry`, `clearPlayer/TeamPowerHistory`. Entry `getType` = GAIN if delta≥0 else LOSS; team-id/actor/details empty.

### 10.4 Role mapping
- **`TeamRoleMapper`**: `roleToPriority`: OWNER→`PRIORITY_OWNER`, ADMIN→`PRIORITY_OFFICER`, MEMBER→`PRIORITY_MEMBER`. `rankToRole`: null→MEMBER; priority≥OWNER→OWNER; ≥OFFICER→ADMIN; else MEMBER. `resolveRankForRole(ranks, role)`: OWNER→max priority; ADMIN→highest rank in `[OFFICER, OWNER)`, falling back to highest below owner; MEMBER→min priority.
- **`TeamsCustomRoleRegistry`**: `roleKey(factionId, rankId, rankName)` = `"reference:<factionId>:<rankId>:<safeName>"` (name lowercased, non-`[a-z0-9_-]`→`-`). `toRoleDefinition(rank)` = `TeamRoleDefinition(key, priority, prefix)` (prefix = rank prefix or built-in role's prefix or ""). `registerAll`/`unregisterAllForPluginData` iterate all factions×ranks.
- **`RoleChangeNotifier`** (interface, default no-ops `roleCreated/roleUpdated/roleRenamed/roleDeleted`) + **`RoleChangeNotifierHolder`** (volatile no-op default; `getNotifier/setNotifier/clearNotifier`). Core service always calls the holder; only the TeamsAPI registrar installs a real notifier. This is the ONE part of the api-package coupling that is always on the classpath (imported by `FactionServiceImpl`).

---

## 11. Cross-feature interactions & gotchas
- **Config keys defined but never consumed**: `factions.land.buffer-zone`, `factions.economy.cost-create`, `factions.economy.cost-claim`, `factions.fly.disable-on-threat`. A faithful reimplementation should preserve them in the default config for compatibility but need not implement behavior. (Fly has NO threat-radius scanner or timer — the only fly logic is the manual `/f fly` toggle gated by `isFlyEnabled()` and `isFlyRequireOwnTerritory()`.)
- **Two power totals coexist**: `PowerService.getFactionPower` / `EngineChunkChange.computeMaxLand` apply inactive-member exclusion; the TeamsAPI `getTeamPower`/`getTeamMaxClaims` adapters do **not**. Reimplement both behaviors as-is unless intentionally unifying.
- **Regen ignores caller delta**: the tick passes `baseDelta=0` but full configured regen is applied because `sourceAmount` returns the config value for regen sources.
- **Power history** is written on every effective `apply` via `repos.powerHistory().record(id, effectiveDelta, reasonCode, after)`.
- **`/fa reload`** re-runs services→engines→commands→optional-hooks by stopping and restarting them in order (`Bootstrap.reloadServices`), which re-reads config/messages fresh.
- **Fly toggle** (`CmdFly`) sets `player.setAllowFlight(newState)` and clears active flight when disabling; state also kept in `FactionServiceImpl.flyStateByPlayer` (in-memory, lost on restart).
- **Teleport warmup/cancel-on-move/damage** are NOT implemented in-plugin: `/f home` and `/f warp` delegate to `EssentialsInterop.teleport(player, loc, onSuccess, onFail)`; if Essentials returns false the plugin does an immediate `player.teleport(loc)` with no warmup. Any warmup/cancel-on-move behavior is Essentials'.
- **Tax scheduler** (`EngineEconomy`): only if `isBankEnabled() && isTaxEnabled()`. Interval `max(1, getTaxIntervalHours())*3600*20` ticks, async. Per pass: skip non-normal factions; skip if `bank <= max(0, getTaxMinBankBalance())`; `computed = round2(bank*rate)`; skip if `computed<=0 || computed < max(0,getTaxMinChargeAmount())`; `taxAmount = min(bank, computed)`; `newBank = round2(max(0, bank-taxAmount))`; save; record `TAX` transaction; notify members (`bank.tax-charged`) if `economy.tax.notify-members`. `round2(v)=Math.round(v*100)/100.0`.
- **Bank ops** (`EngineEconomy.deposit/withdraw/transfer`) re-check Vault availability at each step, reject non-positive amounts, fire cancellable `FactionBankTransactionEvent`, and wrap transfers in a Jaloquent transaction with rollback on Vault failure.
