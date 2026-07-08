package dev.fablemc.factions.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The relational projection DDL (proposal-C §6.2). Identical across H2 and MySQL — only the
 * upsert shape (in {@link SqlDialect}) differs. All 12 reference-parity tables are created,
 * including {@code merge_requests} (the reference registered but never created it — bug fixed,
 * pvp-data §3.12), plus the FableFactions control tables {@code ff_meta}, {@code ff_escrows},
 * {@code ff_blobs}.
 *
 * <p>Deliberate deviations from the reference schema (proposal-C §6.2):
 * <ul>
 *   <li><b>D-15:</b> {@code board} keys on {@code (world, cx, cz)} — no string keys on the
 *       hottest table (the reference used a {@code "world:cx:cz"} VARCHAR PK);</li>
 *   <li>{@code factions} gains a {@code name_folded} shadow column with a <b>UNIQUE</b> index,
 *       enforcing case-insensitive name uniqueness at the DB (belt for the service-layer check).</li>
 * </ul>
 *
 * <p><b>Owning thread(s):</b> boot thread only. <b>Mutability:</b> stateless static DDL. Backtick
 * quoting works in both H2 ({@code MODE=MySQL}) and MySQL.
 */
public final class Schema {

    private Schema() {
    }

    private static final String[] TABLES = {
            // factions
            "CREATE TABLE IF NOT EXISTS `factions` ("
                    + "`id` VARCHAR(36) NOT NULL, `name` VARCHAR(64) NOT NULL, "
                    + "`name_folded` VARCHAR(64) NOT NULL, `owner_id` VARCHAR(36), "
                    + "`description` TEXT, `motd` TEXT, `created_at` BIGINT NOT NULL DEFAULT 0, "
                    + "`power_boost` DOUBLE NOT NULL DEFAULT 0, `money` DOUBLE NOT NULL DEFAULT 0, "
                    + "`flags_json` TEXT, `perms_json` TEXT, `relations_json` TEXT, "
                    + "`home_world` VARCHAR(64), `home_x` DOUBLE, `home_y` DOUBLE, `home_z` DOUBLE, "
                    + "`home_yaw` FLOAT, `home_pitch` FLOAT, "
                    + "`is_raidable` TINYINT NOT NULL DEFAULT 0, `shield_start_hour` INT, "
                    + "`shield_duration_hours` INT NOT NULL DEFAULT 0, PRIMARY KEY (`id`))",
            // players
            "CREATE TABLE IF NOT EXISTS `players` ("
                    + "`id` VARCHAR(36) NOT NULL, `faction_id` VARCHAR(36), `rank_id` VARCHAR(36), "
                    + "`title` VARCHAR(64), `power_boost` DOUBLE NOT NULL DEFAULT 0, "
                    + "`power` DOUBLE NOT NULL DEFAULT 0, `joined_at` BIGINT NOT NULL DEFAULT 0, "
                    + "`last_activity` BIGINT NOT NULL DEFAULT 0, `overriding` TINYINT NOT NULL DEFAULT 0, "
                    + "`territory_titles` TINYINT NOT NULL DEFAULT 1, "
                    + "`auto_territory_mode` TINYINT NOT NULL DEFAULT 0, "
                    + "`notify_invites` TINYINT NOT NULL DEFAULT 1, "
                    + "`notify_bank_tax` TINYINT NOT NULL DEFAULT 1, "
                    + "`notify_motd` TINYINT NOT NULL DEFAULT 1, `locale` VARCHAR(16), "
                    + "`last_death_at` BIGINT NOT NULL DEFAULT 0, `death_streak` TINYINT NOT NULL DEFAULT 0, "
                    + "`power_frozen` TINYINT NOT NULL DEFAULT 0, PRIMARY KEY (`id`))",
            // board (D-15 composite PK)
            "CREATE TABLE IF NOT EXISTS `board` ("
                    + "`world` VARCHAR(64) NOT NULL, `cx` INT NOT NULL, `cz` INT NOT NULL, "
                    + "`faction_id` VARCHAR(36) NOT NULL, `access_json` TEXT, "
                    + "PRIMARY KEY (`world`, `cx`, `cz`))",
            // warps
            "CREATE TABLE IF NOT EXISTS `warps` ("
                    + "`id` VARCHAR(36) NOT NULL, `faction_id` VARCHAR(36) NOT NULL, "
                    + "`name` VARCHAR(64) NOT NULL, `world` VARCHAR(64) NOT NULL, "
                    + "`x` DOUBLE NOT NULL DEFAULT 0, `y` DOUBLE NOT NULL DEFAULT 64, "
                    + "`z` DOUBLE NOT NULL DEFAULT 0, `yaw` FLOAT NOT NULL DEFAULT 0, "
                    + "`pitch` FLOAT NOT NULL DEFAULT 0, `creator_id` VARCHAR(36), "
                    + "`created_at` BIGINT NOT NULL DEFAULT 0, `password` VARCHAR(64), "
                    + "`use_cost` DOUBLE NOT NULL DEFAULT 0, PRIMARY KEY (`id`))",
            // invitations
            "CREATE TABLE IF NOT EXISTS `invitations` ("
                    + "`id` VARCHAR(36) NOT NULL, `faction_id` VARCHAR(36) NOT NULL, "
                    + "`invitee_id` VARCHAR(36) NOT NULL, `inviter_id` VARCHAR(36) NOT NULL, "
                    + "`created_at` BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (`id`))",
            // ranks
            "CREATE TABLE IF NOT EXISTS `ranks` ("
                    + "`id` VARCHAR(36) NOT NULL, `faction_id` VARCHAR(36) NOT NULL, "
                    + "`name` VARCHAR(64) NOT NULL, `prefix` VARCHAR(32), "
                    + "`priority` INT NOT NULL DEFAULT 10, PRIMARY KEY (`id`))",
            // bank_transactions
            "CREATE TABLE IF NOT EXISTS `bank_transactions` ("
                    + "`id` VARCHAR(36) NOT NULL, `faction_id` VARCHAR(36) NOT NULL, "
                    + "`actor_uuid` VARCHAR(36), `type` VARCHAR(32) NOT NULL, "
                    + "`amount` DOUBLE NOT NULL DEFAULT 0, `counterparty_faction_id` VARCHAR(36), "
                    + "`created_at` BIGINT NOT NULL DEFAULT 0, `note` VARCHAR(255), PRIMARY KEY (`id`))",
            // power_history
            "CREATE TABLE IF NOT EXISTS `power_history` ("
                    + "`id` VARCHAR(36) NOT NULL, `player_uuid` VARCHAR(36) NOT NULL, "
                    + "`delta` DOUBLE NOT NULL DEFAULT 0, `reason` VARCHAR(32) NOT NULL, "
                    + "`power_after` DOUBLE NOT NULL DEFAULT 0, `created_at` BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (`id`))",
            // faction_inbox
            "CREATE TABLE IF NOT EXISTS `faction_inbox` ("
                    + "`id` VARCHAR(36) NOT NULL, `player_id` VARCHAR(36) NOT NULL, "
                    + "`message` TEXT NOT NULL, `created_at` BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (`id`))",
            // audit_logs
            "CREATE TABLE IF NOT EXISTS `audit_logs` ("
                    + "`id` VARCHAR(36) NOT NULL, `faction_id` VARCHAR(36) NOT NULL, "
                    + "`actor_uuid` VARCHAR(36), `action` VARCHAR(64) NOT NULL, `detail` TEXT, "
                    + "`created_at` BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (`id`))",
            // team_chests
            "CREATE TABLE IF NOT EXISTS `team_chests` ("
                    + "`id` VARCHAR(36) NOT NULL, `faction_id` VARCHAR(36) NOT NULL, "
                    + "`name` VARCHAR(64) NOT NULL, `contents` TEXT, `blob_ref` BIGINT, "
                    + "`created_at` BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (`id`))",
            // merge_requests (created this time — reference bug fixed)
            "CREATE TABLE IF NOT EXISTS `merge_requests` ("
                    + "`id` VARCHAR(36) NOT NULL, `sender_faction_id` VARCHAR(36) NOT NULL, "
                    + "`target_faction_id` VARCHAR(36) NOT NULL, `actor_id` VARCHAR(36) NOT NULL, "
                    + "`created_at` BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (`id`))",
            // ff_meta (single row id=0): schema version, journal checkpoint, advisory lock (AM-11)
            "CREATE TABLE IF NOT EXISTS `ff_meta` ("
                    + "`id` INT NOT NULL, `schema_version` INT NOT NULL DEFAULT 0, "
                    + "`journal_seq` BIGINT NOT NULL DEFAULT -1, `lock_owner` VARCHAR(36), "
                    + "`lock_expiry` BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (`id`))",
            // ff_escrows: durable escrow saga history (AM-7)
            "CREATE TABLE IF NOT EXISTS `ff_escrows` ("
                    + "`id` BIGINT NOT NULL, `kind` INT NOT NULL, `player_uuid` VARCHAR(36), "
                    + "`faction_id` VARCHAR(36), `amount` DOUBLE NOT NULL DEFAULT 0, "
                    + "`status` VARCHAR(16) NOT NULL, `created_at` BIGINT NOT NULL DEFAULT 0, "
                    + "`settled_at` BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (`id`))",
            // ff_blobs: chest-content blob store (Blobs helper)
            "CREATE TABLE IF NOT EXISTS `ff_blobs` ("
                    + "`ref` BIGINT NOT NULL, `data` BLOB, `created_at` BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (`ref`))",
    };

    private static final String[] INDEXES = {
            "CREATE INDEX IF NOT EXISTS `idx_factions_name` ON `factions` (`name`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `ux_factions_name_folded` ON `factions` (`name_folded`)",
            "CREATE INDEX IF NOT EXISTS `idx_players_faction_id` ON `players` (`faction_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_ranks_faction_id` ON `ranks` (`faction_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_board_faction_id` ON `board` (`faction_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_warps_faction_id` ON `warps` (`faction_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_team_chests_faction_id` ON `team_chests` (`faction_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_invitations_faction_id` ON `invitations` (`faction_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_invitations_invitee_id` ON `invitations` (`invitee_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_invitations_faction_invitee` ON `invitations` "
                    + "(`faction_id`, `invitee_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_bank_tx_faction_id` ON `bank_transactions` (`faction_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_bank_tx_faction_created_at` ON `bank_transactions` "
                    + "(`faction_id`, `created_at`)",
            "CREATE INDEX IF NOT EXISTS `idx_power_history_player_uuid` ON `power_history` "
                    + "(`player_uuid`)",
            "CREATE INDEX IF NOT EXISTS `idx_power_history_player_created_at` ON `power_history` "
                    + "(`player_uuid`, `created_at`)",
            "CREATE INDEX IF NOT EXISTS `idx_inbox_player_id` ON `faction_inbox` (`player_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_audit_logs_faction_id` ON `audit_logs` (`faction_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_audit_logs_faction_created_at` ON `audit_logs` "
                    + "(`faction_id`, `created_at`)",
            "CREATE INDEX IF NOT EXISTS `idx_merge_requests_sender` ON `merge_requests` "
                    + "(`sender_faction_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_merge_requests_target` ON `merge_requests` "
                    + "(`target_faction_id`)",
    };

    /** The full ordered list of {@code CREATE TABLE} statements. */
    public static List<String> tableStatements() {
        return new ArrayList<>(List.of(TABLES));
    }

    /** The full ordered list of {@code CREATE INDEX} statements. */
    public static List<String> indexStatements() {
        return new ArrayList<>(List.of(INDEXES));
    }

    /**
     * Creates all tables and indexes idempotently (every statement is {@code IF NOT EXISTS}).
     * Runs on one connection at boot before serving.
     */
    public static void create(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String ddl : TABLES) {
                st.execute(ddl);
            }
            for (String ddl : INDEXES) {
                st.execute(ddl);
            }
        }
    }
}
