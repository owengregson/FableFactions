package dev.fablemc.factions.platform.probe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
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
    private final Capabilities caps;

    private PlatformProfile(
            @NotNull List<ManifestEntry> entries, @NotNull Set<String> disabledFeatures,
            @NotNull Capabilities caps) {
        this.entries = List.copyOf(entries);
        this.disabledFeatures = Set.copyOf(disabledFeatures);
        this.caps = caps;
    }

    /**
     * Resolves the whole manifest once against the running server. Loud-logs a mapping
     * break for any Required miss, throws on an engine-critical miss, and returns an
     * immutable profile the plugin reads for the rest of its life.
     */
    public static @NotNull PlatformProfile resolve(@NotNull Capabilities caps, @NotNull Consumer<String> log) {
        Boolean present = Boolean.TRUE;
        List<ManifestEntry> entries = new ArrayList<>();

        // --- Required handles (present on every supported server ⇒ a miss is a mapping break) ---
        // The world UID accessor underpins the AM-15 world registry; absent ⇒ disable world-keyed features.
        entries.add(Required.owned("world:uid", "worlds",
                () -> Probes.methodPresent(World.class, "getUID") ? present : null));
        // A scheduler must exist (BukkitScheduler on Paper, region schedulers on Folia) or nothing ticks.
        entries.add(Required.engineCritical("scheduler",
                () -> Probes.methodPresent("org.bukkit.Bukkit", "getScheduler")
                        || caps.folia() ? present : null));

        // --- Optional-since capabilities (typed flag, declared fallback) ---
        entries.add(OptionalSince.resolve("capability:folia", "1.19.4", Boolean.FALSE,
                "single-region Bukkit scheduling", () -> caps.folia() ? present : null));
        entries.add(OptionalSince.resolve("capability:async_teleport", "1.13", Boolean.FALSE,
                "sync teleport on the main thread", () -> caps.asyncTeleport() ? present : null));
        entries.add(OptionalSince.resolve("capability:async_chunk_get", "1.13", Boolean.FALSE,
                "sync getChunkAt for home-safety checks", () -> caps.asyncChunkGet() ? present : null));
        entries.add(OptionalSince.resolve("capability:flattened", "1.13", Boolean.FALSE,
                "LegacyMaterials modern→legacy table", () -> caps.flattened() ? present : null));
        entries.add(OptionalSince.resolve("capability:hex_colors", "1.16", Boolean.FALSE,
                "hex downsampled to the nearest named colour", () -> caps.hexColors() ? present : null));
        entries.add(OptionalSince.resolve("capability:bungee_chat", "1.8", Boolean.FALSE,
                "flat §-legacy string send (no hover/click)", () -> caps.bungeeChat() ? present : null));
        entries.add(OptionalSince.resolve("capability:serialize_as_bytes", "1.16.5", Boolean.FALSE,
                "BukkitObjectOutputStream Base64 item blobs", () -> caps.serializeAsBytes() ? present : null));
        entries.add(OptionalSince.resolve("capability:brigadier", "1.20.6", Boolean.FALSE,
                "plugin.yml command tree only", () -> caps.brigadier() ? present : null));
        entries.add(OptionalSince.resolve("capability:pdc", "1.14", Boolean.FALSE,
                "§-lore / side-table marker fallback", () -> caps.pdc() ? present : null));
        entries.add(OptionalSince.resolve("capability:min_height", "1.17", Boolean.FALSE,
                "world floor Y = 0", () -> caps.minHeight() ? present : null));
        entries.add(OptionalSince.resolve("capability:hide_player_plugin", "1.12.2", Boolean.FALSE,
                "the single-arg hidePlayer(Player) overload", () -> caps.hidePlayerPlugin() ? present : null));

        // --- Optional-since events / probe-gated protection listeners ---
        entries.add(OptionalSince.resolve("event:modern_chat", "1.16.5", Boolean.FALSE,
                "AsyncPlayerChatEvent tag injection", () -> caps.modernChatEvent() ? present : null));
        entries.add(OptionalSince.resolve("event:clicked_inventory", "1.13", Boolean.FALSE,
                "rawSlot fallback math", () -> caps.clickedInventory() ? present : null));
        entries.add(OptionalSince.resolve("event:block_explode", "1.8.3", Boolean.FALSE,
                "EntityExplode covers explosion grief", () -> caps.blockExplode() ? present : null));
        entries.add(OptionalSince.resolve("event:entity_pickup", "1.12", Boolean.FALSE,
                "legacy PlayerPickupItemEvent only", () -> caps.entityPickup() ? present : null));
        entries.add(OptionalSince.resolve("event:armor_stands", "1.8", Boolean.FALSE,
                "no armour-stand protection (nothing to protect)", () -> caps.armorStands() ? present : null));
        entries.add(OptionalSince.resolve("event:raids", "1.15", Boolean.FALSE,
                "no raid-in-claim suppression", () -> caps.raids() ? present : null));
        entries.add(OptionalSince.resolve("event:toggle_glide", "1.10", Boolean.FALSE,
                "no elytra fly-in-claim interaction", () -> caps.toggleGlide() ? present : null));
        entries.add(OptionalSince.resolve("event:lingering", "1.9", Boolean.FALSE,
                "PotionSplashEvent covers claim combat", () -> caps.lingering() ? present : null));
        entries.add(OptionalSince.resolve("event:mount", "1.8", Boolean.FALSE,
                "no mount-in-claim rules", () -> caps.mountBukkit() || caps.mountSpigot() ? present : null));

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
                + "scheduling=" + (caps.folia() ? "folia" : "bukkit")
                + " materials=" + (caps.flattened() ? "flattened" : "legacy-table")
                + " text=" + (caps.hexColors() ? "hex" : "downsampled")
                + (caps.bungeeChat() ? "+bungee" : "")
                + " items=" + (caps.serializeAsBytes() ? "bytes" : "yaml-base64")
                + "; features disabled: " + (disabledFeatures.isEmpty() ? "none" : disabledFeatures);
    }
}
