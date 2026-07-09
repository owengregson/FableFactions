# FableFactions

**Territory, power, and relations for Minecraft servers — one jar, PaperSpigot 1.7.10 through Paper/Folia 26.x.**

[![CI](https://img.shields.io/github/actions/workflow/status/owengregson/FableFactions/ci.yml?branch=main&label=CI)](https://github.com/owengregson/FableFactions/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/owengregson/FableFactions?include_prereleases&label=release)](https://github.com/owengregson/FableFactions/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Modrinth](https://img.shields.io/badge/Modrinth-FableFactions-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/plugin/fablefactions)

## Why FableFactions

Most factions plugins ship a build per Minecraft era and treat Folia as an afterthought. FableFactions ships **one Multi-Release jar** that boots unmodified on Spigot, Paper, and PaperSpigot from 1.7.10 up through Paper 26.1.2 — and on **Folia** (`folia-supported: true`) with the identical feature set, not a degraded mode.

Under the hood it is engineered like a database, not a listener pile:

- **All game state lives in one immutable snapshot** with a single writer thread. Protection checks (build, interact, PvP) are wait-free, allocation-free reads on the event thread — they never block, never lock, never touch storage.
- **A crash-safe write-ahead journal** with group-commit fsync backs every mutation. When a player sees a bank, chest, or disband confirmation, it is already durable on disk — confirmations never lie, even through a hard kill.
- **Version differences are resolved once at boot** via capability probes, never by per-event reflection. The same threading model runs on Spigot, Paper, and Folia.

Full design details are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## ✨ Features

| | |
|---|---|
| **Territory & map** | Chunk claiming/unclaiming, in-chat territory map (`/f map`), safe zones and war zones, dynmap rendering |
| **Power & raiding** | Per-player power with death penalties, overclaim of weakened factions, daily **war shield** windows, power leaderboard (`/f top`), full admin power tooling with change history |
| **Relations** | Ally, truce, neutral, enemy — with relation-aware protection, chat tags, and map coloring |
| **Bank & economy** | Vault-backed faction bank: deposit, withdraw, transfer, transaction history — every entry journaled before it is confirmed |
| **Faction chests** | Virtual shared chests, create/delete gated to officers, contents crash-safe |
| **Homes & warps** | Faction home plus named warps with **teleport warmups**, optional per-warp **passwords**, and per-use **costs** |
| **Combat & flight** | Combat tagging and faction flight (`/f fly`) inside own territory |
| **GUI** | Menu-driven interface (`/f gui`), layouts configurable in `gui.yml` |
| **Roles & flags** | Custom roles with priorities and chat prefixes, per-faction flags, faction audit log |
| **Merges** | Full faction merges via request/accept flow — members, land, and bank combined atomically |
| **Predefined factions** | Server-defined presets (spawn factions, staff factions) managed in `pre-defined.yml` |
| **Localization** | Fully externalized message catalog (MiniMessage) with a per-player `/f language` seam and a locale waterfall. English ships in v1.0.0-beta.1; additional translations are planned once they can be kept in parity |
| **Integrations** | Vault, WorldGuard, WorldEdit, PlaceholderAPI, Essentials/EssentialsX, dynmap, LWC/LWCX, DiscordSRV, EzCountdown, TeamsAPI · bStats metrics · update checker |

## 🧩 Compatibility

**One jar. No per-version builds.** Every entry below is a live-server lane in CI, driven by [`support-matrix.json`](support-matrix.json) — the single source of truth for supported versions:

| Minecraft version | Platform | Tested on JDK |
|---|---|---|
| 1.7.10 | PaperSpigot | 8 |
| 1.8.8 | PaperSpigot | 8 |
| 1.9.4 | Paper | 21 |
| 1.10.2 | Paper | 21 |
| 1.11.2 | Paper | 21 |
| 1.12.2 | Paper | 21 |
| 1.13.2 | Paper | 13 |
| 1.14.4 | Paper | 13 |
| 1.15.2 | Paper | 14 |
| 1.16.5 | Paper | 16 |
| 1.17.1 | Paper | 17 |
| 1.18.2 | Paper | 17 |
| 1.19.4 | Paper | 17 |
| 1.20.6 | Paper | 25 |
| 1.21.4 | Paper | 25 |
| 1.21.11 | Paper | 25 |
| 26.1.2 | Paper | 25 |
| 26.1.2 | **Folia** | 25 |

Folia is fully supported with the identical feature set — there is no reduced "Folia mode".

## 🚀 Install & Quick Start

**Requirements**

- A Spigot, Paper, PaperSpigot, or Folia server, Minecraft 1.7.10–26.x
- Java 8 or newer (whatever your server version already requires)
- Optional: [Vault](https://www.spigotmc.org/resources/vault.34315/) + an economy plugin for the faction bank

**Setup**

1. Download `FableFactions-<version>.jar` and drop it into `plugins/`.
2. Start the server. First boot generates:
   - `config.yml` — gameplay settings (power, claiming, warmups, combat tag, flight…)
   - `database.yml` — storage backend
   - `gui.yml`, `roles.yml`, `pre-defined.yml`, `notifications.yml`
   - `messages/` — the 8 locale files
3. Play. Storage defaults to **embedded H2** (zero setup). For **MySQL/MariaDB**, set `type: mysql` and fill in the `mysql:` block in `database.yml`, then restart.

## ⌨️ Commands & Permissions

Everything lives under `/f` (aliases: `/faction`, `/factions`) with per-subcommand permissions, plus `/fa` (alias: `/factionadmin`) for administration.

| Group | Subcommands | What it does |
|---|---|---|
| Faction lifecycle | `create` `disband` `rename` `desc` `motd` | Create and manage your faction |
| Membership | `invite` `join` `leave` `kick` `promote` `demote` `leader` | Invites, ranks, leadership transfer |
| Territory | `claim` `unclaim` `map` | Claim land, view the territory map |
| Homes & warps | `home` `sethome` `warp` `setwarp` | Teleports with warmups, warp passwords and costs |
| Bank | `bank` | Deposit, withdraw, transfer, history |
| Chests | `chest` | Open, create, delete faction chests |
| Diplomacy | `relation` `merge` | Ally/truce/neutral/enemy, faction merges |
| Roles & flags | `role` `flag` `audit` | Custom roles, faction flags, audit log |
| Utility | `fly` `gui` `list` `top` `notify` `language` | Flight, menus, leaderboards, personal settings |
| Server zones | `safezone` `warzone` `shield` `predefined` | Protected zones, war shields, preset factions (op) |

`/fa` covers administration: force-claim and force-disband any faction (`factions.cmd.claim.other`, `factions.cmd.disband.other`), the full player-power toolkit (`/fa power` view/set/add/remove/reset/freeze/history), war shield windows, custom role management, and chest administration.

**Permission roots**

- `factions.cmd.<subcommand>` — one node per subcommand (most player commands default to `true`)
- `factions.admin` — the `/fa` tree and all admin children (default: op)
- `factions.bypass` — bypass territory protection (default: op)

The exact tree, including defaults, is defined in [`core/src/main/resources/plugin.yml`](core/src/main/resources/plugin.yml); see [docs/](docs/) for the full reference.

## 🔌 PlaceholderAPI

When PlaceholderAPI is installed, FableFactions registers the `fable` expansion — all placeholders render as `%fable_<param>%`:

`%fable_faction_name%` · `%fable_faction_power%` · `%fable_faction_members%` · `%fable_faction_land%` · `%fable_faction_bank%` · `%fable_player_power%` · `%fable_player_role%` · `%fable_player_role_prefix%`

## 🛠 For Developers

The public API entry point is `FableFactionsApi`: read game state through immutable **snapshot views**, submit mutations through the **request pipeline**, and listen to **7 Bukkit events** (`FactionCreateEvent`, `FactionDisbandEvent`, `FactionJoinEvent`, `FactionLeaveEvent`, `FactionChunkClaimEvent`, `FactionChunkUnclaimEvent`, `FactionBankTransactionEvent`).

**Building from source**

```sh
git clone https://github.com/owengregson/FableFactions.git
cd FableFactions
./gradlew build
```

That is the whole setup: the Gradle 9.5.1 wrapper auto-provisions every JDK it needs via the foojay toolchain resolver — you only need a Java install capable of launching Gradle. `./gradlew build` produces `core/build/libs/FableFactions-<version>.jar` and runs the full verification-gate suite plus unit, property, and architecture tests. Live-server integration lanes run via `./gradlew integrationTest` (PR smoke) and `./gradlew integrationTestMatrix` (all 18 matrix entries).

See [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution workflow and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) / [docs/CONTRACTS.md](docs/CONTRACTS.md) for the normative internal specs.

## 🐛 Support & Bug Reports

Found a bug or have a feature request? Open an issue at [github.com/owengregson/FableFactions/issues](https://github.com/owengregson/FableFactions/issues). Please include your server platform, Minecraft version, and the plugin version (`1.0.0-beta.1` is the current first beta).

## License

[MIT](LICENSE) © 2026 Owen Gregson
