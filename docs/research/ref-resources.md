# the reference implementation — Resources & Config Behavior Spec

Clean-room reimplementation reference for **FableFactions**. Source of truth: `the reference implementation/src/main/resources/**` plus `the reference implementation/docs/**`. This document captures every config key (with default + semantics), the full `plugin.yml` command/permission tree, the complete `messages_en.yml` key inventory (exact English strings + placeholder tokens), `gui.yml` layout semantics, `roles.yml`, `notifications.yml` routing, `database.yml`, `pre-defined.yml`, and behavior revealed only in `/docs`.

> **Localization contract:** 8 locale bundles ship: `en, es, de, fr, pt-BR, ja, zh, ru` under `messages/messages_<locale>.yml`. Every non-en bundle MUST carry the identical key set to `en` (27 top-level groups). Values use **MiniMessage** formatting. Placeholders in `{curly}` form must be preserved verbatim in every translation. Lookup order: (1) player-selected locale via `/f language <code>`, (2) server default `factions.language.default`, (3) `en`, (4) inline Java fallback string.

---

## 1. `config.yml` — full key tree

Header comment: "Restart required for most changes. Reload with `/fa reload`. Database settings moved to `database.yml`." All keys below live under root `factions:` unless the section header says otherwise (`integrations:` is a separate root).

### 1.1 `factions.*` top-level limits

| Key | Default | Semantics |
|---|---|---|
| `factions.max-members` | `50` | Max members per faction. **NOTE:** `membership-and-ranks.md` claims default `0`=unlimited, but the shipped config value is `50`. Reimplement so `0` = unlimited; ship `50`. When cap reached: `/f join` blocked ("full"); invites may still be sent but acceptance blocked until a slot frees. |
| `factions.max-warps` | `10` | Max warps per faction. Hitting cap → `warp.limit-reached` / `custom.warp.set-failed`. |
| `factions.max-team-chests` | `1` | Max named team chests per faction. |
| `factions.max-allies` | `5` | Max ALLY relations. |
| `factions.max-truces` | `5` | Max TRUCE relations. |
| `factions.team-chest.default-name` | `default` | Name of the implicit chest opened by bare `/f chest`. |
| `factions.invites.ttl-hours` | `72` | Pending invite lifetime. Expired invites auto-pruned on login/list/join. |

### 1.2 `factions.language.*`

| Key | Default | Semantics |
|---|---|---|
| `factions.language.default` | `en` | Server default locale for faction messages. Falls back to `en` if bundle missing. |
| `factions.language.allow-player-override` | `true` | If false, players cannot set a personal locale (`language.override-disabled` shown). |
| `factions.language.command-opens-gui` | `true` | Running `/f language` with no args opens the language GUI. |
| `factions.language.command-open-gui-after-set` | `true` | Re-open language GUI after `/f language <code>` or `/f language reset`. |
| `factions.language.visible-locales` | `[]` (empty list) | Whitelist of locales shown in tab-completion + GUI. Empty = all loaded locales available. |

### 1.3 `factions.power.*` — the power model (load-bearing math)

| Key | Default | Semantics |
|---|---|---|
| `factions.power.per-player-max` | `10.0` | Max power each online player contributes to the faction. |
| `factions.power.regen-per-second` | `0.1` | Power regenerated per second while online (base rate feeding regen defaults). |
| `factions.power.loss-on-death` | `4.0` | Power lost when a player dies in enemy or war-zone territory. |
| `factions.power.grace-period-seconds` | `3600` | Seconds after server start before power-loss-on-death is enforced. |
| `factions.power.tick-interval-seconds` | `60` | Interval between power-tick engine updates (scheduler period). |
| `factions.power.gain-on-kill.enabled` | `true` | Killing an enemy player grants power to the killer. |
| `factions.power.gain-on-kill.amount` | `2.0` | Power awarded per kill. |
| `factions.power.gain-on-kill.scale.enabled` | `false` | **(F3)** Scale kill reward by victim/killer power ratio. |
| `factions.power.gain-on-kill.scale.min-factor` | `0.25` | Killing a weaker enemy → as little as `min-factor × base`. |
| `factions.power.gain-on-kill.scale.max-factor` | `2.0` | Killing a stronger enemy → up to `max-factor × base`. |
| `factions.power.inactive-exclusion.enabled` | `false` | **(F1)** Exclude long-offline members from the faction max-land calc. Stored power unchanged; only land-cap computation skips them. |
| `factions.power.inactive-exclusion.days` | `7` | Offline threshold in days for exclusion. |
| `factions.power.death-streak.enabled` | `false` | **(F2)** Multiply power loss for consecutive deaths in a window. Streak 0 = first death (no penalty); streak 1 = loss × multiplier; etc. |
| `factions.power.death-streak.window-seconds` | `600` | Window for counting consecutive deaths. |
| `factions.power.death-streak.multiplier` | `1.5` | Per-streak loss multiplier. |
| `factions.power.buy.enabled` | `false` | Opt-in: allow buying personal power with money (`/f power buy`). Requires Vault. |
| `factions.power.buy.cost-per-point` | `100.0` | Money charged per 1 power point. |
| `factions.power.buy.max-per-purchase` | `5.0` | Max power buyable per command invocation. |
| `factions.power.sources.regen-online.enabled` | `true` | Online-regen source toggle. |
| `factions.power.sources.regen-online.amount` | `6.0` | Power gained per tick while online. |
| `factions.power.sources.regen-offline.enabled` | `true` | Offline-regen source toggle. |
| `factions.power.sources.regen-offline.amount` | `3.0` | Power gained per tick while offline. |
| `factions.power.sources.death-loss.enabled` | `true` | Death-loss source toggle. |
| `factions.power.sources.death-loss.amount` | `4.0` | Base death-loss amount. |
| `factions.power.sources.kill-gain.enabled` | `true` | Kill-gain source toggle. |
| `factions.power.sources.kill-gain.amount` | `2.0` | Base kill-gain amount. |
| `factions.power.sources.buy.enabled` | `true` | Buy source toggle (AND-ed with `power.buy.enabled`). |
| `factions.power.constraints.min-power` | `0.0` | Hard lower bound. |
| `factions.power.constraints.max-power` | `10.0` | Hard upper bound. |
| `factions.power.constraints.max-change-per-event` | `0.0` | Per-event absolute change clamp; `0` disables clamp. |
| `factions.power.multipliers.worlds` | `{}` | Map `<worldName> → multiplier` applied to death/kill source adjustments (e.g. `world_nether: 1.25`). |
| `factions.power.multipliers.zones.safezone` | `1.0` | Zone multiplier for death/kill in safezone. |
| `factions.power.multipliers.zones.warzone` | `1.0` | Zone multiplier — warzone. |
| `factions.power.multipliers.zones.own_claimed` | `1.0` | Zone multiplier — own claimed. |
| `factions.power.multipliers.zones.enemy_claimed` | `1.0` | Zone multiplier — enemy claimed. |
| `factions.power.multipliers.zones.wilderness` | `1.0` | Zone multiplier — wilderness. |
| `factions.power.freeze.blocks-automatic` | `true` | While a player is frozen, block non-admin automatic sources. |
| `factions.power.freeze.blocks-regen` | `true` | Block regen sources while frozen. |
| `factions.power.freeze.allow-admin-bypass` | `true` | Admin power commands bypass freeze. |
| `factions.power.notifications.actor` | `true` | Send actor/target-facing power-update messages (`power.change-actor`). |
| `factions.power.notifications.faction` | `false` | Broadcast power updates to faction members (`power.change-faction`). |
| `factions.power.notifications.staff` | `false` | Broadcast to staff channel (`power.change-staff`). |

**Zone multiplier keys** are exactly: `safezone, warzone, own_claimed, enemy_claimed, wilderness` (underscore form).

### 1.4 Pagination / display sections

| Key | Default | Semantics |
|---|---|---|
| `factions.map.once-radius` | `3` | Radius for `/f map once` render. (Reference doc example shows `2` — ship `3`.) |
| `factions.list.page-size` | `8` | `/f list` entries per page. |
| `factions.top.page-size` | `8` | `/f top` entries per page. |
| `factions.info.relations.show-allies` | `true` | Show allies in `/f info`. |
| `factions.info.relations.show-truces` | `false` | Include truces in `/f info`. |
| `factions.info.relations.show-neutrals` | `false` | Include neutrals in `/f info`. |
| `factions.info.relations.show-enemies` | `false` | Include enemies in `/f info`. |
| `factions.warp.list.page-size` | `8` | `/f warp list` per page. |
| `factions.audit.page-size` | `10` | `/f audit` and `/fa audit` per page. |
| `factions.economy.bank.history.page-size` | `8` | `/f bank history` per page. |

### 1.5 `factions.land.*`

| Key | Default | Semantics |
|---|---|---|
| `factions.land.buffer-zone` | `0` | Minimum buffer (chunks) between two ENEMY factions' territory. `0` = disabled. |
| `factions.land.max-per-command` | `200` | A single `/f claim` (fill/circle/square) claims at most this many chunks. |
| `factions.land.per-power` | *(not in shipped config; effective default `1.0`)* | **Undocumented-in-config but implementation-honored.** Land-per-power ratio → faction max land = `floor(totalMemberPower × per-power)`. Reimplement reading this key with default `1.0`. |
| `factions.land.max` | *(not in shipped config; effective default `500`)* | Absolute hard cap on faction land, on top of power-based cap. Default `500`. |

> Max-land = min(power-based cap using `per-power`, `land.max`). Members offline beyond `inactive-exclusion.days` are excluded from the power sum when `inactive-exclusion.enabled`. Overclaiming triggers when victim land count > victim max-land.

### 1.6 `factions.economy.*`

| Key | Default | Semantics |
|---|---|---|
| `factions.economy.enabled` | `true` | Master toggle for economy logic. |
| `factions.economy.cost-create` | `50.0` | Cost (economy units) to create a faction. |
| `factions.economy.cost-claim` | `100.0` | Cost per chunk claimed. |
| `factions.economy.tax.enabled` | `false` | Periodic faction bank tax. |
| `factions.economy.tax.rate` | `0.05` | Fraction of bank balance deducted each interval. |
| `factions.economy.tax.interval-hours` | `24` | Hours between tax cycles. |
| `factions.economy.tax.min-bank-balance` | `0.0` | Do not tax factions with bank below this. |
| `factions.economy.tax.min-charge-amount` | `0.01` | Skip deductions below this amount. |

Tax transaction entries recorded with type `TAX`. Online members notified (`bank.tax-charged`) when `notifications.yml → economy.tax.notify-members: true`.

### 1.7 `factions.fly.*`

| Key | Default | Semantics |
|---|---|---|
| `factions.fly.enabled` | `true` | Master toggle. |
| `factions.fly.disable-on-threat` | `true` | Cancel flight when an enemy enters the chunk (`fly.enemy-nearby`). |
| `factions.fly.require-own-territory` | `true` | Players may only fly inside own faction territory (`fly.left-territory`). |

### 1.8 `factions.flags.*` — per-faction toggleable behavior flags

Officers+ toggle via `/f flag set` unless `player-editable: false`. Admins override any via `/fa flag`. Five flags, each with `default` (bool) and `player-editable` (bool):

| Flag | `default` | `player-editable` | Effect |
|---|---|---|---|
| `pvp` | `true` | `true` | Allow PvP inside claimed territory. |
| `friendly-fire` | `false` | `true` | Allow faction members to harm each other **anywhere**. |
| `explosions` | `false` | `true` | Allow explosions to destroy terrain in territory. |
| `fire-spread` | `false` | `true` | Allow fire spread inside territory. |
| `open` | `false` | `true` | Allow anyone to join without a pending invite. |

Valid flag names string (for error msg): `pvp, friendly-fire, explosions, fire-spread, open`.

### 1.9 `factions.chat.*`

| Key | Default | Semantics |
|---|---|---|
| `factions.chat.show-tag` | `false` | Prefix faction tag before player name in global chat. |
| `factions.chat.tag-format` | `"<gray>[<gold>{faction_name}</gold>]</gray> "` | MiniMessage; placeholders `{faction_name}`, `{rank_prefix}`. |

### 1.10 `factions.metrics.*` / `factions.updates.*`

| Key | Default | Semantics |
|---|---|---|
| `factions.metrics.bstats.enabled` | `true` | Anonymous bStats collection. |
| `factions.updates.enabled` | `true` | Check for new versions on startup. **NOTE:** reference doc claims default `false`/opt-in; shipped value is `true`. |
| `factions.updates.notify-ops-on-join` | `true` | Notify op players of updates on join (`update.available` / `update.url`). |

### 1.11 `factions.zones.*`

| Key | Default | Semantics |
|---|---|---|
| `factions.zones.safe-zone.enabled` | `true` | Safe zone system. Disable → safezone chunks treated as Wilderness. No PvP, no build; assigned only by `/fa safezone`. |
| `factions.zones.war-zone.enabled` | `true` | War zone system. Disable → warzone chunks treated as Wilderness. PvP always on, no build; assigned only by `/fa warzone`. |

### 1.12 `factions.overclaiming.*`

| Key | Default | Semantics |
|---|---|---|
| `factions.overclaiming.enabled` | `false` | Enemies can claim land from factions whose power dropped below their claim count. Border adjacency NOT required for overclaims. |
| `factions.overclaiming.require-enemy-relation` | `true` | Require ENEMY relation before overclaim allowed. |
| `factions.overclaiming.offline-protection.enabled` | `false` | **(F5)** Block overclaim when ALL defending members are offline (prevents pure offline raiding) → `claim.enemy-offline-protected`. |

**Overclaim flow:** (1) attacker `/f claim` on already-claimed enemy chunk; (2) check overclaiming enabled + chunk not a system zone; (3) if `require-enemy-relation`, victim must be ENEMY; (4) victim land > max-land required (else `claim.enemy-not-raidable`); (5) F5 offline-protection check; (6) F6 war-shield check (`claim.shield-active`); (7) attacker gets `claim.overclaimed`, all online victim members get `claim.overclaimed-victim`.

### 1.13 `factions.raidable.*`

| Key | Default | Semantics |
|---|---|---|
| `factions.raidable.broadcast.enabled` | `true` | **(F4)** Notify faction members on raidable↔safe transition during a power tick (`raidable.became-raidable` / `raidable.no-longer-raidable`). |
| `factions.raidable.broadcast.server-wide` | `false` | Also broadcast server-wide (`raidable.server-announce` / `raidable.server-announce-recovered`). |

### 1.14 `factions.war.shield.*`

| Key | Default | Semantics |
|---|---|---|
| `factions.war.shield.enabled` | `false` | **(F6)** Daily time-of-day protection window; land cannot be overclaimed while active. Window = UTC hours 0–23. |
| `factions.war.shield.max-duration-hours` | `8` | Max window length a single `/fa shield` may assign. |

Shield stored per-faction in DB (survives restart), evaluated in UTC. `/fa shield <faction> <start-hour 0-23> <duration>` sets; `/fa shield <faction> clear` removes.

### 1.15 `factions.merge.*`

| Key | Default | Semantics |
|---|---|---|
| `factions.merge.enabled` | `false` | Allow `/f merge send` / `/f merge accept`. When false both commands blocked regardless of permission (`merge.disabled`). |

Accepting a merge transfers all claims, warps, bank balance, and members into the accepting faction; the sender faction is then disbanded.

### 1.16 `integrations:` (root, sibling of `factions:`)

Soft-integration toggles; plugin checks provider at startup and gracefully disables if absent.

| Key | Default | Semantics |
|---|---|---|
| `integrations.vault` | `true` | Vault economy attempt. |
| `integrations.worldguard` | `true` | WorldGuard integration. |
| `integrations.worldguard-sync-regions` | `false` | Mirror claim chunks as WG `ProtectedCuboidRegion`s so WG does native block protection (no per-event DB query). Requires WG. Restart to toggle. See §12. |
| `integrations.dynmap` | `true` | Dynmap territory layer (marker set id `factions`). |
| `integrations.placeholderapi` | `true` | Register `reference` PAPI expansion. |
| `integrations.essentialsx.enabled` | `false` | Route `/f home` and `/f warp` through EssentialsX async teleport (respects delay/safety; records `/back`; blocks while jailed). Requires EssentialsX 2.19+. |
| `integrations.lwc.enabled` | `true` | Master toggle for LWC/LWCX interop. |
| `integrations.lwc.require-build-rights-to-create` | `true` | Block creating protections when creator lacks build rights. |
| `integrations.lwc.remove-if-no-build-rights` | `true` | Remove stale protections on interact when owner lost build rights (`lwc.stale-protection-removed`). |
| `integrations.lwc.remove-on-claim-change` | `true` | Remove alien protections after chunk ownership changes. |
| `integrations.discordsrv.enabled` | `false` | Broadcast faction events to Discord via DiscordSRV. |
| `integrations.discordsrv.channel-id` | `""` | Discord channel ID; empty = DiscordSRV main linked channel. |
| `integrations.discordsrv.events.faction-created.enabled` | `true` | msg default: `**{faction}** was founded!` |
| `integrations.discordsrv.events.faction-disbanded.enabled` | `true` | msg: `**{faction}** was disbanded.` |
| `integrations.discordsrv.events.relation-ally.enabled` | `true` | msg: `:handshake: **{source}** and **{target}** are now allies!` |
| `integrations.discordsrv.events.relation-truce.enabled` | `true` | msg: `:white_flag: **{source}** and **{target}** agreed to a truce.` |
| `integrations.discordsrv.events.relation-enemy.enabled` | `true` | msg: `:crossed_swords: **{source}** declared war on **{target}**!` |

Each DiscordSRV event has `enabled` + `message` (placeholders `{faction}`, `{source}`, `{target}`).

---

## 2. `database.yml`

Changes require restart. `type: h2` (default) = embedded file DB in MySQL-compat mode (for Jaloquent upsert syntax); `type: mysql` = remote MySQL/MariaDB.

| Key | Default | Semantics |
|---|---|---|
| `type` | `h2` | `h2` or `mysql`. |
| `h2.file` | `data/factions` | Path relative to plugin data folder (`plugins/ReferenceFactions/`). |
| `mysql.host` | `localhost` | |
| `mysql.port` | `3306` | |
| `mysql.database` | `factions` | |
| `mysql.username` | `root` | |
| `mysql.password` | `""` | |
| `mysql.pool-size` | `10` | Hikari pool size. |
| `debug.jaloquent-logging` | `false` | Print every SQL query (needs SLF4J provider e.g. slf4j-jdk14). Keep false in prod. |

ORM layer is **Jaloquent**. War shields, audit entries, power history, invites, claims persist in DB and survive restarts.

---

## 3. `notifications.yml` — routing

Restart required for most changes.

| Key | Default | Semantics |
|---|---|---|
| `inbox.enabled` | `true` | Store missed faction notifications for offline players, deliver on join (`notifications.inbox-header`). |
| `inbox.max-per-login` | `20` | Max inbox entries delivered per login; excess discarded to avoid spam. |
| `member.notify-player-joined` | `true` | Notify faction members when a player joins (`member.player-joined`). |
| `economy.tax.notify-members` | `true` | Notify online members when bank tax is charged. |
| `ezcountdown.enabled` | `true` | Master toggle. When false OR EzCountdown absent, relation announcements are chat-only. |
| `ezcountdown.announcement-duration-seconds` | `8` | Duration of server-wide display notification. Must be > 0. |
| `ezcountdown.display-types` | `[ACTION_BAR]` | List of EzCountdown display types. Valid: `ACTION_BAR, BOSS_BAR, TITLE, CHAT, SCOREBOARD`. |

Per-player notification prefs (persisted DB columns) toggled via `/f notify [status|invites|territory|tax|all] [on|off]`. `TEAM_INVITE` notifications map to a `notify_invites` per-player column; all other TeamsAPI notification types always enabled.

---

## 4. `pre-defined.yml`

Predefined (server-owned template) factions. Disabled by default.

| Key | Default | Semantics |
|---|---|---|
| `enabled` | `false` | Master toggle. When false, all `/fa predefined` ops and predefined lookups → `predefined.disabled`. |
| `case-sensitive` | `false` | Whether predefined faction name matching is case-sensitive. |
| `block-disband` | `true` | Predefined factions cannot be disbanded (`predefined.disband-blocked`). |
| `factions` | `{}` | Map of predefined faction presets (populated by `/fa predefined ...` commands). Each stores claims (x,z per chunk) and a home location. |

Managed by `/fa predefined create|claim|sethome|reload|list` (permissions `factions.cmd.predefined.*`).

---

## 5. `roles.yml`

Global role defaults & limits. Per-faction override behavior gated by `roles.overrides.enabled`.

| Key | Default | Semantics |
|---|---|---|
| `roles.overrides.enabled` | `false` | When false, per-faction custom role overrides disabled; factions use global defaults only. Custom role/prefix editing requires this = true (error messages `role.create-disabled`, `role.prefix-disabled` cite it). |
| `roles.custom.enabled` | `true` | Master toggle for custom role management (`/f role ...`). |
| `roles.custom.min-priority` | `11` | Min priority for custom roles. Built-ins Owner/Officer/Member are fixed & protected below this. |
| `roles.custom.max-priority` | `99` | Max priority for custom roles. |
| `roles.custom.max-per-faction` | `8` | Max custom roles/faction. `0` = unlimited. |
| `roles.prefix.enabled` | `true` | Allow editing rank prefixes. |
| `roles.prefix.max-length` | `32` | Hard cap for prefix length (chars). `0` = unlimited. |
| `roles.defaults.owner.prefix` | `"<gold>[Owner]</gold>"` | Default Owner prefix at faction creation. |
| `roles.defaults.officer.prefix` | `"<yellow>[Officer]</yellow>"` | Default Officer prefix. |
| `roles.defaults.member.prefix` | `""` | Default Member prefix (empty). |

**Role authority:** built-in roles Owner > Officer > Member. Custom-role creation blocked if requested priority ≥ actor's own rank (`role.actor-rank-insufficient`). Priority must be within `[min-priority, max-priority]` (`role.priority-out-of-range`, min/max placeholders). Role authority enforced consistently across `/f promote`, `/f demote`, `/f leader`, `/f role assign`. Both `roles.custom.enabled: true` AND `roles.overrides.enabled: true` are required to actually create roles; both `roles.prefix.enabled: true` AND `roles.overrides.enabled: true` to edit prefixes.

---

## 6. `plugin.yml`

```
name: ReferenceFactions
main: com.reference.factions.ReferenceFactions
api-version: '1.21'
folia-supported: true
website: https://modrinth.com/plugin/the reference implementation
```
Authors: Shadow48402 (+ original MassiveCraft Factions authors: Madus, Cayorion, Ulumulu1510, MarkehMe, Brettflan).

**softdepend:** `TeamsAPI, Vault, WorldGuard, WorldEdit, PlaceholderAPI, Essentials, EssentialsX, dynmap, DiscordSRV, EzEconomy, EzCountdown, LWC, LWCX`.
**loadbefore:** `EzShops, EzAuction, EzRTP, EzClean`.

### 6.1 Commands

| Command | Aliases | Permission | Usage |
|---|---|---|---|
| `f` | `faction`, `factions` | (none at declaration; per-subcommand) | `/f <subcommand> [args]` |
| `fa` | `factionadmin` | `factions.admin` | `/fa <subcommand> [args]` |

### 6.2 Permission tree (node → default, description)

**Administrative container** — `factions.admin` (`op`): "Full administrative access to all `/fa` commands and bypasses." Children (all `true`): `factions.bypass`, `factions.cmd.claim.other`, `factions.cmd.disband.other`, `factions.cmd.shield`, `factions.cmd.flag.set`, `factions.cmd.audit`, `factions.cmd.admin.power` (+`.view/.set/.add/.remove/.reset/.freeze/.history`), `factions.cmd.chest` (+`.create/.delete`), `factions.cmd.role` (+`.list/.create/.edit/.delete/.assign`).

| Node | Default | Description |
|---|---|---|
| `factions.bypass` | `op` | Bypass faction territory protection (build/interact/PvP). |
| `factions.cmd.shield` | `op` | Set/clear a faction's daily war shield window. |
| `factions.cmd.power.history` | `true` | View your own power change history. |
| `factions.cmd.power.history.other` | `op` | View another player's power history. |
| `factions.cmd.admin.power` | `op` | Root for `/fa power`. |
| `factions.cmd.admin.power.view` | `op` | View target player power. |
| `factions.cmd.admin.power.set` | `op` | Set exact player power. |
| `factions.cmd.admin.power.add` | `op` | Add power. |
| `factions.cmd.admin.power.remove` | `op` | Remove power. |
| `factions.cmd.admin.power.reset` | `op` | Reset to configured max. |
| `factions.cmd.admin.power.freeze` | `op` | Toggle frozen state. |
| `factions.cmd.admin.power.history` | `op` | View power history via `/fa power history`. |
| `factions.cmd.create` | `true` | Create a faction. |
| `factions.cmd.disband` | `true` | Disband own faction. |
| `factions.cmd.disband.other` | `op` | Force-disband any faction. |
| `factions.cmd.rename` | `true` | Rename own faction. |
| `factions.cmd.desc` | `true` | Set own faction description. |
| `factions.cmd.motd` | `true` | View/set faction MOTD. |
| `factions.cmd.list` | `true` | List factions. |
| `factions.cmd.map` | `true` | View territory map. |
| `factions.cmd.notify` | `true` | Manage personal notification settings. |
| `factions.cmd.language` | `true` | View/change personal language. |
| `factions.cmd.gui` | `true` | Open GUI. |
| `factions.cmd.top` | `true` | Power leaderboard. |
| `factions.cmd.invite` | `true` | Invite/manage incoming invites. |
| `factions.cmd.invite.list` | `true` | List pending invites. |
| `factions.cmd.invite.revoke` | `true` | Revoke a sent invite. |
| `factions.cmd.join` | `true` | Join via pending invite. |
| `factions.cmd.flag` | `true` | View flags. |
| `factions.cmd.flag.set` | `true` | Toggle/set a flag (officer+ enforced in-game). |
| `factions.cmd.audit` | `op` | View audit log (officer+ enforced in-game). |
| `factions.cmd.leave` | `true` | Leave faction. |
| `factions.cmd.kick` | `true` | Kick member. |
| `factions.cmd.promote` | `true` | Promote one rank. |
| `factions.cmd.demote` | `true` | Demote one rank. |
| `factions.cmd.leader` | `true` | Transfer leadership. |
| `factions.cmd.role` | `op` | Root role-management. |
| `factions.cmd.role.list` | `op` | List roles. |
| `factions.cmd.role.create` | `op` | Create custom roles. |
| `factions.cmd.role.edit` | `op` | Edit role name/priority/prefix. |
| `factions.cmd.role.delete` | `op` | Delete custom roles. |
| `factions.cmd.role.assign` | `op` | Assign role to member. |
| `factions.cmd.relation` | `true` | Set/view relations. |
| `factions.cmd.merge` | `true` | Send/accept merge requests. |
| `factions.cmd.predefined` | `op` | Root predefined mgmt. Children (`true`): `.create/.claim/.sethome/.reload/.list`. |
| `factions.cmd.predefined.create` | `op` | Create predefined faction. |
| `factions.cmd.predefined.claim` | `op` | Save current chunk into a preset. |
| `factions.cmd.predefined.sethome` | `op` | Save current location as predefined home. |
| `factions.cmd.predefined.reload` | `op` | Reload `pre-defined.yml` cache. |
| `factions.cmd.predefined.list` | `op` | List predefined entries. |
| `factions.cmd.claim` | `true` | Claim land for own faction. |
| `factions.cmd.claim.other` | `op` | Force-(un)claim for any faction. |
| `factions.cmd.unclaim` | `true` | Unclaim own land. |
| `factions.cmd.home` | `true` | Teleport to faction home. |
| `factions.cmd.sethome` | `true` | Set/remove faction home. |
| `factions.cmd.warp` | `true` | Teleport to a warp. |
| `factions.cmd.setwarp` | `true` | Create/update/delete warps. |
| `factions.cmd.warp.password` | `true` | Set/clear warp password (officer+). |
| `factions.cmd.warp.cost` | `true` | Set per-use warp cost (officer+). |
| `factions.cmd.chest` | `true` | Open/view team chests. |
| `factions.cmd.chest.create` | `true` | Create chests (officer+). |
| `factions.cmd.chest.delete` | `true` | Delete chests (officer+). |
| `factions.cmd.bank` | `true` | View bank, deposit, withdraw. |
| `factions.cmd.bank.transfer` | `op` | Transfer to another faction bank. |
| `factions.cmd.bank.history` | `true` | View bank transaction history. |
| `factions.cmd.fly` | `true` | Toggle faction flight. |
| `factions.cmd.safezone` | `op` | Assign/remove safe-zone chunks. |
| `factions.cmd.warzone` | `op` | Assign/remove war-zone chunks. |

> **Doc-vs-plugin discrepancy:** `commands.md` references `factions.cmd.power` and `factions.cmd.power.buy` for `/f power` / `/f power buy`; these nodes are NOT declared in `plugin.yml`. The declared power-history player node is `factions.cmd.power.history` (+`.other`). Reimplement `/f power buy` gated by `power.buy.enabled` config + Vault (not a distinct perm) unless you choose to add explicit nodes. Also `/f powerhistory`/`phist` alias is documented but not a distinct plugin.yml command (dispatched as an `/f` subcommand).

---

## 7. Command surface (from `/docs/commands.md`, not fully in plugin.yml)

### 7.1 Player `/f` subcommands
`help`, `create <name>`, `disband` (owner only; 30s re-run confirm), `info [name]` (alias `show`), `list [page]`, `top [page] [sort]` (sort: power|land|bank), `join [factionName]` (no args → clickable pending-invite list), `leave`, `kick <player>`, `promote <player>`, `demote <player>`, `leader <player> [confirm]`, `rename <name>`, `desc <text...>`, `motd <text...>|clear`, `home`, `sethome`, `unsethome [confirm]`, `fly`, `map [on|off|once]`, `notify [status|invites|territory|tax|all] [on|off]`, `gui [menu]`, `language [code|reset]`, `power`, `power buy <amount>`, `powerhistory [player] [page]` (alias `phist`).

**Claim modes:** `/f claim`, `claim one`, `claim auto [on|off]`, `claim square [radius]`, `claim circle [radius]`, `claim fill`, `claim nearby [radius]`, `claim at <chunkX> <chunkZ>`. **Unclaim:** `/f unclaim`, `unclaim one`, `unclaim auto [on|off]`, `unclaim square [radius]`, `unclaim circle [radius]`, `unclaim fill`, `unclaim all [confirm]`.

**Invite:** `/f invite <player>`, `invite list [faction]`, `invite revoke <player>`, `invite accept <faction>`, `invite decline <faction>`, `invite declineall`.

**Relation:** `/f relation <faction> <ally|truce|neutral|enemy>`, `relation list [type]`, `relation wishes`. Self-target blocked. Two-sided handshake: setting a relation posts a "wish"; mutual confirmation required (ally/truce/enemy) — `relation.pending-wish`/`pending-received`/`mutual-established`. neutral can be unilateral downgrade.

**Merge:** `/f merge send <faction>`, `merge accept <faction>` (officer+).

**Bank** (needs Vault): `/f bank`, `bank deposit <amount>`, `bank withdraw <amount>`, `bank transfer <faction> <amount>`, `bank history [page]`.

**Audit:** `/f audit [page]`, `/f audit [page] --action=<action>`. Actions: `claim, unclaim, relation-change, kick, promote, demote, bank-deposit, bank-withdraw, bank-transfer, merge-request, merge-accept`. Newest-first. Officers+ only for own faction; invalid `--action` shows valid list.

**Warp:** `/f warp <name> [password]`, `warp set <name> [here|x y z world]`, `warp delete <name>`, `warp list [page]`, `warp password <name> [password|clear]` (officer+), `warp cost <name> <amount>` (officer+, Vault charge before teleport, insufficient balance blocks).

**Chest:** `/f chest` (opens `default`), `chest open <name>`, `chest list`, `chest create <name>` (officer+), `chest delete <name>` (officer+).

**Role:** `/f role list|create|rename|setpriority|setprefix|delete|assign`.

### 7.2 Admin `/fa` subcommands
`help`, `bypass` (toggle protection bypass; `admin.bypass-enabled`/`disabled`), `reload` (`admin.reload`), `disband <faction>`, `claim <faction> [one|square|circle|fill] [radius]`, `unclaim <faction> [all|one|square|circle|fill] [radius]`, `safezone [one|square|circle|remove] [radius]`, `warzone [one|square|circle|remove] [radius]`, `flag <faction> <flag> [on|off]` (bypasses `player-editable`; `flag.admin-override`), `shield <faction> <start-hour> <duration-hours>` / `shield <faction> clear`, `audit <faction> [page] [--action=<action>]`, `power view|set|add|remove|reset|freeze|history <player> ...`.

`/fa power set|add|remove <player> <amount> <reason...>`, `reset <player> <reason...>`, `freeze <player> <on|off>`, `history <player> [page]`.

---

## 8. `gui.yml` — layout semantics

Root:
| Key | Default | Semantics |
|---|---|---|
| `gui.enabled` | `true` | Master toggle. |
| `gui.default-menu` | `main` | Menu opened by bare `/f` / `/f gui`. |
| `gui.language-menu` | `language` | Menu id used for the language selector. |
| `gui.menus.<id>.title` | — | MiniMessage inventory title (supports gradients). |
| `gui.menus.<id>.size` | — | Inventory slot count (multiple of 9, ≤54). |
| `gui.menus.<id>.items.<itemId>` | — | Item definition map. |

**Item keys:** `slot` (int), `material` (Bukkit material name), `name` (MiniMessage + placeholders), `lore` (list of MiniMessage lines), `glow` (bool → enchant glint), `action`, plus action-specific: `command` (for RUN_COMMAND/SUGGEST_COMMAND), `menu` (for OPEN_MENU), `locale` (for LANGUAGE_SET).

**Actions (complete set):** `RUN_COMMAND`, `SUGGEST_COMMAND`, `OPEN_MENU`, `REFRESH`, `CLOSE`, `LANGUAGE_SET`, `LANGUAGE_RESET`. (The config doc only lists the first five; `LANGUAGE_SET`/`LANGUAGE_RESET` are real and used by the `language` menu.)

**Item placeholders:** `{player}`, `{faction}`, `{faction_members}`, `{faction_land}`, `{faction_bank}`, `{power}`, `{max_power}`, `{language_current}`, `{language_default}`, `{language_available}`.

### 8.1 Shipped `main` menu (size 54, title gradient `#f6d365→#fda085` "Factions Control")

| Item | Slot | Material | Action | Target |
|---|---|---|---|---|
| info | 10 | BOOK | RUN_COMMAND | `/f info` |
| map | 12 | FILLED_MAP | RUN_COMMAND | `/f map` |
| claim | 14 | GRASS_BLOCK | RUN_COMMAND | `/f claim` |
| unclaim | 16 | BARRIER | RUN_COMMAND | `/f unclaim` |
| invites | 28 | WRITABLE_BOOK | RUN_COMMAND | `/f join` |
| home | 30 | LODESTONE | RUN_COMMAND | `/f home` |
| warps | 32 | ENDER_PEARL | RUN_COMMAND | `/f warp list` |
| top | 34 | NETHER_STAR | RUN_COMMAND | `/f top` |
| language | 46 | GLOBE_BANNER_PATTERN | OPEN_MENU | `language` |
| your-power | 49 | PLAYER_HEAD (glow) | REFRESH | — |
| close | 53 | STRUCTURE_VOID | CLOSE | — |

### 8.2 Shipped `language` menu (size 54, title gradient `#84fab0→#8fd3f4`)

`language-status` slot 4 (GLOBE_BANNER_PATTERN, REFRESH). Locale buttons (action `LANGUAGE_SET`, `locale` field): en=19 (WHITE_BANNER), es=20 (RED_BANNER), de=21 (BLACK_BANNER), fr=22 (BLUE_BANNER), pt-BR=23 (GREEN_BANNER), zh=24 (RED_BANNER), ru=25 (BLUE_BANNER), ja=31 (WHITE_BANNER). `language-reset` slot 49 (FEATHER, LANGUAGE_RESET). `back` slot 45 (ARROW, OPEN_MENU→main). `close` slot 53 (STRUCTURE_VOID, CLOSE).

Errors: `custom.gui.menu-not-found` (`{menu}`), `custom.gui.menu-not-configured` (`{menu}`), `custom.gui.suggested-command` (`{command}`).

---

## 9. `messages_en.yml` — complete key inventory

Root `prefix: "<gray>[<gold>Factions</gold>]</gray> "`. 27 top-level groups. Exact English strings + placeholder tokens below. (`{...}` = placeholder; MiniMessage tags omitted from the token list but present in values.) The rewrite must ship equivalent messages in all 8 locales with identical keys.

### 9.1 `general.*`
`no-permission` "You don't have permission to do that." · `player-only` "This command can only be run by a player." · `invalid-args` "Usage: {usage}" · `unknown-subcommand` "Unknown sub-command. Try /f help." · `faction-not-found` "Faction {name} not found." · `player-not-found` "Player {name} not found." · `not-in-faction` "You are not in a faction." · `must-be-leader` "Only the faction leader can do that." · `must-be-officer` "Only officers or above can do that." · `economy-disabled` "The economy integration is not available." · `cancelled` "Action cancelled." · `unknown-subcommand-detailed` "Unknown command '{input}'. " · `help-hover` "Click to view all faction commands".

### 9.2 `help.*`
`title`, `start-here`, `start-step-1..4` (create/invite/claim/sethome steps), `separator`, `section.core|faction-setup|members-and-invites|land-and-navigation|economy-and-utility`, `officer-title`, `officer-line-1..3`, `admin-title`, `tip-notify`, `entry-line` "{usage} - {description}", `entry-hover` "{description}...Click to suggest command".

### 9.3 `faction.*`
`created` "Faction {name} created." · `name-taken` · `name-too-short` "at least 3 characters." · `name-too-long` "at most 32 characters." · `name-invalid` "letters, digits, underscores, and hyphens." · `disbanded` "{name}" · `disband-confirm` "...Run the command again within 30 seconds to confirm." · `already-in-faction` · `cost-to-create` "{cost}...{balance}" · `not-enough-money-create` "{cost}".

**Name rules (load-bearing):** min 3, max 32 chars; regex allows letters, digits, `_`, `-` only.

### 9.4 `member.*`
`joined` "{faction}" · `player-joined` "{player}" · `left` "{faction}" · `player-left` "{player}" · `kicked` "{faction}/{kicker}" · `player-kicked` "{player}/{kicker}" · `not-member` "{player}" · `cannot-kick-self` · `cannot-kick-leader`.

### 9.5 `invite.*`
`sent` "{player}" · `received` "{player}/{faction}" · `summary` "{count}" · `summary-line` "{faction}/{inviter}" · `accepted` "{faction}" · `declined` "{faction}" · `expired` "{faction}" · `already-invited` "{player}" · `not-invited` · `no-invite-pending` · `received-short` "{faction}" · `accept-hover` "{faction}" · `decline-hover`.

### 9.6 `claim.*`
`claimed` "{faction}" · `unclaimed` · `unclaimed-all` "{faction}" · `already-claimed` "{faction}" · `not-your-land` · `not-enough-power` · `no-border` "...borders your existing territory or the wilderness." · `cannot-claim-safezone` · `cost-per-chunk` "{cost}/{balance}" · `not-enough-money-claim` "{cost}" · `overclaimed` "{faction}" · `overclaimed-victim` "{attacker}/{remaining}" · `enemy-not-raidable` "{faction}" · `enemy-offline-protected` "{faction}" · `shield-active` "{faction}" · `enter-faction` "{faction}" · `enter-wilderness` · `enter-safezone` · `enter-warzone`.

### 9.7 `bank.*`
`balance` "{balance}" · `deposited` "{amount}/{balance}" · `withdrew` "{amount}/{balance}" · `transferred` "{amount}/{from}/{to}" · `insufficient-funds` "{balance}" · `invalid-amount` · `tax-charged` "{amount}/{balance}".

### 9.8 `home.*`
`set` · `teleporting` · `teleported` · `teleport-failed` · `no-home` · `set-in-own-territory` · `jailed` "You cannot teleport home while jailed.".

### 9.9 `warp.*`
`set` "{name}" · `deleted` "{name}" · `not-found` "{name}" · `teleporting` "{name}" · `teleported` "{name}" · `teleport-failed` · `limit-reached` "{max}" · `set-in-own-territory` · `jailed`.

### 9.10 `flag.*`
`list-header` "{faction}" · `entry-on` "{flag}/{edit_note}" · `entry-off` "{flag}/{edit_note}" · `set-on` "{flag}" · `set-off` "{flag}" · `invalid` "{flag}...Valid: pvp, friendly-fire, explosions, fire-spread, open" · `not-editable` "{flag}" · `officer-required` · `admin-override` "{flag}/{faction}".

### 9.11 `relation.*`
`set` "{faction}/{relation}" · `updated` "{faction}/{relation}" · `cannot-set-self` · `set-failed` · `pending-wish` "{relation}/{faction}" · `pending-received` "{faction}/{relation}" · `mutual-established` "{relation}/{faction}" · `mutual-established-target` "{faction}/{relation}" · `updated-by-other` "{faction}/{relation}".

### 9.12 `predefined.*`
`disabled` · `unknown` "{faction}" · `create-not-allowed` · `disband-blocked` · `claim-saved` "{faction}/{x}/{z}" · `home-saved` "{faction}" · `reload-success` · `reload-failed` · `none`.

### 9.13 `power.*`
`too-low-raid` · `lost-on-death` "{amount}" · `kill-gained` "{amount}" · `buy-success` "{amount}/{cost}" · `buy-disabled` · `buy-no-vault` · `buy-invalid-amount` "{max}" · `buy-already-max` · `buy-insufficient-funds` "{cost}" · `death-streak-penalty` "{streak}/{amount}" · `history-empty` "{name}" · `history-console-usage` · `history-header` "{name}/{page}" · `history-entry-gain` "{time}/{reason}/{delta}/{power_after}" · `history-entry-loss` "{time}/{reason}/{delta}/{power_after}" · `history-storage-error` · `blocked-frozen` "{source}" · `staff-blocked-frozen` "{player}/{source}" · `change-actor` "{before}/{after}/{delta}" · `change-faction` "{player}/{delta}/{after}" · `change-staff` "{player}/{delta}/{before}/{after}/{source}/{reason}/{world}/{zone}" · `admin-view` "{player}/{power}/{frozen}" · `admin-set-success` "{player}/{before}/{after}" · `admin-add-success` "{delta}/{player}/{before}/{after}" · `admin-remove-success` "{delta}/{player}/{before}/{after}" · `admin-reset-success` "{player}/{before}/{after}" · `admin-freeze-success` "{player}/{state}".

### 9.14 `raidable.*`
`became-raidable` "⚠ Your faction is now raidable! Enemies can overclaim your land." · `no-longer-raidable` "✔..." · `server-announce` "{faction}" · `server-announce-recovered` "{faction}".

### 9.15 `fly.*`
`enabled` · `disabled` · `left-territory` · `enemy-nearby`.

### 9.16 `lwc.*`
`stale-protection-removed`.

### 9.17 `info.*`
`header` "{name}" · `leader` "{leader}" · `members` "{count}/{max}" · `power` "{power}/{max_power}" · `land` "{land}" · `balance` "{balance}" · `home` "{home}" · `relation` "{relation}" · `allies` "{allies}" · `description` "{description}".

### 9.18 `admin.*`
`bypass-enabled` · `bypass-disabled` · `reload` "Configuration reloaded.".

### 9.19 `shield.*`
`set` "{faction}/{start}/{duration}" ("{start}:00 UTC for {duration}h") · `cleared` "{faction}" · `feature-disabled` · `invalid-hour` "Start hour must be 0–23." · `invalid-duration` "Duration must be 1–{max} hours.".

### 9.20 `notifications.*`
`inbox-header` "{count}".

### 9.21 `update.*`
`available` "{current}/{latest}/{source}" · `url` "{url}" (clickable open_url).

### 9.22 `ezcountdown.*`
`relation-enemy` "{source}/{target}" · `relation-ally` "{source}/{target}" · `relation-truce` "{source}/{target}".

### 9.23 `merge.*`
`disabled` · `request-sent` "{faction}" · `request-received` "{faction}" · `accepted` "{faction}" · `merged-into` "{faction}" · `already-requested` "{faction}" · `no-request-found` "{faction}" · `self-merge` · `target-not-found` "{faction}".

### 9.24 `language.*`
`system-unavailable` · `profile-load-failed` · `reset-success` · `invalid-code` "{code}" · `set-success` "{code}" · `save-failed` · `current` "{code}" · `default` "{code}" · `available` "{list}" · `usage` · `override-disabled`.

### 9.25 `custom.*` (nested feature copy)
- `custom.protection.no-break` / `no-place` / `friendly-fire-disabled` / `pvp-safezone` / `pvp-territory`.
- `custom.move.enter-claimed` "{faction}" / `enter` "{territory}" / `info-leader` "{value}" / `info-members` "{value}" / `info-land` "{value}" / `info-power` "{value}".
- `custom.warp.none` / `header` / `header-page` "{page}" / `world-not-loaded` / `set-protected` / `set-failed`.
- `custom.home.set-protected` / `set` / `set-failed`.
- `custom.fly.disabled-global` / `own-territory-required`.
- `custom.power.usage-buy` "Usage: /f power buy <amount>".
- `custom.notify.usage` "Usage: /f notify [status|invites|territory|tax|all] [on|off]" / `unknown-type` / `updated` "{type}/{value}" / `update-failed` / `status-header` / `status-line` "{type}/{value}".
- `custom.member.owner-cannot-leave` / `leave-failed` / `leader-confirm-self` "{name}" / `leader-transferred` "{name}" / `leader-transfer-failed` / `promoted` "{name}" / `promote-failed` / `kick-actor` "{player}".
- `custom.faction.disbanded` / `disband-failed` / `desc-too-long` "max 250 chars" / `desc-updated` / `desc-update-failed`.
- `custom.list.separator` / `none` / `header` "{page}/{sort}" / `row` "{rank}/{name}/{members}/{land}/{bank}".
- `custom.top.header` "{page}/{sort}" / `row` "{rank}/{name}/{power}/{land}/{bank}".
- `custom.invite.target-already-in-faction` / `sent` "{player}" / `already-pending`.
- `custom.gui.menu-not-found` "{menu}" / `suggested-command` "{command}" / `menu-not-configured` "{menu}".
- `custom.merge.help-title` / `help-send` / `help-accept`.
- `custom.role.list-header` / `list-entry` "{name}/{priority}/{prefix}" / `invalid-priority` / `create-success` "{name}/{priority}" / `create-failed` / `create-disabled` (cites `roles.custom.enabled` + `roles.overrides.enabled`) / `priority-out-of-range` "{min}/{max}" / `actor-rank-insufficient` / `name-taken` "{name}" / `limit-reached` / `rename-success` "{old}/{new}" / `rename-failed` / `priority-success` "{name}/{priority}" / `priority-failed` / `prefix-success` "{name}" / `prefix-failed` / `prefix-disabled` (cites `roles.prefix.enabled` + `roles.overrides.enabled`) / `delete-success` "{name}" / `delete-failed` / `assign-success` "{role}/{player}" / `assign-failed`.

### 9.26 `map.*`
Present as a top-level key but **empty/null** in `messages_en.yml` (file ends with `map:` and no children). Reimplementation should keep the group (map rendering likely uses inline/hardcoded strings + hover). Treat as reserved.

> **Locale parity:** each of `de/es/fr/ja/pt-BR/ru/zh` carries the same 27 top-level groups (verified: de mirrors en group structure). Line counts differ only due to translated text wrapping. Ship all keys in every bundle; missing keys fall through to `en` then inline fallback.

---

## 10. PlaceholderAPI expansion (`reference`)

Registered when PlaceholderAPI present + `integrations.placeholderapi: true`.

| Placeholder | Returns | Not-in-faction default |
|---|---|---|
| `%fable_faction_name%` | faction name | `None` |
| `%fable_faction_power%` | summed member power (integer string) | `0` |
| `%fable_faction_members%` | member count | `0` |
| `%fable_faction_land%` | claimed chunk count | `0` |
| `%fable_faction_bank%` | bank balance (numeric string) | `0.0` |
| `%fable_player_power%` | current player power | — |
| `%fable_player_role%` | role name | `None` |
| `%fable_player_role_prefix%` | role prefix | empty string |

Unresolvable → empty value.

---

## 11. TeamsAPI integration (soft-depend, optional; providers registered reflectively at startup)

Plugin runs standalone; registers providers when TeamsAPI detected. Version gates: subcommand API 1.5.0+; relation service 1.6.0+; SafeZone/WarZone + notification service 1.7.0+; power history 1.8.0+; chest service 2.3.0+; custom role/prefix service 2.4.0+.

- **Subcommand dispatch:** unrecognized `/f <name>` routed to registered `TeamsSubcommand` (case-insensitive name match). Permission check → no-perm message + stop; `execute()==false` → usage hint. Tab-completion appends subcommand names at position 1.
- **ClaimTerritoryType:** `WILDERNESS, TEAM, SAFE_ZONE, WAR_ZONE`. SafeZone = no PvP + no power loss; WarZone = always contested. `TeamClaim.getOwningTeamId()` = `Optional.empty()` for safe/war zones (admin-owned).
- **Notification service:** `TEAM_INVITE` ↔ `notify_invites` per-player column; all other types always enabled. Custom string types delivered but not persisted.
- **Power history service:** entries carry entryId, playerUUID, type (`GAIN`/`LOSS`), delta (signed), reason (upper-case e.g. `KILL/DEATH/PASSIVE`), occurredAt. teamId/actorUUID/details always empty in this schema. `updatePowerHistoryEntry` always returns false. Player-facing view = `/f powerhistory [player] [page]` (alias `phist`).

---

## 12. WorldGuard region-sync mode (`integrations.worldguard-sync-regions: true`)

Mirrors each claimed chunk as a WG `ProtectedCuboidRegion`. Event priority scheme:
- WG evaluates at `NORMAL`. Faction members added as WG domain members (their actions pass).
- Protection engine at `HIGH` with `ignoreCancelled=true`: for enemies, WG already cancelled → no DB query.
- Allies NOT added to WG regions; a `HIGHEST ignoreCancelled=false` handler un-cancels their events after a lightweight DB check.
- Safezone/warzone chunks → WG regions with no members (WG denies all building).
- Startup: all claimed chunks registered. Claim/unclaim/join/leave/disband keep regions current. Restart required to toggle.

---

## 13. Cross-feature interactions & edge cases

- **Power tick engine** (`tick-interval-seconds`, default 60s): applies online/offline regen per source amounts; recomputes faction max-land (respecting `inactive-exclusion`); drives raidable-state transitions (F4 broadcasts) and shield evaluation.
- **Grace period** (`grace-period-seconds`=3600): no death power-loss for the first hour after server start.
- **Death loss** only in enemy or war-zone territory; scaled by `multipliers.worlds[world]` × `multipliers.zones[zone]`; escalated by `death-streak` (streak 0 = free); source gated by `sources.death-loss.enabled`; clamped by `constraints.*`.
- **Kill gain** gated by `gain-on-kill.enabled` + `sources.kill-gain.enabled`; optionally ratio-scaled (F3) between `min-factor`..`max-factor`.
- **Freeze**: frozen players blocked from automatic/regen sources per `freeze.blocks-automatic`/`blocks-regen`; admin commands bypass if `allow-admin-bypass`. Blocked events emit `power.blocked-frozen` (actor) / `power.staff-blocked-frozen` (staff).
- **Disband** clears claims, warps, invites, membership links, and WG regions.
- **Merge accept** transfers claims+warps+bank+members then disbands sender.
- **Jailed players** (EssentialsX): `/f home` and `/f warp` blocked (`home.jailed` / `warp.jailed`).
- **Leadership transfer** self-target requires `/f leader <name> confirm` re-affirm (`custom.member.leader-confirm-self`).
- **Disband confirm:** re-run `/f disband` within 30s.
- **Description** max 250 chars (`custom.faction.desc-too-long`).
- **Overclaim** ignores border adjacency; normal claim requires bordering own territory or wilderness (`claim.no-border`) and cannot target safe/war zones.
- **Buffer-zone**: min chunk gap between enemy factions' territory when > 0.
- **Relations two-sided:** ally/truce/enemy need mutual confirmation (wish system); relation data feeds territory+combat protection and PvP flags.

---

## 14. Known doc-vs-resource discrepancies (reimplement per RESOURCE, note the docs)

1. `factions.max-members` shipped `50`; docs say default `0`=unlimited. Support `0`=unlimited semantics; ship `50`.
2. `factions.updates.enabled`/`notify-ops-on-join` shipped `true`; reference doc says default `false`. Ship `true`.
3. `factions.land.per-power` (default `1.0`) and `factions.land.max` (default `500`) are read by the implementation but absent from shipped `config.yml`. Add them (or honor as defaults).
4. `factions.map.once-radius` shipped `3`; doc example shows `2`. Ship `3`.
5. `factions.cmd.power` / `factions.cmd.power.buy` referenced in docs but not declared in `plugin.yml`.
6. GUI actions `LANGUAGE_SET`/`LANGUAGE_RESET` and item fields `locale`/`glow` are real but omitted from `configuration/gui.md`.
7. `messages_en.yml` `map:` group is empty; `custom.notify` usage mentions `tax` type while `/f notify` doc omits explanation — support types: `status, invites, territory, tax, all`.
8. `notify` per-player types persisted: `notify_invites` (and territory/tax) columns.
</content>
</invoke>
