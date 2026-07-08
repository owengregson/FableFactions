package dev.fablemc.factions.core.config;

/**
 * Interns every YAML path the {@link ConfigParser} reads, grouped by file and section (CONTRACTS
 * §6.3: the reference config tree, verbatim). Centralizing the paths keeps the parser free of
 * scattered string literals and makes an accidental typo a one-line diff rather than a silent
 * fallback-to-default.
 *
 * <p><b>Owning thread(s):</b> constants, referenced on any thread. <b>Mutability:</b> static-only
 * constant holder (never instantiated). <b>Reducer rule:</b> n/a — pure {@code :core} config keys.
 */
public final class ConfigKeys {

    private ConfigKeys() {
    }

    // ── config.yml: meta ────────────────────────────────────────────────────────────────────
    /** Schema version of {@code config.yml}, consumed by the migration hook. */
    public static final String CONFIG_VERSION = "config-version";

    // ── config.yml: factions.* limits ───────────────────────────────────────────────────────
    public static final String MAX_MEMBERS = "factions.max-members";
    public static final String MAX_WARPS = "factions.max-warps";
    public static final String MAX_TEAM_CHESTS = "factions.max-team-chests";
    public static final String MAX_ALLIES = "factions.max-allies";
    public static final String MAX_TRUCES = "factions.max-truces";
    public static final String TEAM_CHEST_DEFAULT_NAME = "factions.team-chest.default-name";
    public static final String INVITES_TTL_HOURS = "factions.invites.ttl-hours";

    // ── config.yml: factions.language.* ─────────────────────────────────────────────────────
    public static final String LANGUAGE_DEFAULT = "factions.language.default";
    public static final String LANGUAGE_ALLOW_OVERRIDE = "factions.language.allow-player-override";
    public static final String LANGUAGE_COMMAND_OPENS_GUI = "factions.language.command-opens-gui";
    public static final String LANGUAGE_COMMAND_OPEN_GUI_AFTER_SET =
            "factions.language.command-open-gui-after-set";
    public static final String LANGUAGE_VISIBLE_LOCALES = "factions.language.visible-locales";

    // ── config.yml: factions.power.* ────────────────────────────────────────────────────────
    /** Canonical max-power key A (aliased with {@link #POWER_CONSTRAINTS_MAX_POWER}). */
    public static final String POWER_PER_PLAYER_MAX = "factions.power.per-player-max";
    public static final String POWER_REGEN_PER_SECOND = "factions.power.regen-per-second";
    public static final String POWER_LOSS_ON_DEATH = "factions.power.loss-on-death";
    public static final String POWER_GRACE_PERIOD_SECONDS = "factions.power.grace-period-seconds";
    public static final String POWER_TICK_INTERVAL_SECONDS = "factions.power.tick-interval-seconds";
    public static final String POWER_GAIN_ON_KILL_ENABLED = "factions.power.gain-on-kill.enabled";
    public static final String POWER_GAIN_ON_KILL_AMOUNT = "factions.power.gain-on-kill.amount";
    public static final String POWER_KILL_SCALE_ENABLED = "factions.power.gain-on-kill.scale.enabled";
    public static final String POWER_KILL_SCALE_MIN = "factions.power.gain-on-kill.scale.min-factor";
    public static final String POWER_KILL_SCALE_MAX = "factions.power.gain-on-kill.scale.max-factor";
    public static final String POWER_INACTIVE_ENABLED = "factions.power.inactive-exclusion.enabled";
    public static final String POWER_INACTIVE_DAYS = "factions.power.inactive-exclusion.days";
    public static final String POWER_DEATH_STREAK_ENABLED = "factions.power.death-streak.enabled";
    public static final String POWER_DEATH_STREAK_WINDOW = "factions.power.death-streak.window-seconds";
    public static final String POWER_DEATH_STREAK_MULT = "factions.power.death-streak.multiplier";
    public static final String POWER_BUY_ENABLED = "factions.power.buy.enabled";
    public static final String POWER_BUY_COST = "factions.power.buy.cost-per-point";
    public static final String POWER_BUY_MAX = "factions.power.buy.max-per-purchase";
    public static final String POWER_SRC_REGEN_ONLINE_ENABLED =
            "factions.power.sources.regen-online.enabled";
    public static final String POWER_SRC_REGEN_ONLINE_AMOUNT =
            "factions.power.sources.regen-online.amount";
    public static final String POWER_SRC_REGEN_OFFLINE_ENABLED =
            "factions.power.sources.regen-offline.enabled";
    public static final String POWER_SRC_REGEN_OFFLINE_AMOUNT =
            "factions.power.sources.regen-offline.amount";
    public static final String POWER_SRC_DEATH_LOSS_ENABLED = "factions.power.sources.death-loss.enabled";
    public static final String POWER_SRC_DEATH_LOSS_AMOUNT = "factions.power.sources.death-loss.amount";
    public static final String POWER_SRC_KILL_GAIN_ENABLED = "factions.power.sources.kill-gain.enabled";
    public static final String POWER_SRC_KILL_GAIN_AMOUNT = "factions.power.sources.kill-gain.amount";
    public static final String POWER_SRC_BUY_ENABLED = "factions.power.sources.buy.enabled";
    public static final String POWER_CONSTRAINTS_MIN = "factions.power.constraints.min-power";
    /** Canonical max-power key B (aliased with {@link #POWER_PER_PLAYER_MAX}). */
    public static final String POWER_CONSTRAINTS_MAX_POWER = "factions.power.constraints.max-power";
    public static final String POWER_CONSTRAINTS_MAX_CHANGE =
            "factions.power.constraints.max-change-per-event";
    public static final String POWER_MULTIPLIERS_WORLDS = "factions.power.multipliers.worlds";
    public static final String POWER_ZONE_SAFEZONE = "factions.power.multipliers.zones.safezone";
    public static final String POWER_ZONE_WARZONE = "factions.power.multipliers.zones.warzone";
    public static final String POWER_ZONE_OWN_CLAIMED = "factions.power.multipliers.zones.own_claimed";
    public static final String POWER_ZONE_ENEMY_CLAIMED = "factions.power.multipliers.zones.enemy_claimed";
    public static final String POWER_ZONE_WILDERNESS = "factions.power.multipliers.zones.wilderness";
    public static final String POWER_FREEZE_BLOCKS_AUTOMATIC = "factions.power.freeze.blocks-automatic";
    public static final String POWER_FREEZE_BLOCKS_REGEN = "factions.power.freeze.blocks-regen";
    public static final String POWER_FREEZE_ALLOW_BYPASS = "factions.power.freeze.allow-admin-bypass";
    public static final String POWER_NOTIFY_ACTOR = "factions.power.notifications.actor";
    public static final String POWER_NOTIFY_FACTION = "factions.power.notifications.faction";
    public static final String POWER_NOTIFY_STAFF = "factions.power.notifications.staff";

    // ── config.yml: pagination / display ────────────────────────────────────────────────────
    public static final String MAP_ONCE_RADIUS = "factions.map.once-radius";
    public static final String LIST_PAGE_SIZE = "factions.list.page-size";
    public static final String TOP_PAGE_SIZE = "factions.top.page-size";
    public static final String INFO_SHOW_ALLIES = "factions.info.relations.show-allies";
    public static final String INFO_SHOW_TRUCES = "factions.info.relations.show-truces";
    public static final String INFO_SHOW_NEUTRALS = "factions.info.relations.show-neutrals";
    public static final String INFO_SHOW_ENEMIES = "factions.info.relations.show-enemies";
    public static final String WARP_LIST_PAGE_SIZE = "factions.warp.list.page-size";
    public static final String AUDIT_PAGE_SIZE = "factions.audit.page-size";

    // ── config.yml: factions.land.* ─────────────────────────────────────────────────────────
    public static final String LAND_BUFFER_ZONE = "factions.land.buffer-zone";
    public static final String LAND_MAX_PER_COMMAND = "factions.land.max-per-command";
    public static final String LAND_PER_POWER = "factions.land.per-power";
    public static final String LAND_MAX = "factions.land.max";

    // ── config.yml: factions.economy.* ──────────────────────────────────────────────────────
    public static final String ECON_ENABLED = "factions.economy.enabled";
    public static final String ECON_COST_CREATE = "factions.economy.cost-create";
    public static final String ECON_COST_CLAIM = "factions.economy.cost-claim";
    public static final String ECON_TAX_ENABLED = "factions.economy.tax.enabled";
    public static final String ECON_TAX_RATE = "factions.economy.tax.rate";
    public static final String ECON_TAX_INTERVAL_HOURS = "factions.economy.tax.interval-hours";
    public static final String ECON_TAX_MIN_BANK = "factions.economy.tax.min-bank-balance";
    public static final String ECON_TAX_MIN_CHARGE = "factions.economy.tax.min-charge-amount";
    public static final String ECON_HISTORY_PAGE_SIZE = "factions.economy.bank.history.page-size";

    // ── config.yml: factions.fly.* ──────────────────────────────────────────────────────────
    public static final String FLY_ENABLED = "factions.fly.enabled";
    public static final String FLY_DISABLE_ON_THREAT = "factions.fly.disable-on-threat";
    public static final String FLY_REQUIRE_OWN_TERRITORY = "factions.fly.require-own-territory";

    // ── config.yml: factions.flags.* (default + player-editable per flag) ───────────────────
    public static final String FLAG_PVP_DEFAULT = "factions.flags.pvp.default";
    public static final String FLAG_PVP_EDITABLE = "factions.flags.pvp.player-editable";
    public static final String FLAG_FRIENDLY_FIRE_DEFAULT = "factions.flags.friendly-fire.default";
    public static final String FLAG_FRIENDLY_FIRE_EDITABLE = "factions.flags.friendly-fire.player-editable";
    public static final String FLAG_EXPLOSIONS_DEFAULT = "factions.flags.explosions.default";
    public static final String FLAG_EXPLOSIONS_EDITABLE = "factions.flags.explosions.player-editable";
    public static final String FLAG_FIRE_SPREAD_DEFAULT = "factions.flags.fire-spread.default";
    public static final String FLAG_FIRE_SPREAD_EDITABLE = "factions.flags.fire-spread.player-editable";
    public static final String FLAG_OPEN_DEFAULT = "factions.flags.open.default";
    public static final String FLAG_OPEN_EDITABLE = "factions.flags.open.player-editable";

    // ── config.yml: factions.chat.* ─────────────────────────────────────────────────────────
    public static final String CHAT_SHOW_TAG = "factions.chat.show-tag";
    public static final String CHAT_TAG_FORMAT = "factions.chat.tag-format";

    // ── config.yml: factions.metrics.* / updates.* ──────────────────────────────────────────
    public static final String METRICS_BSTATS_ENABLED = "factions.metrics.bstats.enabled";
    public static final String UPDATES_ENABLED = "factions.updates.enabled";
    public static final String UPDATES_NOTIFY_OPS_ON_JOIN = "factions.updates.notify-ops-on-join";

    // ── config.yml: factions.zones.* ────────────────────────────────────────────────────────
    public static final String ZONE_SAFE_ENABLED = "factions.zones.safe-zone.enabled";
    public static final String ZONE_WAR_ENABLED = "factions.zones.war-zone.enabled";

    // ── config.yml: factions.overclaiming.* ─────────────────────────────────────────────────
    public static final String OVERCLAIM_ENABLED = "factions.overclaiming.enabled";
    public static final String OVERCLAIM_REQUIRE_ENEMY = "factions.overclaiming.require-enemy-relation";
    public static final String OVERCLAIM_OFFLINE_PROTECTION =
            "factions.overclaiming.offline-protection.enabled";

    // ── config.yml: factions.raidable.* ─────────────────────────────────────────────────────
    public static final String RAIDABLE_BROADCAST_ENABLED = "factions.raidable.broadcast.enabled";
    public static final String RAIDABLE_BROADCAST_SERVER_WIDE = "factions.raidable.broadcast.server-wide";

    // ── config.yml: factions.war.shield.* ───────────────────────────────────────────────────
    public static final String WAR_SHIELD_ENABLED = "factions.war.shield.enabled";
    public static final String WAR_SHIELD_MAX_DURATION_HOURS = "factions.war.shield.max-duration-hours";

    // ── config.yml: factions.merge.* ────────────────────────────────────────────────────────
    public static final String MERGE_ENABLED = "factions.merge.enabled";

    // ── database.yml ────────────────────────────────────────────────────────────────────────
    public static final String DB_TYPE = "type";
    public static final String DB_H2_FILE = "h2.file";
    public static final String DB_MYSQL_HOST = "mysql.host";
    public static final String DB_MYSQL_PORT = "mysql.port";
    public static final String DB_MYSQL_DATABASE = "mysql.database";
    public static final String DB_MYSQL_USERNAME = "mysql.username";
    public static final String DB_MYSQL_POOL_SIZE = "mysql.pool-size";
    public static final String DB_DEBUG_SQL_LOGGING = "debug.sql-logging";

    // ── roles.yml ───────────────────────────────────────────────────────────────────────────
    public static final String ROLES_OVERRIDES_ENABLED = "roles.overrides.enabled";
    public static final String ROLES_CUSTOM_ENABLED = "roles.custom.enabled";
    public static final String ROLES_CUSTOM_MIN_PRIORITY = "roles.custom.min-priority";
    public static final String ROLES_CUSTOM_MAX_PRIORITY = "roles.custom.max-priority";
    public static final String ROLES_CUSTOM_MAX_PER_FACTION = "roles.custom.max-per-faction";
    public static final String ROLES_PREFIX_ENABLED = "roles.prefix.enabled";
    public static final String ROLES_PREFIX_MAX_LENGTH = "roles.prefix.max-length";
    public static final String ROLES_DEFAULT_OWNER_PREFIX = "roles.defaults.owner.prefix";
    public static final String ROLES_DEFAULT_OFFICER_PREFIX = "roles.defaults.officer.prefix";
    public static final String ROLES_DEFAULT_MEMBER_PREFIX = "roles.defaults.member.prefix";

    // ── notifications.yml ───────────────────────────────────────────────────────────────────
    public static final String INBOX_ENABLED = "inbox.enabled";
    public static final String INBOX_MAX_PER_LOGIN = "inbox.max-per-login";
    public static final String MEMBER_NOTIFY_PLAYER_JOINED = "member.notify-player-joined";
    public static final String ECONOMY_TAX_NOTIFY_MEMBERS = "economy.tax.notify-members";
    public static final String COUNTDOWN_ENABLED = "countdown.enabled";
    public static final String COUNTDOWN_DURATION_SECONDS = "countdown.announcement-duration-seconds";
    public static final String COUNTDOWN_DISPLAY_TYPES = "countdown.display-types";

    // ── pre-defined.yml ─────────────────────────────────────────────────────────────────────
    public static final String PREDEFINED_ENABLED = "enabled";
    public static final String PREDEFINED_CASE_SENSITIVE = "case-sensitive";
    public static final String PREDEFINED_BLOCK_DISBAND = "block-disband";
    public static final String PREDEFINED_FACTIONS = "factions";

    // ── gui.yml (kernel carries only the top-level toggles; menus parse platform-side) ──────
    public static final String GUI_ENABLED = "gui.enabled";
    public static final String GUI_DEFAULT_MENU = "gui.default-menu";
    public static final String GUI_LANGUAGE_MENU = "gui.language-menu";
}
