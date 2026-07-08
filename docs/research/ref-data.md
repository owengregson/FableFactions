# the reference implementation — Data Layer Specification

Clean-room reimplementation spec for the persistence layer: entities/tables, repository
query semantics, ORM usage, connection management, H2/MySQL differences, schema
migration, chunk-claim encoding, caching, async/threading, and shutdown behavior.

Source package scope: `com.reference.factions.data` (`.model`, `.repository`),
`com.reference.factions.data.DatabaseManager`, `.data.Repositories`,
`com.reference.factions.update.UpdateNotificationManager`, `resources/database.yml`,
plus the Jaloquent ORM (`com.github.EzFramework:Jaloquent:1.3.0` +
`JavaQueryBuilder:1.2.0`, shaded/relocated to `com.reference.lib.jaloquent` /
`com.reference.lib.javaquerybuilder`).

---

## 1. ORM overview (Jaloquent + JavaQueryBuilder)

The plugin uses **Jaloquent**, a small active-record ORM, over a JDBC `DataSource`.
Key types (all relocated at build time under `com.reference.lib.*`):

| Jaloquent type | Role |
|---|---|
| `com.github.ezframework.jaloquent.model.Model` | Base class for every entity. Stores attributes in an internal map keyed by column name; **no typed fields on subclasses**. Constructed with a string `id` (the primary key). |
| `Model.set(String col, Object val)` | Set an attribute (staged for the next INSERT/UPSERT). |
| `Model.getAs(String col, Class<T> type, T default)` | Read an attribute, coerced to `type`, returning `default` when absent/null. |
| `Model.getId()` | Returns the primary-key string. |
| `com.github.ezframework.jaloquent.model.ModelRepository<T>` | Base repository. Constructed with `(DataSourceJdbcStore store, String prefix, BiFunction<String,Map,T> factory)`. |
| `ModelRepository.find(String id) → Optional<T>` | Point lookup by primary key. |
| `ModelRepository.query(Query) → List<T>` | Run a built query, hydrate rows into models. |
| `ModelRepository.save(T model)` | **Upsert** (INSERT … ON DUPLICATE KEY UPDATE) keyed by `id`. Write-through, synchronous JDBC. |
| `ModelRepository.delete(String id)` | Delete row by primary key. |
| `ModelRepository.deleteWhere(String col, Object val)` | Bulk delete by column equality. |
| `com.github.ezframework.jaloquent.model.TableRegistry` | Static registry mapping a *prefix* → (table name, column-def map). Each repository calls `TableRegistry.register(prefix, tableName, COLUMNS)` in its constructor. Drives Jaloquent's INSERT column list. |
| `com.github.ezframework.javaquerybuilder.query.builder.QueryBuilder` | Fluent query builder. Used methods: `new QueryBuilder().whereEquals(col, val).build()` and `new QueryBuilder().build()` (select all). |
| `com.github.ezframework.jaloquent.store.sql.DataSourceJdbcStore` | JDBC execution backend wrapping a `javax.sql.DataSource`. Method `executeUpdate(String sql, List<Object> params)` is the extension point (overridden for H2, see §7). |
| `com.github.ezframework.jaloquent.config.JaloquentConfig.enableLogging(boolean)` | Global toggle for SQL query logging (needs an SLF4J provider on classpath). |
| `com.github.ezframework.jaloquent.exception.StorageException` | Checked exception thrown by all repository IO. Callers must handle. |

### Critical Jaloquent behavior — NOT-NULL and the INSERT column list
Jaloquent serializes **every registered column** in the INSERT statement. Any column
not explicitly `set(...)` before `save()` is passed as SQL `NULL` — the database column
`DEFAULT` is **ignored**, so a `NOT NULL` column with an unset value triggers a constraint
violation (H2 error `23502`). Therefore **every model constructor explicitly sets all
NOT-NULL columns to their default values** so the first `save()` never fails. An implementer
MUST replicate this: seed all NOT-NULL columns in the constructor. (See per-model constructor
lists below.)

### `save()` is an UPSERT keyed on `id`
Jaloquent emits MySQL-style:
```
INSERT INTO `t` (`id`, `col`, …) VALUES (?, ?, …) ON DUPLICATE KEY UPDATE `col`=VALUES(`col`), …
```
This is why there is no separate `insert` vs `update` — `save()` covers both. H2 requires a
rewrite (see §7).

---

## 2. Backends & connection management (`DatabaseManager`)

`DatabaseManager` owns a **HikariCP** pool (`HikariDataSource`) and a Jaloquent
`DataSourceJdbcStore`. Lifecycle: `initialize(DatabaseConfig, File dataDir, Logger)` in
bootstrap; `close()` in shutdown.

Backend selected by `database.yml` `type`: anything other than case-insensitive `"mysql"`
is treated as **H2** (H2 is the default/fallback).

### HikariCP configuration (both backends)
| Setting | Value |
|---|---|
| `poolName` | `ReferenceFactions-DB` |
| `connectionTimeout` | `10_000` ms |
| `idleTimeout` | `600_000` ms (10 min) |
| `maxLifetime` | `1_800_000` ms (30 min) |

### H2 (default, embedded, file-based)
- File path: `new File(dataDir, dbCfg.getH2File())` where `getH2File()` default is
  `data/factions` (relative to `plugins/ReferenceFactions/`). Parent dirs are `mkdirs()`-ed.
- JDBC URL: `jdbc:h2:file:<absolutePath>;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE`
  - `MODE=MySQL` → MySQL-compat mode (needed for backtick identifiers, `TINYINT`, etc.).
  - `DB_CLOSE_DELAY=-1` → keep DB open until JVM exit even if all connections close.
  - `NON_KEYWORDS=VALUE` → allow `VALUE` as an identifier (H2 2.x reserves it).
- `driverClassName` set **explicitly** via `detectH2DriverClass()` (see §6) to avoid Bukkit
  classloader isolation problems with `DriverManager`.
- Credentials: username `sa`, password empty.
- `maximumPoolSize = 1` (H2 file DB is single-writer), `minimumIdle = 1`.
- Uses the `H2CompatJdbcStore` subclass of `DataSourceJdbcStore` (upsert rewrite, §7).

### MySQL / MariaDB (remote)
- JDBC URL:
  `jdbc:mysql://<host>:<port>/<database>?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=UTC`
- `driverClassName` via `detectMysqlDriverClass()` (see §6).
- Credentials + pool size from config: `mysql.username`, `mysql.password`,
  `maximumPoolSize = mysql.pool-size` (default 10), `minimumIdle = 2`.
- Uses the plain `DataSourceJdbcStore` (no upsert rewrite; MySQL supports `VALUES(col)`).

### Init failure handling
- Opening the pool: any exception → `IllegalStateException("Failed to open database connection pool", e)`.
- Schema creation `SQLException` → logs SEVERE and rethrows `IllegalStateException("Schema creation failed", e)`.
- Bootstrap (`InfrastructureBootstrapComponent.initDatabase`) calls `initialize(...)`, then
  checks `isInitialized()`; if false it logs `"Failed to initialise the database. Disabling plugin."`
  and returns false (plugin disable).

### `database.yml` keys (with defaults from `DatabaseConfig`)
| Key | Default | Meaning |
|---|---|---|
| `type` | `h2` | `h2` or `mysql` |
| `h2.file` | `data/factions` | H2 file path (relative to data folder) |
| `mysql.host` | `localhost` | |
| `mysql.port` | `3306` | |
| `mysql.database` | `factions` | |
| `mysql.username` | `root` | |
| `mysql.password` | `""` | |
| `mysql.pool-size` | `10` | Hikari `maximumPoolSize` |
| `debug.jaloquent-logging` | `false` | Toggles `JaloquentConfig.enableLogging` |

`database.yml` header note: *"Changes require a server restart."* The `DatabaseConfig` is a
typed wrapper over the Bukkit `FileConfiguration`; it is re-created on `/fa reload` but the DB
pool is not re-opened by reload.

---

## 3. Data model — entities & tables

Every model extends Jaloquent `Model`; `id` is always the primary key (a string). Column
definitions live in a `public static final Map<String,String> COLUMNS` on each model, keyed by
column name with a SQL type fragment as the value (H2/MySQL-compatible syntax). The `PREFIX`
constant is the TableRegistry key **and** the table name.

> **Ordering caveat:** most models build `COLUMNS` with `Map.of(...)` / `Map.ofEntries(...)`,
> which are **unordered**. The `CREATE TABLE` column order and Jaloquent INSERT column order
> are therefore non-deterministic across JVMs — this is harmless because columns are named,
> but an implementer should not rely on positional column order.

### 3.1 `factions` (`FactionModel`, PREFIX `factions`)
PK `id` = faction UUID string. Constructor seeds: `is_raidable=0`, `shield_duration_hours=0`,
`power_boost=0.0`, `money=0.0`, `created_at=0`.

| Column | Type | Default | Notes |
|---|---|---|---|
| `id` | VARCHAR(36) NOT NULL | — | faction UUID (PK) |
| `name` | VARCHAR(64) NOT NULL | — | display name; indexed |
| `owner_id` | VARCHAR(36) | null | UUID of highest-rank player |
| `description` | TEXT | null | getter defaults to `""` |
| `motd` | TEXT | null | getter defaults to `""` |
| `created_at` | BIGINT NOT NULL | 0 | epoch millis |
| `power_boost` | DOUBLE NOT NULL | 0.0 | admin power adjustment |
| `money` | DOUBLE NOT NULL | 0.0 | bank balance (aliased `getBank/setBank`) |
| `flags_json` | TEXT | null | JSON map, getter default `"{}"` |
| `perms_json` | TEXT | null | JSON map, getter default `"{}"` |
| `relations_json` | TEXT | null | JSON `factionId → Relation`, default `"{}"` |
| `home_world` | VARCHAR(64) | null | `hasHome()` = world != null |
| `home_x` | DOUBLE | null | getter default 0.0 |
| `home_y` | DOUBLE | null | getter default **64.0** |
| `home_z` | DOUBLE | null | getter default 0.0 |
| `home_yaw` | FLOAT | null | getter default 0.0 |
| `home_pitch` | FLOAT | null | getter default 0.0 |
| `is_raidable` | TINYINT NOT NULL | 0 | boolean; `1`=raidable (set by power tick) |
| `shield_start_hour` | INT | null | UTC hour 0–23 of war-shield window start |
| `shield_duration_hours` | INT NOT NULL | 0 | 0 = no shield |

Sentinel IDs (not stored rows — pseudo-factions): `WILDERNESS`, `SAFEZONE`, `WARZONE`.
Helpers: `isWilderness/isSafeZone/isWarZone/isNormal`, `isOwner(UUID)`.

**War-shield active check (`isShieldActive()`)** — load-bearing:
returns false if `duration ≤ 0` or `shield_start_hour == null`; else
`base = ((start % 24) + 24) % 24`, current = UTC hour now, active iff
`(base + i) % 24 == current` for any `i` in `[0, duration)` (wraps midnight).

### 3.2 `players` (`PlayerModel`, PREFIX `players`)
PK `id` = player UUID string. One row per known player (membership + preferences + power).
Constructor seeds every NOT-NULL column (see below).

| Column | Type | Default | Notes |
|---|---|---|---|
| `id` | VARCHAR(36) NOT NULL | — | player UUID (PK) |
| `faction_id` | VARCHAR(36) | null | null/empty = factionless; indexed |
| `rank_id` | VARCHAR(36) | null | rank UUID within faction |
| `title` | VARCHAR(64) | null | getter default `""` |
| `power_boost` | DOUBLE NOT NULL | 0.0 | admin adjust (may be negative) |
| `power` | DOUBLE NOT NULL | 0.0 | current accumulated power |
| `joined_at` | BIGINT NOT NULL | 0 | epoch millis joined current faction |
| `last_activity` | BIGINT NOT NULL | 0 | epoch millis last seen online |
| `overriding` | TINYINT NOT NULL | 0 | admin-override mode |
| `territory_titles` | TINYINT NOT NULL | 1 | show titles on chunk crossing |
| `auto_territory_mode` | TINYINT NOT NULL | 0 | enum: 0=OFF,1=CLAIM,2=UNCLAIM |
| `notify_invites` | TINYINT NOT NULL | 1 | |
| `notify_bank_tax` | TINYINT NOT NULL | 1 | |
| `notify_motd` | TINYINT NOT NULL | 1 | |
| `locale` | VARCHAR(16) | null | per-player locale override |
| `last_death_at` | BIGINT NOT NULL | 0 | epoch millis of most recent death |
| `death_streak` | TINYINT NOT NULL | 0 | consecutive deaths in streak window |
| `power_frozen` | TINYINT NOT NULL | 0 | if true, power tick skips this player |

Boolean columns are TINYINT; getters compare `getAs(col, Integer.class, dflt) == 1`, setters
store `enabled ? 1 : 0`. `auto_territory_mode` maps through the `AutoTerritoryMode` enum:
`OFF(0)`, `CLAIM(1)`, `UNCLAIM(2)`; `fromDbValue(int)` maps 1→CLAIM, 2→UNCLAIM, default→OFF.
`isInFaction()` = `faction_id` non-null and non-empty.

**Columns `auto_territory_mode`, `notify_invites`, `notify_bank_tax`, `notify_motd`, `locale`,
`power_frozen` are added via `ensureColumn` ALTER migrations** (present in COLUMNS too, so on a
fresh DB they come from CREATE TABLE; on an upgraded DB they come from ALTER). See §5.

### 3.3 `board` (`BoardEntry`, PREFIX `board`) — CHUNK CLAIMS
PK `id` = composite chunk key `"world:chunkX:chunkZ"` (VARCHAR(128)). **No default-seeding
constructor** (both non-id columns handled at save time).

| Column | Type | Default | Notes |
|---|---|---|---|
| `id` | VARCHAR(128) NOT NULL | — | `world:chunkX:chunkZ` (PK) |
| `faction_id` | VARCHAR(36) NOT NULL | — | owning faction UUID; indexed |
| `access_json` | TEXT | null | per-player/per-relation access overrides; getter default `"{}"` |

**Chunk-claim storage encoding (load-bearing):**
- `BoardEntry.buildId(worldName, chunkX, chunkZ)` → `worldName + ":" + chunkX + ":" + chunkZ`.
  No padding, no hashing; chunk coords are the Bukkit chunk (block>>4) coords, signed decimal.
- Parsing: `getId().split(":")` — `[0]`=world, `[1]`=chunkX (`Integer.parseInt`), `[2]`=chunkZ.
  **Caveat:** a world name containing `:` would break parsing — implementer should note Bukkit
  world names never contain `:` in practice.
- `createTableColumns()` returns a `LinkedHashMap` copy of COLUMNS with `id` overridden to
  `VARCHAR(128) NOT NULL PRIMARY KEY` (a helper, but note the actual schema is built by
  `DatabaseManager.createTableSql`, which appends its own `PRIMARY KEY (id)` — see §4).

### 3.4 `warps` (`WarpModel`, PREFIX `warps`)
PK `id` = warp UUID. Constructor seeds: `x=0.0, y=64.0, z=0.0, yaw=0.0, pitch=0.0,
created_at=0, use_cost=0.0`.

| Column | Type | Default | Notes |
|---|---|---|---|
| `id` | VARCHAR(36) NOT NULL | — | warp UUID (PK) |
| `faction_id` | VARCHAR(36) NOT NULL | — | indexed |
| `name` | VARCHAR(64) NOT NULL | — | |
| `world` | VARCHAR(64) NOT NULL | — | Bukkit world name |
| `x` | DOUBLE NOT NULL | 0.0 | |
| `y` | DOUBLE NOT NULL | 64.0 | |
| `z` | DOUBLE NOT NULL | 0.0 | |
| `yaw` | FLOAT NOT NULL | 0.0 | |
| `pitch` | FLOAT NOT NULL | 0.0 | |
| `creator_id` | VARCHAR(36) | null | |
| `created_at` | BIGINT NOT NULL | 0 | |
| `password` | VARCHAR(64) | null | added via `ensureColumn` (v1.1.3); `hasPassword()` = non-null & non-empty |
| `use_cost` | DOUBLE NOT NULL | 0.0 | added via `ensureColumn` (v1.1.3); `hasCost()` = > 0 |

`toLocation()` reconstructs a Bukkit `Location`, returns null if world unloaded.

### 3.5 `invitations` (`InvitationModel`, PREFIX `invitations`)
PK `id` = invite UUID. Constructor seeds `created_at=0`. TTL-expired by the engine (deletes on
accept/decline/expiry — not enforced at DB level).

| Column | Type | Default | Notes |
|---|---|---|---|
| `id` | VARCHAR(36) NOT NULL | — | PK |
| `faction_id` | VARCHAR(36) NOT NULL | — | indexed |
| `invitee_id` | VARCHAR(36) NOT NULL | — | invited player; indexed (+composite with faction) |
| `inviter_id` | VARCHAR(36) NOT NULL | — | sender |
| `created_at` | BIGINT NOT NULL | 0 | epoch millis |

### 3.6 `ranks` (`RankModel`, PREFIX `ranks`)
PK `id` = rank UUID. Constructor seeds `priority=10`. Every faction has ≥3 built-in ranks.

| Column | Type | Default | Notes |
|---|---|---|---|
| `id` | VARCHAR(36) NOT NULL | — | PK |
| `faction_id` | VARCHAR(36) NOT NULL | — | indexed |
| `name` | VARCHAR(64) NOT NULL | — | getter default `"Member"` |
| `prefix` | VARCHAR(32) | null | MiniMessage chat prefix |
| `priority` | INT NOT NULL | 10 | higher = more authority |

Built-in names/priorities (load-bearing constants): `Owner`=100, `Officer`=50, `Member`=10.
Helpers: `isOwner()` = priority≥100; `isOfficerOrAbove()` = priority≥50;
`canManage(other)` = `priority > other.priority`. Priority thresholds map to TeamsAPI
`TeamRole`.

### 3.7 `bank_transactions` (`BankTransactionModel`, PREFIX `bank_transactions`)
PK `id` = txn UUID. Constructor seeds `amount=0.0, created_at=0`.

| Column | Type | Default | Notes |
|---|---|---|---|
| `id` | VARCHAR(36) NOT NULL | — | PK |
| `faction_id` | VARCHAR(36) NOT NULL | — | indexed (+composite with created_at) |
| `actor_uuid` | VARCHAR(36) | null | who performed it |
| `type` | VARCHAR(32) NOT NULL | — | transaction type label |
| `amount` | DOUBLE NOT NULL | 0.0 | signed |
| `counterparty_faction_id` | VARCHAR(36) | null | for transfers |
| `created_at` | BIGINT NOT NULL | 0 | epoch millis |
| `note` | VARCHAR(255) | null | getter default `""` |

### 3.8 `power_history` (`PowerHistoryModel`, PREFIX `power_history`)
PK `id` = entry UUID. **No constructor seeding** (all fields set by `record`/`insert`).

| Column | Type | Default | Notes |
|---|---|---|---|
| `id` | VARCHAR(36) NOT NULL | — | PK |
| `player_uuid` | VARCHAR(36) NOT NULL | — | indexed (+composite with created_at) |
| `delta` | DOUBLE NOT NULL | 0.0 | signed power change |
| `reason` | VARCHAR(32) NOT NULL | — | e.g. `DEATH`, `KILL`, `BUY` |
| `power_after` | DOUBLE NOT NULL | 0.0 | power after the change (0.0 for external `insert`) |
| `created_at` | BIGINT NOT NULL | 0 | epoch millis |

### 3.9 `faction_inbox` (`FactionInboxEntry`, PREFIX `faction_inbox`)
PK `id` = entry UUID. Offline-player notification queue; deleted on delivery at join.

| Column | Type | Default | Notes |
|---|---|---|---|
| `id` | VARCHAR(36) NOT NULL | — | PK |
| `player_id` | VARCHAR(36) NOT NULL | — | recipient; indexed |
| `message` | TEXT NOT NULL | — | MiniMessage text |
| `created_at` | BIGINT NOT NULL | 0 | |

### 3.10 `audit_logs` (`AuditLogModel`, PREFIX `audit_logs`)
PK `id` = entry UUID. Constructor seeds `created_at=0`.

| Column | Type | Default | Notes |
|---|---|---|---|
| `id` | VARCHAR(36) NOT NULL | — | PK |
| `faction_id` | VARCHAR(36) NOT NULL | — | indexed (+composite with created_at) |
| `actor_uuid` | VARCHAR(36) | null | |
| `action` | VARCHAR(64) NOT NULL | — | action label |
| `detail` | TEXT | null | getter default `""` |
| `created_at` | BIGINT NOT NULL | 0 | |

### 3.11 `team_chests` (`TeamChestModel`, PREFIX `team_chests`)
PK `id` = chest UUID. Constructor seeds `created_at=0`.

| Column | Type | Default | Notes |
|---|---|---|---|
| `id` | VARCHAR(36) NOT NULL | — | PK |
| `faction_id` | VARCHAR(36) NOT NULL | — | indexed |
| `name` | VARCHAR(64) NOT NULL | — | |
| `contents` | TEXT | null | Base64 of Bukkit-serialized `ItemStack[]` |
| `created_at` | BIGINT NOT NULL | 0 | |

**Contents encoding (`TeamChestSerialization`):** `encode` writes `int size` then each
`ItemStack` via `BukkitObjectOutputStream` into a `ByteArrayOutputStream`, then
`Base64.getEncoder().encodeToString(...)`. `decode` reverses via `BukkitObjectInputStream`;
null/blank → empty list. Requires the Bukkit/Paper serialization API at runtime.

### 3.12 `merge_requests` (`MergeRequestModel`, PREFIX `merge_requests`)
PK `id` = request UUID. Constructor seeds `created_at=0`. No TTL (explicit accept/cancel only).

| Column | Type | Default | Notes |
|---|---|---|---|
| `id` | VARCHAR(36) NOT NULL | — | PK |
| `sender_faction_id` | VARCHAR(36) NOT NULL | — | faction that dissolves & is absorbed |
| `target_faction_id` | VARCHAR(36) NOT NULL | — | absorbing faction |
| `actor_id` | VARCHAR(36) NOT NULL | — | officer who sent it |
| `created_at` | BIGINT NOT NULL | 0 | |

> ⚠️ **KNOWN BUG / important behavior to preserve or fix:** the `merge_requests` table is
> **registered in `TableRegistry`** (via `MergeRequestRepository` constructor) but **NOT created**
> by `DatabaseManager.createTables()` — that method only issues `CREATE TABLE` for the other 11
> tables. On a fresh DB there is no `merge_requests` table, so any `MergeRequestRepository`
> operation throws `StorageException` at runtime (table missing) unless it was created by some
> other path. A clean-room reimplementation **should create this table** (mirror the other
> tables) to make merge requests functional. Document this discrepancy explicitly.

---

## 4. Schema creation (`DatabaseManager.createTables`)

Runs once during `initialize()`, on the calling (bootstrap/main) thread, using one Hikari
connection + `Statement`. For each of the **11** tables (NOT merge_requests) it calls
`createTableSql(tableName, COLUMNS, "id")`:

```
CREATE TABLE IF NOT EXISTS `<table>` (`col` <type>, …, PRIMARY KEY (`id`))
```
- Column order is the iteration order of the `COLUMNS` map (unordered for `Map.of`).
- The primary key is always `id`, appended as a trailing `PRIMARY KEY (`id`)` clause.
- Backtick-quoted identifiers (works in H2 MySQL-mode and MySQL).

Tables created (in this exact call order): `factions`, `players`, `board`, `warps`,
`invitations`, `ranks`, `bank_transactions`, `power_history`, `faction_inbox`, `audit_logs`,
`team_chests`. Then `ensureColumn` migrations, then `createIndexes`.

---

## 5. Schema migration / versioning (`ensureColumn`)

There is **no version table** and no numeric schema version. Migration is idempotent
column-adding via `ensureColumn(stmt, logger, table, column, columnSql)`:

1. Try `ALTER TABLE `t` ADD COLUMN IF NOT EXISTS `c` <sql>`.
2. On `SQLException` (engine lacking `IF NOT EXISTS`), retry `ALTER TABLE … ADD COLUMN` without it.
3. If that also fails, and the message doesn't contain `exists`/`duplicate`/`already`
   (case-insensitive), log at `FINE`; otherwise silently ignore (column already present).

Because these columns are ALSO in the models' `COLUMNS` maps, a fresh DB gets them from
`CREATE TABLE`; an older DB gets them from these ALTERs. Applied migrations:

| Table | Column | Definition | Version note |
|---|---|---|---|
| players | `auto_territory_mode` | `TINYINT NOT NULL DEFAULT 0` | |
| players | `notify_invites` | `TINYINT NOT NULL DEFAULT 1` | |
| players | `notify_bank_tax` | `TINYINT NOT NULL DEFAULT 1` | |
| players | `notify_motd` | `TINYINT NOT NULL DEFAULT 1` | |
| players | `locale` | `VARCHAR(16)` | |
| players | `power_frozen` | `TINYINT NOT NULL DEFAULT 0` | |
| warps | `password` | `VARCHAR(64)` | 1.1.3 |
| warps | `use_cost` | `DOUBLE NOT NULL DEFAULT 0.0` | 1.1.3 |

To add future columns, an implementer follows the same pattern: add to `COLUMNS` **and** add an
`ensureColumn` call.

### Indexes (`createIndexes`)
Created after column migrations via `createIndex(stmt, logger, indexName, table, columnList)`:
tries `CREATE INDEX IF NOT EXISTS `idx` ON `t` (cols)`; on failure retries without
`IF NOT EXISTS` and swallows exists/duplicate/already errors (logs others at FINE).

| Index | Table | Columns |
|---|---|---|
| `idx_factions_name` | factions | `name` |
| `idx_players_faction_id` | players | `faction_id` |
| `idx_ranks_faction_id` | ranks | `faction_id` |
| `idx_board_faction_id` | board | `faction_id` |
| `idx_warps_faction_id` | warps | `faction_id` |
| `idx_team_chests_faction_id` | team_chests | `faction_id` |
| `idx_invitations_faction_id` | invitations | `faction_id` |
| `idx_invitations_invitee_id` | invitations | `invitee_id` |
| `idx_invitations_faction_invitee` | invitations | `faction_id, invitee_id` |
| `idx_bank_tx_faction_id` | bank_transactions | `faction_id` |
| `idx_bank_tx_faction_created_at` | bank_transactions | `faction_id, created_at` |
| `idx_power_history_player_uuid` | power_history | `player_uuid` |
| `idx_power_history_player_created_at` | power_history | `player_uuid, created_at` |
| `idx_inbox_player_id` | faction_inbox | `player_id` |
| `idx_audit_logs_faction_id` | audit_logs | `faction_id` |
| `idx_audit_logs_faction_created_at` | audit_logs | `faction_id, created_at` |

Note: none are UNIQUE indexes (name uniqueness is enforced in the service layer, not the DB —
`idx_factions_name` is a plain index).

---

## 6. Driver-class detection (shaded vs unshaded)

Both drivers are shaded/relocated in the packaged jar; Hikari `driverClassName` is set
explicitly (never via `DriverManager` SPI — the `META-INF/services/java.sql.Driver` file is
excluded by the shade config). Detection tries the shaded class first, falls back to the
canonical one (for tests):
- `detectH2DriverClass()`: `com.reference.lib.h2.Driver` else `org.h2.Driver`.
- `detectMysqlDriverClass()`: `com.reference.lib.mysql.cj.jdbc.Driver` else `com.mysql.cj.jdbc.Driver`.

---

## 7. H2 upsert compatibility (`H2CompatJdbcStore`)

`DataSourceJdbcStore` subclass used only for H2. Overrides `executeUpdate(sql, params)` to
rewrite Jaloquent's MySQL upsert into H2's `MERGE`. Rewrite logic (`h2Upsert(String)`), exact:

1. Find `" ON DUPLICATE KEY UPDATE "`; if absent, return SQL unchanged.
2. Take substring before it (`beforeOdku`); find last case-insensitive `" VALUES "`; if absent,
   return unchanged.
3. Return:
   `"MERGE INTO" + beforeOdku.substring("INSERT INTO".length(), valuesIdx) + " KEY(`id`)" + beforeOdku.substring(valuesIdx)`

Effect: `INSERT INTO `t` (cols) VALUES (?) ON DUPLICATE KEY UPDATE …` →
`MERGE INTO `t` (cols) KEY(`id`) VALUES (?)`. H2 does not implement the `VALUES(col)` function
reference inside `ON DUPLICATE KEY UPDATE`, so this rewrite is mandatory. MySQL path keeps the
original SQL (uses the plain `DataSourceJdbcStore`). The `MERGE … KEY(`id`)` matches the fact
that every table's PK is `id` — the rewrite is only correct because all upserts key on `id`.

---

## 8. Repositories — query semantics

Container: `Repositories` holds all 12 repository instances (built once in bootstrap after DB
init, from `db.getStore()`), exposing accessor methods (`factions()`, `players()`, `board()`,
`warps()`, `invitations()`, `ranks()`, `bankTransactions()`, `powerHistory()`, `inbox()`,
`auditLogs()`, `mergeRequests()`, `teamChests()`).

All repos extend `ModelRepository<T>` and register their table with `TableRegistry` in the
constructor. Every method throws `StorageException`.

### Cross-cutting patterns
- **"find by column" methods** delegate to `query(new QueryBuilder().whereEquals(col, val).build())`.
- **findAll** = `query(new QueryBuilder().build())`.
- **Sorting, filtering, pagination, and secondary lookups are done in Java (in-memory) after a
  broad query** — the query builder is only used for single-column equality WHERE clauses.
  There is NO ORDER BY / LIMIT / OFFSET / LIKE pushed to SQL. Implementers may optimize by
  pushing these into SQL but must preserve the observable ordering/paging semantics below.

### Per-repository methods
| Repo | Method | Semantics |
|---|---|---|
| **FactionRepository** | `findAll()` | all factions |
| | `findByName(name)` | `whereEquals("name", name)`, returns first match or empty. **Note:** despite the docstring saying "case-insensitive", `whereEquals` is a SQL equality — case sensitivity depends on the DB collation (H2 MySQL-mode/MySQL default is case-insensitive for VARCHAR). |
| | `countAll()` | `findAll().size()` (loads all rows) |
| **PlayerRepository** | `findByFactionId(fid)` | members of a faction |
| | `findAll()` | all players |
| | `findOrCreate(uuid)` | `find(uuid)` else `new PlayerModel(uuid)`; both passed through `ensureWriteSafeDefaults`. **New model is NOT saved** by this method. |
| | `clearFactionMembers(fid)` | loads members, sets `faction_id=null` + `rank_id=null`, `save`s each. Does **not** delete player rows. |
| | `ensureWriteSafeDefaults(m)` (private) | title null→`""`; re-sets territory_titles/overriding/notify_*/power_frozen to their current values (forces the attribute into the write map to avoid NULL insert); locale blank→null; auto_territory_mode re-set, null→OFF. |
| **BoardRepository** | `findByChunk(world,x,z)` | `find(buildId(...))` point lookup |
| | `findByFactionId(fid)` | all claims of a faction |
| | `countByFactionId(fid)` | `findByFactionId(fid).size()` |
| | `deleteByFactionId(fid)` | `deleteWhere("faction_id", fid)` |
| | `claimChunk(world,x,z,fid)` | `new BoardEntry(buildId)`, set faction_id, `save` (upsert — overwrites owner) |
| | `unclaimChunk(world,x,z)` | `delete(buildId)` |
| **WarpRepository** | `findByFactionId(fid)` | |
| | `findByFactionIdAndName(fid,name)` | loads faction warps, in-memory `equalsIgnoreCase` match (case-INsensitive despite docstring saying case-sensitive) |
| | `deleteByFactionId(fid)` | bulk delete |
| **InvitationRepository** | `findByFactionId(fid)` / `findByInviteeId(id)` | |
| | `findByFactionAndInvitee(fid,invitee)` | filters `findByInviteeId` by faction in Java |
| | `deleteByFactionId(fid)` / `deleteByInviteeId(id)` | bulk delete |
| **RankRepository** | `findByFactionId(fid)` | queried then **sorted by priority DESC in Java** |
| | `findDefaultRank(fid)` | first rank named exactly `"Member"` |
| | `findOwnerRank(fid)` | first rank with `isOwner()` (priority≥100) |
| | `deleteByFactionId(fid)` | bulk delete |
| **BankTransactionRepository** | `findRecentByFactionId(fid, limit, offset)` | query all for faction → sort by `created_at` DESC → `subList(offset, min(size, offset+limit))`; empty if offset≥size |
| **PowerHistoryRepository** | `findRecentByPlayerUuid(uuid, limit, offset)` | same paging pattern as bank txns |
| | `record(uuid, delta, reason, powerAfter)` | new entry, random UUID, `created_at = System.currentTimeMillis()`, `save` |
| | `insert(UUID id, uuid, delta, reason, occurredAtMs)` | idempotent by id: if `find(id)` present → return false; else save with `power_after=0.0`, `created_at=occurredAtMs`, return true (used by TeamsAPI integration) |
| | `findAllByPlayerUuid(uuid)` | all, sorted `created_at` DESC |
| | `deleteById(id)` | if absent return false; else `delete`, return true |
| **FactionInboxRepository** | `findByPlayerId(id)` | insertion-order (no explicit sort) |
| | `deleteByPlayerId(id)` | bulk delete |
| **AuditLogRepository** | `findByFaction(fid, limit, offset)` | query all for faction → sort `created_at` DESC → paginate |
| | `findByFactionAndAction(fid, action, limit, offset)` | query all → `removeIf` action !equalsIgnoreCase → sort DESC → paginate |
| **TeamChestRepository** | `findByFactionId(fid)` | |
| | `findByFactionIdAndName(fid,name)` | in-memory `equalsIgnoreCase` |
| | `deleteByFactionId(fid)` | bulk delete |
| **MergeRequestRepository** | `findBySenderFactionId` / `findByTargetFactionId` | |
| | `findBySenderAndTarget(s,t)` | filter sender list by target in Java |
| | `deleteBySenderFactionId` / `deleteByTargetFactionId` | bulk delete |

**Pagination contract (bank/power/audit)** — replicate exactly: sort descending by
`created_at`, then `if (offset >= rows.size()) return List.of();
return rows.subList(offset, Math.min(rows.size(), offset + limit));`. This returns a **view**
sublist for the non-empty case (in the original; a reimplementation may return a copy).

---

## 9. Caching layers

There is **no repository-level cache** — every read hits the DB (Hikari + JDBC). Caching is
minimal and lives above the data layer:

1. **`AutoTerritoryModeCache`** (`engine` package) — a `ConcurrentHashMap<UUID, AutoTerritoryMode>`
   write-through cache over the `players.auto_territory_mode` column.
   - `getMode(uuid)` → cache, default OFF (no DB hit).
   - `setMode(uuid, mode)` → `players().findOrCreate`, set mode, **`save` (write-through)**, then
     update cache; on `StorageException` logs WARNING and returns false (cache NOT updated).
   - `hydrate(uuid)` → load from DB into cache (called on join); on failure caches OFF.
   - `evict(uuid)` → remove from cache (called on quit).
2. **`FactionServiceImpl`** holds a transient runtime-only `ConcurrentHashMap<UUID, Boolean>
   flyStateByPlayer` (faction-fly toggle state) — NOT persisted, not a DB cache.
3. No faction/board/rank in-memory index — territory lookups, member lists, etc. read the DB
   each time (indexes back these). An implementer wanting parity keeps reads direct; wanting
   performance may add caches but must preserve write-through-on-save semantics.

**Write model = write-through, synchronous.** Every mutation is a `save()`/`delete()` that
immediately executes SQL. There is no write-behind buffer, no dirty-tracking flush.

---

## 10. Async / threading model

- **All repository IO is synchronous blocking JDBC** on whatever thread calls it. The
  repositories themselves are thread-agnostic; HikariCP is the only concurrency guard (pool
  size 1 for H2 → serialized writes; ≥2 for MySQL).
- **Engines schedule their own async work** via the platform-neutral `TaskScheduler` interface
  (`runAsync`, `runSync`, `runSyncForPlayer`, `runSyncLater`, `scheduleAsyncTimer`,
  returning `CancelableTask`). Implementations: `BukkitTaskScheduler`, `FoliaTaskScheduler`
  (chosen by `PlatformDetector.isFolia()`).
- **Pattern:** heavy/periodic DB work runs on async threads. Example — `EnginePower`:
  `taskScheduler.scheduleAsyncTimer(this, intervalTicks, intervalTicks)` runs the power tick
  off the main thread; inside it reads `repos.players().findAll()`, `repos.board().findByChunk`,
  `repos.factions().findAll()`, mutates and `repos.factions().save(...)` — all on the async
  thread. `EngineEconomy` runs periodic bank taxation similarly (async timer).
- **Marshaling back to the main thread:** when a result must touch the Bukkit API (teleport,
  messaging, world/region access), the engine wraps that portion in `scheduler.runSync(...)` /
  `runSyncForPlayer(player, ...)`. There is no `CompletableFuture`-based DB API in the data
  layer itself; futures appear only in the update-checker and some integrations.
- H2's single-connection pool means concurrent async DB access is serialized at the pool; the
  code does not add its own locks around repositories.

---

## 11. Save-on-quit / autosave / shutdown flush

- **No global autosave task and no shutdown "flush all dirty models" step.** Because every
  mutation is written through immediately (§9), there is nothing buffered to flush.
- **Shutdown:** `InfrastructureBootstrapComponent.stop()` unregisters LWC interop then calls
  `DatabaseManager.close()`, which closes the Hikari pool if open. For H2, `DB_CLOSE_DELAY=-1`
  keeps the file DB open until JVM exit; closing the pool releases connections. There is no
  explicit `CHECKPOINT`/`SHUTDOWN` statement issued to H2.
- **Join/quit player lifecycle** is handled by listeners/engines, not the data layer: on join,
  caches like `AutoTerritoryModeCache.hydrate` load state and inbox entries are delivered
  (then `deleteByPlayerId`); on quit, `AutoTerritoryModeCache.evict`. `last_activity` is a
  persisted column updated by the engine layer (via `save`), not by any data-layer timer.
- **Periodic timers that mutate the DB** (power tick, bank tax) are the closest thing to
  autosave — they recompute and `save()` faction/player rows on their async cadence. Their
  intervals are engine-config driven (`intervalTicks`), not part of the data layer.

---

## 12. Update-notification manager (`update` package)

`UpdateNotificationManager` (in scope but orthogonal to persistence): wraps a
`ChainedUpdateChecker` (from shaded `com.reference.lib.updater`).
- `checkAsync()` → `checker.checkNowAsync()` (returns `CompletableFuture<ChainedUpdateResult>`);
  `.thenAccept(handleResult).exceptionally(...)` logs WARNING on unexpected failure.
- `handleResult`: stores `latest` (volatile). If `update.hasError()` → WARNING listing failed
  source keys. If `isUpdateAvailable()` → WARNING `current -> latest` + download URL. Else INFO
  "up to date". `latest()` exposes `Optional<ChainedUpdateResult>` for join-time notifications.
- No database interaction; purely network + logging.

---

## 13. Reimplementation checklist / gotchas

1. **Seed all NOT-NULL columns in every model constructor** (or however you emit INSERTs) — do
   not rely on SQL DEFAULTs; the ORM sends explicit NULLs for unset columns.
2. **`save()` must be an upsert keyed on `id`** for all tables.
3. **H2 needs the `MERGE INTO … KEY(`id`)` rewrite**; MySQL uses `ON DUPLICATE KEY UPDATE`.
4. **H2 JDBC URL flags are load-bearing:** `MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE`.
5. **Chunk claim key = `world:chunkX:chunkZ`** (signed decimal chunk coords, colon-delimited),
   VARCHAR(128) PK on `board`.
6. **Boolean columns are TINYINT 0/1**; enum `auto_territory_mode` is 0/1/2 (OFF/CLAIM/UNCLAIM).
7. **Create the `merge_requests` table** — the original registers but never creates it (bug).
8. **Pagination/sorting/filtering is in-memory** in the original; equality WHERE is the only SQL
   predicate used. Preserve descending-`created_at` ordering and `subList` paging semantics.
9. **No schema-version table**; migrations are idempotent `ADD COLUMN IF NOT EXISTS` + retry.
10. **H2 pool size = 1** (single writer); MySQL pool from config (default 10, min idle 2).
11. **`findByName`/name uniqueness** is not DB-enforced (plain index); enforce in service layer.
12. **`findOrCreate` does not persist** a freshly created player — caller must `save`.
