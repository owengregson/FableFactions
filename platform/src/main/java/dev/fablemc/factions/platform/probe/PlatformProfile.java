package dev.fablemc.factions.platform.probe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * The single boot-time resolution owner (CONTRACTS §3, proposal-C §7.1; Mental Tier-2
 * pattern). Built once at enable from a manifest of typed {@link ManifestEntry}s, it
 * folds every version-gated probe into one immutable value read on the hot path as
 * plain field access. It prints exactly ONE boot-report line and yields the
 * disabled-feature set the reconciler uses so a mapping-broken feature never converges.
 *
 * <p>A {@link Required} entry that misses disables its owning feature (one loud log) or,
 * if engine-critical, fails the boot; an {@link OptionalSince} entry that misses below
 * its version is a quiet, typed fallback (B10 — no silent degradation).
 *
 * <p>Owning thread(s): built on the boot thread, read-only thereafter. Mutability class:
 * immutable value (defensive copies of the entry list and disabled set).
 */
public final class PlatformProfile {

    private final List<ManifestEntry> entries;
    private final Set<String> disabledFeatures;
    private final Capabilities capabilities;

    private PlatformProfile(
            @NotNull List<ManifestEntry> entries, @NotNull Set<String> disabledFeatures,
            @NotNull Capabilities capabilities) {
        this.entries = List.copyOf(entries);
        this.disabledFeatures = Set.copyOf(disabledFeatures);
        this.capabilities = capabilities;
    }

    /**
     * Resolves the whole manifest once against the running server. Loud-logs a mapping
     * break for any Required miss, throws on an engine-critical miss, and returns an
     * immutable profile the plugin reads for the rest of its life.
     */
    public static @NotNull PlatformProfile resolve(@NotNull Capabilities caps, @NotNull Consumer<String> log) {
        List<ManifestEntry> entries = new ArrayList<>();

        // --- Required handles (present on every supported server ⇒ a miss is a mapping break) ---
        // The world UID accessor underpins the AM-15 world registry; absent ⇒ disable world-keyed features.
        entries.add(Required.owned("world:uid", "worlds", Probes.methodPresent(World.class, "getUID")));
        // A scheduler must exist (BukkitScheduler on Paper, region schedulers on Folia) or nothing ticks.
        entries.add(Required.engineCritical("scheduler",
                Probes.methodPresent(Bukkit.class, "getScheduler") || caps.folia()));

        // --- Optional-since capabilities (typed flag, declared fallback) ---
        entries.add(OptionalSince.flag("capability:folia", "1.19.4",
                "single-region Bukkit scheduling", caps.folia()));
        entries.add(OptionalSince.flag("capability:async_teleport", "1.13",
                "sync teleport on the main thread", caps.asyncTeleport()));
        entries.add(OptionalSince.flag("capability:async_chunk_get", "1.13",
                "sync getChunkAt for home-safety checks", caps.asyncChunkGet()));
        entries.add(OptionalSince.flag("capability:flattened", "1.13",
                "LegacyMaterials modern→legacy table", caps.flattened()));
        entries.add(OptionalSince.flag("capability:hex_colors", "1.16",
                "hex downsampled to the nearest named colour", caps.hexColors()));
        entries.add(OptionalSince.flag("capability:bungee_chat", "1.8",
                "flat §-legacy string send (no hover/click)", caps.bungeeChat()));
        entries.add(OptionalSince.flag("capability:serialize_as_bytes", "1.16.5",
                "BukkitObjectOutputStream Base64 item blobs", caps.serializeAsBytes()));
        entries.add(OptionalSince.flag("capability:brigadier", "1.20.6",
                "plugin.yml command tree only", caps.brigadier()));
        entries.add(OptionalSince.flag("capability:pdc", "1.14",
                "§-lore / side-table marker fallback", caps.pdc()));
        entries.add(OptionalSince.flag("capability:min_height", "1.17",
                "world floor Y = 0", caps.minHeight()));
        entries.add(OptionalSince.flag("capability:hide_player_plugin", "1.12.2",
                "the single-arg hidePlayer(Player) overload", caps.hidePlayerPlugin()));

        // --- Optional-since events / probe-gated protection listeners ---
        entries.add(OptionalSince.flag("event:modern_chat", "1.16.5",
                "AsyncPlayerChatEvent tag injection", caps.modernChatEvent()));
        entries.add(OptionalSince.flag("event:clicked_inventory", "1.13",
                "rawSlot fallback math", caps.clickedInventory()));
        entries.add(OptionalSince.flag("event:block_explode", "1.8.3",
                "EntityExplode covers explosion grief", caps.blockExplode()));
        entries.add(OptionalSince.flag("event:entity_pickup", "1.12",
                "legacy PlayerPickupItemEvent only", caps.entityPickup()));
        entries.add(OptionalSince.flag("event:armor_stands", "1.8",
                "no armour-stand protection (nothing to protect)", caps.armorStands()));
        entries.add(OptionalSince.flag("event:raids", "1.15",
                "no raid-in-claim suppression", caps.raids()));
        entries.add(OptionalSince.flag("event:toggle_glide", "1.10",
                "no elytra fly-in-claim interaction", caps.toggleGlide()));
        entries.add(OptionalSince.flag("event:lingering", "1.9",
                "PotionSplashEvent covers claim combat", caps.lingering()));
        entries.add(OptionalSince.flag("event:mount", "1.8",
                "no mount-in-claim rules", caps.mountBukkit() || caps.mountSpigot()));

        Set<String> disabled = resolveDisabled(entries, log);
        return new PlatformProfile(entries, disabled, caps);
    }

    /**
     * Scans a resolved manifest for Required misses: an owned miss disables its owner
     * (one loud log), an engine-critical miss throws (boot fail). Pure over the entry
     * list — the unit test drives it directly with a synthetic manifest.
     */
    static @NotNull Set<String> resolveDisabled(
            @NotNull List<ManifestEntry> entries, @NotNull Consumer<String> log) {
        Set<String> disabled = new LinkedHashSet<>();
        for (ManifestEntry entry : entries) {
            if (!(entry instanceof Required<?> required) || required.present()) {
                continue;
            }
            if (required.engineCritical()) {
                throw new IllegalStateException(
                        "platform: engine-critical handle " + required.name()
                                + " did not resolve on this server — cannot boot.");
            }
            String owner = required.owner();
            log.accept("platform: required handle " + required.name() + " did not resolve — disabling "
                    + owner + " on this version (a mapping break).");
            disabled.add(owner);
        }
        return disabled;
    }

    /** Features a Required mapping break disabled on this server — empty on every supported version. */
    public @NotNull Set<String> disabledFeatures() {
        return disabledFeatures;
    }

    /** The whole resolved manifest, for the boot report and invariant tests. */
    public @NotNull List<ManifestEntry> entries() {
        return entries;
    }

    /** The single boot-report line summarising the resolved profile (B10). */
    public @NotNull String bootReport() {
        int present = 0;
        for (ManifestEntry entry : entries) {
            if (entry.present()) {
                present++;
            }
        }
        return "platform profile — " + present + "/" + entries.size() + " handles resolved; "
                + "scheduling=" + (capabilities.folia() ? "folia" : "bukkit")
                + " materials=" + (capabilities.flattened() ? "flattened" : "legacy-table")
                + " text=" + (capabilities.hexColors() ? "hex" : "downsampled")
                + (capabilities.bungeeChat() ? "+bungee" : "")
                + " items=" + (capabilities.serializeAsBytes() ? "bytes" : "yaml-base64")
                + "; features disabled: " + (disabledFeatures.isEmpty() ? "none" : disabledFeatures);
    }
}
