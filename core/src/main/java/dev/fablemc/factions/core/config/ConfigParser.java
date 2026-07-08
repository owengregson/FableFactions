package dev.fablemc.factions.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.kernel.config.BakedTables;
import dev.fablemc.factions.kernel.config.ChatConfig;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.config.EconomyConfig;
import dev.fablemc.factions.kernel.config.FlyConfig;
import dev.fablemc.factions.kernel.config.GuiModelConfig;
import dev.fablemc.factions.kernel.config.LandConfig;
import dev.fablemc.factions.kernel.config.NotificationRouting;
import dev.fablemc.factions.kernel.config.PowerConfig;
import dev.fablemc.factions.kernel.config.PredefinedConfig;
import dev.fablemc.factions.kernel.config.RelationConfig;
import dev.fablemc.factions.kernel.config.RoleConfig;
import dev.fablemc.factions.kernel.config.StorageConfigView;
import dev.fablemc.factions.kernel.config.ZoneConfig;
import dev.fablemc.factions.kernel.ids.ChunkKeys;

/**
 * Turns the six parsed YAML files into one immutable {@link ConfigImage} with warn-and-fallback
 * per-key readers and the AM-14 {@link BakedTables} derivation (proposal-C §9.1). An absent key
 * silently takes the reference default; a <em>present but wrong-typed</em> key records a
 * human-readable issue (surfaced by {@code /fa reload}) and falls back to the default, so a
 * malformed config degrades gracefully rather than aborting the swap.
 *
 * <p><b>Owning thread(s):</b> {@link #parse} runs off the writer — on the boot thread or the async
 * reload thread; it touches no Bukkit runtime and no kernel state. <b>Mutability:</b> static-only,
 * stateless; every call produces a fresh immutable image. <b>Reducer rule:</b> the produced image
 * reaches the kernel only through a {@code SwapConfig} intent (never mutated in place).
 *
 * <p>The aliased max-power keys ({@code power.per-player-max} and {@code power.constraints.max-power})
 * collapse to one canonical value consumed by both fields, warning when the two disagree
 * (kills logic-bug 22).
 */
public final class ConfigParser {

    /** Reference default for the canonical max-power value (both aliases). */
    private static final double DEFAULT_MAX_POWER = 10.0;

    /** ZoneContext ordinals used to index the baked zone-multiplier array. */
    private static final int ZONE_SAFEZONE = 0;
    private static final int ZONE_WARZONE = 1;
    private static final int ZONE_OWN_CLAIMED = 2;
    private static final int ZONE_ENEMY_CLAIMED = 3;
    private static final int ZONE_WILDERNESS = 4;

    /** The default EzCountdown display-type list when none is configured (or the list is empty). */
    private static final String[] DEFAULT_DISPLAY_TYPES = {"ACTION_BAR"};

    private ConfigParser() {
    }

    /**
     * The six loaded YAML files that back one {@link ConfigImage}. Constructed by {@link ConfigFiles}
     * at boot / reload; tests build it directly from in-memory {@link YamlConfiguration}s.
     */
    public record Sources(YamlConfiguration config, YamlConfiguration database,
                          YamlConfiguration roles, YamlConfiguration notifications,
                          YamlConfiguration predefined, YamlConfiguration gui) {

        /** An all-empty bundle (every reader falls back to its default → {@link ConfigImage#defaults()}). */
        public static Sources empty() {
            return new Sources(new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration(),
                    new YamlConfiguration(), new YamlConfiguration(), new YamlConfiguration());
        }
    }

    /**
     * Parses {@code sources} into a complete {@link ConfigImage}, appending any per-key issues to
     * {@code issues}. {@code worldIndex} resolves a world name to its {@code worldIdx} for the baked
     * world-multiplier array and predefined presets; when {@code null} those name-keyed values are
     * skipped (world resolution happens at boot, so the off-thread parse can proceed world-blind and
     * the boot layer re-bakes materials/worlds via {@link ConfigImage#withBaked}).
     */
    public static ConfigImage parse(Sources sources, @Nullable ToIntFunction<String> worldIndex,
                                    List<String> issues) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(issues, "issues");
        YamlConfiguration c = sources.config();

        PowerConfig power = parsePower(c, worldIndex, issues);
        return new ConfigImage(
                parseLimits(c, issues),
                parseLanguage(c, issues),
                parseDisplay(c, issues),
                parseUpdates(c, issues),
                parseMetrics(c, issues),
                bool(c, ConfigKeys.MERGE_ENABLED, false, issues),
                parseFlagDefaults(c, issues),
                power,
                parseLand(c, issues),
                parseEconomy(c, issues),
                parseFly(c, issues),
                parseChat(c, issues),
                parseRelation(c, issues),
                parseRoles(sources.roles(), issues),
                parseZones(c, issues),
                parseNotifications(sources.notifications(), issues),
                parseGui(sources.gui(), issues),
                parsePredefined(sources.predefined(), worldIndex, issues),
                parseStorage(sources.database(), issues),
                bakeTables(power, worldIndex));
    }

    // ── section parsers ─────────────────────────────────────────────────────────────────────

    private static ConfigImage.Limits parseLimits(ConfigurationSection c, List<String> issues) {
        return new ConfigImage.Limits(
                integer(c, ConfigKeys.MAX_MEMBERS, 50, issues),
                integer(c, ConfigKeys.MAX_WARPS, 10, issues),
                integer(c, ConfigKeys.MAX_TEAM_CHESTS, 1, issues),
                str(c, ConfigKeys.TEAM_CHEST_DEFAULT_NAME, "default", issues),
                integer(c, ConfigKeys.INVITES_TTL_HOURS, 72, issues));
    }

    private static ConfigImage.Language parseLanguage(ConfigurationSection c, List<String> issues) {
        return new ConfigImage.Language(
                str(c, ConfigKeys.LANGUAGE_DEFAULT, "en", issues),
                bool(c, ConfigKeys.LANGUAGE_ALLOW_OVERRIDE, true, issues),
                bool(c, ConfigKeys.LANGUAGE_COMMAND_OPENS_GUI, true, issues),
                bool(c, ConfigKeys.LANGUAGE_COMMAND_OPEN_GUI_AFTER_SET, true, issues),
                stringArray(c, ConfigKeys.LANGUAGE_VISIBLE_LOCALES));
    }

    private static ConfigImage.Display parseDisplay(ConfigurationSection c, List<String> issues) {
        return new ConfigImage.Display(
                integer(c, ConfigKeys.MAP_ONCE_RADIUS, 3, issues),
                integer(c, ConfigKeys.LIST_PAGE_SIZE, 8, issues),
                integer(c, ConfigKeys.TOP_PAGE_SIZE, 8, issues),
                integer(c, ConfigKeys.WARP_LIST_PAGE_SIZE, 8, issues),
                integer(c, ConfigKeys.AUDIT_PAGE_SIZE, 10, issues),
                bool(c, ConfigKeys.INFO_SHOW_ALLIES, true, issues),
                bool(c, ConfigKeys.INFO_SHOW_TRUCES, false, issues),
                bool(c, ConfigKeys.INFO_SHOW_NEUTRALS, false, issues),
                bool(c, ConfigKeys.INFO_SHOW_ENEMIES, false, issues));
    }

    private static ConfigImage.Updates parseUpdates(ConfigurationSection c, List<String> issues) {
        return new ConfigImage.Updates(
                bool(c, ConfigKeys.UPDATES_ENABLED, true, issues),
                bool(c, ConfigKeys.UPDATES_NOTIFY_OPS_ON_JOIN, true, issues),
                "fablefactions", "fablemc", "FableFactions");
    }

    private static ConfigImage.Metrics parseMetrics(ConfigurationSection c, List<String> issues) {
        return new ConfigImage.Metrics(bool(c, ConfigKeys.METRICS_BSTATS_ENABLED, true, issues), 31240);
    }

    private static ConfigImage.FlagDefaults parseFlagDefaults(ConfigurationSection c, List<String> issues) {
        boolean[] def = {
                bool(c, ConfigKeys.FLAG_PVP_DEFAULT, true, issues),
                bool(c, ConfigKeys.FLAG_FRIENDLY_FIRE_DEFAULT, false, issues),
                bool(c, ConfigKeys.FLAG_EXPLOSIONS_DEFAULT, false, issues),
                bool(c, ConfigKeys.FLAG_FIRE_SPREAD_DEFAULT, false, issues),
                bool(c, ConfigKeys.FLAG_OPEN_DEFAULT, false, issues),
        };
        boolean[] editable = {
                bool(c, ConfigKeys.FLAG_PVP_EDITABLE, true, issues),
                bool(c, ConfigKeys.FLAG_FRIENDLY_FIRE_EDITABLE, true, issues),
                bool(c, ConfigKeys.FLAG_EXPLOSIONS_EDITABLE, true, issues),
                bool(c, ConfigKeys.FLAG_FIRE_SPREAD_EDITABLE, true, issues),
                bool(c, ConfigKeys.FLAG_OPEN_EDITABLE, true, issues),
        };
        return new ConfigImage.FlagDefaults(def, editable);
    }

    private static PowerConfig parsePower(ConfigurationSection c, @Nullable ToIntFunction<String> worldIndex,
                                          List<String> issues) {
        double canonicalMax = canonicalMaxPower(c, issues);
        String[] worldNames;
        double[] worldValues;
        ConfigurationSection worlds = c.getConfigurationSection(ConfigKeys.POWER_MULTIPLIERS_WORLDS);
        if (worlds == null) {
            worldNames = new String[0];
            worldValues = new double[0];
        } else {
            List<String> names = new ArrayList<>();
            List<Double> values = new ArrayList<>();
            for (String key : worlds.getKeys(false)) {
                Object raw = worlds.get(key);
                if (raw instanceof Number number) {
                    names.add(key);
                    values.add(number.doubleValue());
                } else {
                    issues.add(ConfigKeys.POWER_MULTIPLIERS_WORLDS + "." + key
                            + " is not a number; ignoring");
                }
            }
            worldNames = names.toArray(new String[0]);
            worldValues = new double[values.size()];
            for (int i = 0; i < worldValues.length; i++) {
                worldValues[i] = values.get(i);
            }
        }
        PowerConfig.ZoneMultipliers zones = new PowerConfig.ZoneMultipliers(
                dbl(c, ConfigKeys.POWER_ZONE_SAFEZONE, 1.0, issues),
                dbl(c, ConfigKeys.POWER_ZONE_WARZONE, 1.0, issues),
                dbl(c, ConfigKeys.POWER_ZONE_OWN_CLAIMED, 1.0, issues),
                dbl(c, ConfigKeys.POWER_ZONE_ENEMY_CLAIMED, 1.0, issues),
                dbl(c, ConfigKeys.POWER_ZONE_WILDERNESS, 1.0, issues));
        return new PowerConfig(
                canonicalMax,
                dbl(c, ConfigKeys.POWER_REGEN_PER_SECOND, 0.1, issues),
                dbl(c, ConfigKeys.POWER_LOSS_ON_DEATH, 4.0, issues),
                integer(c, ConfigKeys.POWER_GRACE_PERIOD_SECONDS, 3600, issues),
                integer(c, ConfigKeys.POWER_TICK_INTERVAL_SECONDS, 60, issues),
                bool(c, ConfigKeys.POWER_GAIN_ON_KILL_ENABLED, true, issues),
                dbl(c, ConfigKeys.POWER_GAIN_ON_KILL_AMOUNT, 2.0, issues),
                bool(c, ConfigKeys.POWER_KILL_SCALE_ENABLED, false, issues),
                dbl(c, ConfigKeys.POWER_KILL_SCALE_MIN, 0.25, issues),
                dbl(c, ConfigKeys.POWER_KILL_SCALE_MAX, 2.0, issues),
                bool(c, ConfigKeys.POWER_INACTIVE_ENABLED, false, issues),
                integer(c, ConfigKeys.POWER_INACTIVE_DAYS, 7, issues),
                bool(c, ConfigKeys.POWER_DEATH_STREAK_ENABLED, false, issues),
                integer(c, ConfigKeys.POWER_DEATH_STREAK_WINDOW, 600, issues),
                dbl(c, ConfigKeys.POWER_DEATH_STREAK_MULT, 1.5, issues),
                bool(c, ConfigKeys.POWER_BUY_ENABLED, false, issues),
                dbl(c, ConfigKeys.POWER_BUY_COST, 100.0, issues),
                dbl(c, ConfigKeys.POWER_BUY_MAX, 5.0, issues),
                bool(c, ConfigKeys.POWER_SRC_REGEN_ONLINE_ENABLED, true, issues),
                bool(c, ConfigKeys.POWER_SRC_REGEN_OFFLINE_ENABLED, true, issues),
                bool(c, ConfigKeys.POWER_SRC_DEATH_LOSS_ENABLED, true, issues),
                bool(c, ConfigKeys.POWER_SRC_KILL_GAIN_ENABLED, true, issues),
                bool(c, ConfigKeys.POWER_SRC_BUY_ENABLED, true, issues),
                dbl(c, ConfigKeys.POWER_SRC_REGEN_ONLINE_AMOUNT, 6.0, issues),
                dbl(c, ConfigKeys.POWER_SRC_REGEN_OFFLINE_AMOUNT, 3.0, issues),
                dbl(c, ConfigKeys.POWER_SRC_DEATH_LOSS_AMOUNT, 4.0, issues),
                dbl(c, ConfigKeys.POWER_SRC_KILL_GAIN_AMOUNT, 2.0, issues),
                dbl(c, ConfigKeys.POWER_CONSTRAINTS_MIN, 0.0, issues),
                canonicalMax,
                dbl(c, ConfigKeys.POWER_CONSTRAINTS_MAX_CHANGE, 0.0, issues),
                bool(c, ConfigKeys.POWER_FREEZE_BLOCKS_AUTOMATIC, true, issues),
                bool(c, ConfigKeys.POWER_FREEZE_BLOCKS_REGEN, true, issues),
                bool(c, ConfigKeys.POWER_FREEZE_ALLOW_BYPASS, true, issues),
                bool(c, ConfigKeys.POWER_NOTIFY_ACTOR, true, issues),
                bool(c, ConfigKeys.POWER_NOTIFY_FACTION, false, issues),
                bool(c, ConfigKeys.POWER_NOTIFY_STAFF, false, issues),
                zones,
                worldNames,
                worldValues);
    }

    /** Collapses the two aliased max-power keys to one value, warning when both are set and differ. */
    private static double canonicalMaxPower(ConfigurationSection c, List<String> issues) {
        Double perPlayer = optionalDouble(c, ConfigKeys.POWER_PER_PLAYER_MAX, issues);
        Double constraint = optionalDouble(c, ConfigKeys.POWER_CONSTRAINTS_MAX_POWER, issues);
        if (perPlayer != null && constraint != null && Double.compare(perPlayer, constraint) != 0) {
            issues.add(ConfigKeys.POWER_PER_PLAYER_MAX + " (" + perPlayer + ") and "
                    + ConfigKeys.POWER_CONSTRAINTS_MAX_POWER + " (" + constraint
                    + ") disagree; using " + ConfigKeys.POWER_PER_PLAYER_MAX);
            return perPlayer;
        }
        if (perPlayer != null) {
            return perPlayer;
        }
        if (constraint != null) {
            return constraint;
        }
        return DEFAULT_MAX_POWER;
    }

    private static LandConfig parseLand(ConfigurationSection c, List<String> issues) {
        return new LandConfig(
                integer(c, ConfigKeys.LAND_BUFFER_ZONE, 0, issues),
                integer(c, ConfigKeys.LAND_MAX_PER_COMMAND, 200, issues),
                dbl(c, ConfigKeys.LAND_PER_POWER, 1.0, issues),
                integer(c, ConfigKeys.LAND_MAX, 500, issues),
                bool(c, ConfigKeys.OVERCLAIM_ENABLED, false, issues),
                bool(c, ConfigKeys.OVERCLAIM_REQUIRE_ENEMY, true, issues),
                bool(c, ConfigKeys.OVERCLAIM_OFFLINE_PROTECTION, false, issues),
                bool(c, ConfigKeys.RAIDABLE_BROADCAST_ENABLED, true, issues),
                bool(c, ConfigKeys.RAIDABLE_BROADCAST_SERVER_WIDE, false, issues),
                bool(c, ConfigKeys.WAR_SHIELD_ENABLED, false, issues),
                integer(c, ConfigKeys.WAR_SHIELD_MAX_DURATION_HOURS, 8, issues));
    }

    private static EconomyConfig parseEconomy(ConfigurationSection c, List<String> issues) {
        return new EconomyConfig(
                bool(c, ConfigKeys.ECON_ENABLED, true, issues),
                dbl(c, ConfigKeys.ECON_COST_CREATE, 50.0, issues),
                dbl(c, ConfigKeys.ECON_COST_CLAIM, 100.0, issues),
                bool(c, ConfigKeys.ECON_TAX_ENABLED, false, issues),
                dbl(c, ConfigKeys.ECON_TAX_RATE, 0.05, issues),
                integer(c, ConfigKeys.ECON_TAX_INTERVAL_HOURS, 24, issues),
                dbl(c, ConfigKeys.ECON_TAX_MIN_BANK, 0.0, issues),
                dbl(c, ConfigKeys.ECON_TAX_MIN_CHARGE, 0.01, issues),
                integer(c, ConfigKeys.ECON_HISTORY_PAGE_SIZE, 8, issues));
    }

    private static FlyConfig parseFly(ConfigurationSection c, List<String> issues) {
        return new FlyConfig(
                bool(c, ConfigKeys.FLY_ENABLED, true, issues),
                bool(c, ConfigKeys.FLY_DISABLE_ON_THREAT, true, issues),
                bool(c, ConfigKeys.FLY_REQUIRE_OWN_TERRITORY, true, issues));
    }

    private static ChatConfig parseChat(ConfigurationSection c, List<String> issues) {
        return new ChatConfig(
                bool(c, ConfigKeys.CHAT_SHOW_TAG, false, issues),
                str(c, ConfigKeys.CHAT_TAG_FORMAT, "<gray>[<gold>{faction_name}</gold>]</gray> ", issues));
    }

    private static RelationConfig parseRelation(ConfigurationSection c, List<String> issues) {
        return new RelationConfig(
                integer(c, ConfigKeys.MAX_ALLIES, 5, issues),
                integer(c, ConfigKeys.MAX_TRUCES, 5, issues));
    }

    private static RoleConfig parseRoles(ConfigurationSection roles, List<String> issues) {
        return new RoleConfig(
                bool(roles, ConfigKeys.ROLES_CUSTOM_ENABLED, true, issues),
                bool(roles, ConfigKeys.ROLES_OVERRIDES_ENABLED, false, issues),
                integer(roles, ConfigKeys.ROLES_CUSTOM_MIN_PRIORITY, 11, issues),
                integer(roles, ConfigKeys.ROLES_CUSTOM_MAX_PRIORITY, 99, issues),
                integer(roles, ConfigKeys.ROLES_CUSTOM_MAX_PER_FACTION, 0, issues),
                bool(roles, ConfigKeys.ROLES_PREFIX_ENABLED, true, issues),
                integer(roles, ConfigKeys.ROLES_PREFIX_MAX_LENGTH, 32, issues),
                str(roles, ConfigKeys.ROLES_DEFAULT_OWNER_PREFIX, "", issues),
                str(roles, ConfigKeys.ROLES_DEFAULT_OFFICER_PREFIX, "", issues),
                str(roles, ConfigKeys.ROLES_DEFAULT_MEMBER_PREFIX, "", issues));
    }

    private static ZoneConfig parseZones(ConfigurationSection c, List<String> issues) {
        return new ZoneConfig(
                bool(c, ConfigKeys.ZONE_SAFE_ENABLED, true, issues),
                bool(c, ConfigKeys.ZONE_WAR_ENABLED, true, issues));
    }

    private static NotificationRouting parseNotifications(ConfigurationSection n, List<String> issues) {
        String[] displayTypes = stringArray(n, ConfigKeys.COUNTDOWN_DISPLAY_TYPES);
        if (displayTypes.length == 0) {
            displayTypes = DEFAULT_DISPLAY_TYPES.clone();
        } else {
            for (int i = 0; i < displayTypes.length; i++) {
                displayTypes[i] = displayTypes[i].toUpperCase(Locale.ROOT);
            }
        }
        return new NotificationRouting(
                bool(n, ConfigKeys.INBOX_ENABLED, true, issues),
                integer(n, ConfigKeys.INBOX_MAX_PER_LOGIN, 20, issues),
                bool(n, ConfigKeys.MEMBER_NOTIFY_PLAYER_JOINED, true, issues),
                bool(n, ConfigKeys.ECONOMY_TAX_NOTIFY_MEMBERS, true, issues),
                bool(n, ConfigKeys.COUNTDOWN_ENABLED, true, issues),
                longValue(n, ConfigKeys.COUNTDOWN_DURATION_SECONDS, 8L, issues),
                displayTypes);
    }

    private static GuiModelConfig parseGui(ConfigurationSection gui, List<String> issues) {
        return new GuiModelConfig(
                bool(gui, ConfigKeys.GUI_ENABLED, true, issues),
                str(gui, ConfigKeys.GUI_DEFAULT_MENU, "main", issues),
                str(gui, ConfigKeys.GUI_LANGUAGE_MENU, "language", issues));
    }

    private static StorageConfigView parseStorage(ConfigurationSection db, List<String> issues) {
        return new StorageConfigView(
                str(db, ConfigKeys.DB_TYPE, "h2", issues),
                str(db, ConfigKeys.DB_H2_FILE, "data/factions", issues),
                str(db, ConfigKeys.DB_MYSQL_HOST, "localhost", issues),
                integer(db, ConfigKeys.DB_MYSQL_PORT, 3306, issues),
                str(db, ConfigKeys.DB_MYSQL_DATABASE, "factions", issues),
                str(db, ConfigKeys.DB_MYSQL_USERNAME, "root", issues),
                integer(db, ConfigKeys.DB_MYSQL_POOL_SIZE, 10, issues),
                bool(db, ConfigKeys.DB_DEBUG_SQL_LOGGING, false, issues));
    }

    private static PredefinedConfig parsePredefined(ConfigurationSection pre,
                                                    @Nullable ToIntFunction<String> worldIndex,
                                                    List<String> issues) {
        boolean enabled = bool(pre, ConfigKeys.PREDEFINED_ENABLED, false, issues);
        boolean caseSensitive = bool(pre, ConfigKeys.PREDEFINED_CASE_SENSITIVE, false, issues);
        boolean blockDisband = bool(pre, ConfigKeys.PREDEFINED_BLOCK_DISBAND, true, issues);
        PredefinedConfig.Preset[] presets = parsePresets(pre, worldIndex, issues);
        return new PredefinedConfig(enabled, caseSensitive, blockDisband, presets);
    }

    private static PredefinedConfig.Preset[] parsePresets(ConfigurationSection pre,
                                                          @Nullable ToIntFunction<String> worldIndex,
                                                          List<String> issues) {
        ConfigurationSection factions = pre.getConfigurationSection(ConfigKeys.PREDEFINED_FACTIONS);
        if (factions == null) {
            return new PredefinedConfig.Preset[0];
        }
        List<PredefinedConfig.Preset> out = new ArrayList<>();
        for (String name : factions.getKeys(false)) {
            ConfigurationSection sub = factions.getConfigurationSection(name);
            if (sub == null) {
                continue;
            }
            PredefinedConfig.Home home = parsePresetHome(sub.getConfigurationSection("home"), worldIndex);
            List<Map<?, ?>> claimMaps = sub.getMapList("claims");
            int[] claimWorlds = new int[claimMaps.size()];
            long[] claimKeys = new long[claimMaps.size()];
            int n = 0;
            for (Map<?, ?> claim : claimMaps) {
                Integer x = asInt(claim.get("x"));
                Integer z = asInt(claim.get("z"));
                if (x == null || z == null) {
                    issues.add("predefined." + name + ": a claim is missing x/z; ignoring");
                    continue;
                }
                claimWorlds[n] = resolveWorld(claim.get("world"), worldIndex);
                claimKeys[n] = ChunkKeys.key(x, z);
                n++;
            }
            if (n != claimMaps.size()) {
                claimWorlds = java.util.Arrays.copyOf(claimWorlds, n);
                claimKeys = java.util.Arrays.copyOf(claimKeys, n);
            }
            out.add(new PredefinedConfig.Preset(name, claimWorlds, claimKeys, home));
        }
        return out.toArray(new PredefinedConfig.Preset[0]);
    }

    @Nullable
    private static PredefinedConfig.Home parsePresetHome(@Nullable ConfigurationSection home,
                                                         @Nullable ToIntFunction<String> worldIndex) {
        if (home == null) {
            return null;
        }
        int worldIdx = resolveWorld(home.get("world"), worldIndex);
        return new PredefinedConfig.Home(worldIdx,
                home.getDouble("x"), home.getDouble("y"), home.getDouble("z"),
                (float) home.getDouble("yaw"), (float) home.getDouble("pitch"));
    }

    private static int resolveWorld(@Nullable Object worldName, @Nullable ToIntFunction<String> worldIndex) {
        if (worldIndex == null || !(worldName instanceof String name)) {
            return 0;
        }
        return worldIndex.applyAsInt(name);
    }

    // ── BakedTables derivation (AM-14) ──────────────────────────────────────────────────────

    private static BakedTables bakeTables(PowerConfig power, @Nullable ToIntFunction<String> worldIndex) {
        BakedTables.Builder builder = new BakedTables.Builder();
        PowerConfig.ZoneMultipliers zones = power.zoneMultipliers();
        builder.zoneMultiplier(ZONE_SAFEZONE, zones.safezone());
        builder.zoneMultiplier(ZONE_WARZONE, zones.warzone());
        builder.zoneMultiplier(ZONE_OWN_CLAIMED, zones.ownClaimed());
        builder.zoneMultiplier(ZONE_ENEMY_CLAIMED, zones.enemyClaimed());
        builder.zoneMultiplier(ZONE_WILDERNESS, zones.wilderness());
        // World multipliers key on worldIdx; when the parse runs world-blind (worldIndex == null)
        // they stay empty and the boot layer re-bakes them via ConfigImage.withBaked. Material
        // bitsets are likewise filled at boot (no config key drives them). '// hot-path' n/a here:
        // this runs once per parse, off the writer.
        if (worldIndex != null) {
            String[] names = power.worldMultiplierNames();
            double[] values = power.worldMultiplierValues();
            for (int i = 0; i < names.length; i++) {
                builder.worldMultiplier(worldIndex.applyAsInt(names[i]), values[i]);
            }
        }
        return builder.build();
    }

    // ── warn-and-fallback readers ───────────────────────────────────────────────────────────

    private static boolean bool(ConfigurationSection c, String path, boolean def, List<String> issues) {
        Object raw = c.get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        issues.add(path + " should be true/false but is '" + raw + "'; using " + def);
        return def;
    }

    private static int integer(ConfigurationSection c, String path, int def, List<String> issues) {
        Object raw = c.get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        issues.add(path + " should be a whole number but is '" + raw + "'; using " + def);
        return def;
    }

    private static long longValue(ConfigurationSection c, String path, long def, List<String> issues) {
        Object raw = c.get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        issues.add(path + " should be a whole number but is '" + raw + "'; using " + def);
        return def;
    }

    private static double dbl(ConfigurationSection c, String path, double def, List<String> issues) {
        Object raw = c.get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        issues.add(path + " should be a number but is '" + raw + "'; using " + def);
        return def;
    }

    @Nullable
    private static Double optionalDouble(ConfigurationSection c, String path, List<String> issues) {
        Object raw = c.get(path);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        issues.add(path + " should be a number but is '" + raw + "'; ignoring");
        return null;
    }

    private static String str(ConfigurationSection c, String path, String def, List<String> issues) {
        Object raw = c.get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof String value) {
            return value;
        }
        issues.add(path + " should be text but is '" + raw + "'; using default");
        return def;
    }

    private static String[] stringArray(ConfigurationSection c, String path) {
        if (!c.isList(path)) {
            return new String[0];
        }
        List<String> list = c.getStringList(path);
        return list.toArray(new String[0]);
    }

    @Nullable
    private static Integer asInt(@Nullable Object raw) {
        return raw instanceof Number number ? number.intValue() : null;
    }
}
