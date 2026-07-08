package dev.fablemc.factions.core.integration;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The typed {@code config.yml integrations.*} toggle block (ref-integrations §0.5), read once at
 * boot. Each gate is the reference's AND of "config toggle" and "plugin present"; this record
 * carries the config half, and {@link IntegrationsBootstrap} pairs each with a presence probe
 * (ref-integrations §0.3).
 *
 * <p><b>Owning thread(s):</b> parsed on the boot thread from the Bukkit config; an immutable value
 * thereafter, read on the scheduler-routed integration threads. <b>Mutability:</b> immutable record.
 *
 * <p>EzCountdown lives in {@code notifications.yml} ({@code NotificationRouting}) rather than here,
 * so its duration/display-types are read live from the {@code ConfigImage} at announce time (config
 * is state; a reload re-publishes it).
 */
public record IntegrationSettings(
        boolean vault,
        boolean worldGuard,
        boolean worldGuardSyncRegions,
        boolean dynmap,
        boolean placeholderApi,
        boolean essentialsX,
        boolean lwcEnabled,
        boolean lwcRequireBuildRightsToCreate,
        boolean lwcRemoveIfNoBuildRights,
        boolean lwcRemoveOnClaimChange,
        Discord discord) {

    /** DiscordSRV master toggle, target channel, and the five per-event gate/template pairs. */
    public record Discord(
            boolean enabled,
            String channelId,
            boolean factionCreatedEnabled,
            String factionCreatedMessage,
            boolean factionDisbandedEnabled,
            String factionDisbandedMessage,
            boolean relationAllyEnabled,
            String relationAllyMessage,
            boolean relationTruceEnabled,
            String relationTruceMessage,
            boolean relationEnemyEnabled,
            String relationEnemyMessage) {

        /** The reference-default DiscordSRV block (master toggle off; every event template on). */
        public static Discord defaults() {
            return new Discord(
                    false, "",
                    true, "**{faction}** was founded!",
                    true, "**{faction}** was disbanded.",
                    true, ":handshake: **{source}** and **{target}** are now allies!",
                    true, ":white_flag: **{source}** and **{target}** agreed to a truce.",
                    true, ":crossed_swords: **{source}** declared war on **{target}**!");
        }
    }

    /** The complete reference-default integration toggle block. */
    public static IntegrationSettings defaults() {
        return new IntegrationSettings(
                true,   // vault (informational)
                true,   // worldGuard
                false,  // worldGuardSyncRegions
                true,   // dynmap
                true,   // placeholderApi
                false,  // essentialsX
                true,   // lwcEnabled
                true,   // lwcRequireBuildRightsToCreate
                true,   // lwcRemoveIfNoBuildRights
                true,   // lwcRemoveOnClaimChange
                Discord.defaults());
    }

    /**
     * Reads the {@code integrations.*} keys from the plugin's root config section, falling back to
     * the reference default for every absent key. A {@code null} root yields {@link #defaults()}.
     */
    public static IntegrationSettings from(ConfigurationSection root) {
        if (root == null) {
            return defaults();
        }
        Discord d = discordFrom(root);
        return new IntegrationSettings(
                root.getBoolean("integrations.vault", true),
                root.getBoolean("integrations.worldguard", true),
                root.getBoolean("integrations.worldguard-sync-regions", false),
                root.getBoolean("integrations.dynmap", true),
                root.getBoolean("integrations.placeholderapi", true),
                root.getBoolean("integrations.essentialsx.enabled", false),
                root.getBoolean("integrations.lwc.enabled", true),
                root.getBoolean("integrations.lwc.require-build-rights-to-create", true),
                root.getBoolean("integrations.lwc.remove-if-no-build-rights", true),
                root.getBoolean("integrations.lwc.remove-on-claim-change", true),
                d);
    }

    private static Discord discordFrom(ConfigurationSection root) {
        Discord def = Discord.defaults();
        String base = "integrations.discordsrv";
        return new Discord(
                root.getBoolean(base + ".enabled", def.enabled()),
                root.getString(base + ".channel-id", def.channelId()),
                root.getBoolean(base + ".events.faction-created.enabled", def.factionCreatedEnabled()),
                root.getString(base + ".events.faction-created.message", def.factionCreatedMessage()),
                root.getBoolean(base + ".events.faction-disbanded.enabled", def.factionDisbandedEnabled()),
                root.getString(base + ".events.faction-disbanded.message", def.factionDisbandedMessage()),
                root.getBoolean(base + ".events.relation-ally.enabled", def.relationAllyEnabled()),
                root.getString(base + ".events.relation-ally.message", def.relationAllyMessage()),
                root.getBoolean(base + ".events.relation-truce.enabled", def.relationTruceEnabled()),
                root.getString(base + ".events.relation-truce.message", def.relationTruceMessage()),
                root.getBoolean(base + ".events.relation-enemy.enabled", def.relationEnemyEnabled()),
                root.getString(base + ".events.relation-enemy.message", def.relationEnemyMessage()));
    }
}
