package dev.fablemc.factions.kernel.config;

/**
 * The complete typed configuration, immutable and part of kernel state (config is state; a
 * reload is a {@code SwapConfig} intent, so a config swap is serialized with every other
 * mutation and can never tear an in-flight decision — proposal-C §9.1).
 *
 * <p><b>Owning thread(s):</b> parsed off-thread in {@code :core} with warn-and-fallback readers,
 * published inside the snapshot; read on any thread. <b>Mutability:</b> immutable record of
 * records. <b>Reducer rule:</b> replaced whole by the reducer on {@code SwapConfig}.
 *
 * <p>Every key from the reference inventory (pvp-resources.md, pvp-services.md §2) is present as
 * a typed field with the reference default; aliased keys collapse to one canonical field at
 * parse. Domain sections are grouped into their section records ({@link PowerConfig},
 * {@link LandConfig}, …); cross-cutting scalars (limits, language, display, updates, metrics,
 * merge, per-faction flag defaults) are grouped into the small nested records below.
 * {@link #baked()} carries the derived hot tables (AM-14).
 */
public record ConfigImage(
        Limits limits,
        Language language,
        Display display,
        Updates updates,
        Metrics metrics,
        boolean mergeEnabled,
        FlagDefaults flagDefaults,
        PowerConfig power,
        LandConfig land,
        EconomyConfig economy,
        FlyConfig fly,
        ChatConfig chat,
        RelationConfig relation,
        RoleConfig role,
        ZoneConfig zones,
        NotificationRouting notifications,
        GuiModelConfig gui,
        PredefinedConfig predefined,
        StorageConfigView storage,
        BakedTables baked) {

    /** Faction limits and simple string/int knobs ({@code factions.max-*}, invite TTL, etc.). */
    public record Limits(int maxMembers, int maxWarps, int maxTeamChests,
                         String defaultTeamChestName, int invitesTtlHours) {
        /** Reference defaults ({@code maxMembers} 0 = unlimited semantics; shipped value 50). */
        public static Limits defaults() {
            return new Limits(50, 10, 1, "default", 72);
        }
    }

    /** {@code factions.language.*}. */
    public record Language(String defaultLocale, boolean allowPlayerOverride,
                           boolean commandOpensGui, boolean commandOpenGuiAfterSet,
                           String[] visibleLocales) {
        /** Reference defaults. */
        public static Language defaults() {
            return new Language("en", true, true, true, new String[0]);
        }
    }

    /** Pagination / info-display knobs ({@code factions.map/list/top/warp/audit/info.*}). */
    public record Display(int mapOnceRadius, int listPageSize, int topPageSize,
                          int warpListPageSize, int auditPageSize, boolean infoShowAllies,
                          boolean infoShowTruces, boolean infoShowNeutrals,
                          boolean infoShowEnemies) {
        /** Reference defaults. */
        public static Display defaults() {
            return new Display(3, 8, 8, 8, 10, true, false, false, false);
        }
    }

    /** {@code factions.updates.*}. */
    public record Updates(boolean enabled, boolean notifyOpsOnJoin, String modrinthSlug,
                          String githubOwner, String githubRepo) {
        /** Reference defaults (shipped {@code enabled}/{@code notify} = true). */
        public static Updates defaults() {
            return new Updates(true, true, "pvpindex-factions", "PVP-Index", "pvpindex-factions");
        }
    }

    /** {@code factions.metrics.*}. */
    public record Metrics(boolean bstatsEnabled, int bstatsPluginId) {
        /** Reference defaults. */
        public static Metrics defaults() {
            return new Metrics(true, 31240);
        }
    }

    /**
     * Per-faction flag effective defaults and player-editability, indexed by the
     * {@code Faction.FLAG_*} ordinals (PVP, FRIENDLY_FIRE, EXPLOSIONS, FIRE_SPREAD, OPEN).
     */
    public record FlagDefaults(boolean[] defaultValue, boolean[] playerEditable) {
        /** Reference hard defaults: PVP on, all others off; every flag player-editable. */
        public static FlagDefaults defaults() {
            boolean[] def = new boolean[] {true, false, false, false, false};
            boolean[] editable = new boolean[] {true, true, true, true, true};
            return new FlagDefaults(def, editable);
        }

        /** Effective default for a {@code Faction.FLAG_*} ordinal. */
        public boolean defaultOf(int flagOrdinal) {
            return flagOrdinal >= 0 && flagOrdinal < defaultValue.length && defaultValue[flagOrdinal];
        }

        /** Whether players may toggle the flag ({@code true} when out of range, matching config). */
        public boolean editableOf(int flagOrdinal) {
            return flagOrdinal < 0 || flagOrdinal >= playerEditable.length
                    || playerEditable[flagOrdinal];
        }
    }

    /**
     * The complete reference-default configuration image, with {@link BakedTables} derived from
     * the default sections. Used by tests and as the pre-parse boot fallback.
     */
    public static ConfigImage defaults() {
        PowerConfig power = PowerConfig.defaults();
        return new ConfigImage(
                Limits.defaults(),
                Language.defaults(),
                Display.defaults(),
                Updates.defaults(),
                Metrics.defaults(),
                false,                     // mergeEnabled
                FlagDefaults.defaults(),
                power,
                LandConfig.defaults(),
                EconomyConfig.defaults(),
                FlyConfig.defaults(),
                ChatConfig.defaults(),
                RelationConfig.defaults(),
                RoleConfig.defaults(),
                ZoneConfig.defaults(),
                NotificationRouting.defaults(),
                GuiModelConfig.defaults(),
                PredefinedConfig.defaults(),
                StorageConfigView.defaults(),
                BakedTables.defaults(power));
    }

    /** Returns a copy with a freshly baked {@link BakedTables} (after any material registry fill). */
    public ConfigImage withBaked(BakedTables newBaked) {
        return new ConfigImage(limits, language, display, updates, metrics, mergeEnabled,
                flagDefaults, power, land, economy, fly, chat, relation, role, zones,
                notifications, gui, predefined, storage, newBaked);
    }
}
