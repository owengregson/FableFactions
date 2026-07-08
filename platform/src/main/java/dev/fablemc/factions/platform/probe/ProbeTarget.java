package dev.fablemc.factions.platform.probe;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The single registry of every Bukkit/Paper/Spigot class referenced by NAME (CONTRACTS §3,
 * proposal-C §7.1): capability probe targets read by {@link Capabilities#detect()} and the
 * owners of reflectively-resolved members ({@code Feedback}, {@code TextPort}). Each entry
 * carries its own probe — {@link #present()} / {@link #lookup()} / {@link #hasMethod} — so
 * no raw server FQN and no ad-hoc {@code Class.forName} try/catch is ever scattered across
 * the platform, and a rename is a one-line change.
 *
 * <p><b>Owning thread(s):</b> read on the boot thread (and wherever a capability gate fires).
 * <b>Mutability:</b> immutable enum. Every future server class referenced by name MUST be
 * added here rather than inlined as a string literal.
 */
public enum ProbeTarget {

    /** Folia region server — the SOLE Folia selector (AM-12). */
    FOLIA_REGIONIZED_SERVER("io.papermc.paper.threadedregions.RegionizedServer"),
    /** Folia/Paper 1.20+ entity scheduler — a boot-report fact, never a selector. */
    FOLIA_ENTITY_SCHEDULER("io.papermc.paper.threadedregions.scheduler.EntityScheduler"),
    /** Bungee chat base component (the 1.8+ hover/click sink). */
    BUNGEE_BASE_COMPONENT("net.md_5.bungee.api.chat.BaseComponent"),
    /** Bungee text component (constructed reflectively for the action-bar / chat sinks). */
    BUNGEE_TEXT_COMPONENT("net.md_5.bungee.api.chat.TextComponent"),
    /** Bungee chat message type (the {@code ACTION_BAR} sink discriminator). */
    BUNGEE_CHAT_MESSAGE_TYPE("net.md_5.bungee.api.ChatMessageType"),
    /** Bungee chat colour — its {@code of(String)} is the 1.16 hex-colour marker. */
    BUNGEE_CHAT_COLOR("net.md_5.bungee.api.ChatColor"),
    /** The {@code Player.Spigot} message sink (hover/click-capable send). */
    PLAYER_SPIGOT("org.bukkit.entity.Player$Spigot"),
    /** Paper async chat event (the modern chat path). */
    ASYNC_CHAT_EVENT("io.papermc.paper.event.player.AsyncChatEvent"),
    /** Modern block-explode event. */
    BLOCK_EXPLODE_EVENT("org.bukkit.event.block.BlockExplodeEvent"),
    /** Modern entity item-pickup event. */
    ENTITY_PICKUP_ITEM_EVENT("org.bukkit.event.entity.EntityPickupItemEvent"),
    /** Armor-stand entity type. */
    ARMOR_STAND("org.bukkit.entity.ArmorStand"),
    /** Area-effect cloud entity (1.9+; the lingering-potion damage attributor). */
    AREA_EFFECT_CLOUD("org.bukkit.entity.AreaEffectCloud"),
    /** Raid API. */
    RAID("org.bukkit.Raid"),
    /** Bukkit entity-mount event. */
    ENTITY_MOUNT_EVENT_BUKKIT("org.bukkit.event.entity.EntityMountEvent"),
    /** Spigot entity-mount event. */
    ENTITY_MOUNT_EVENT_SPIGOT("org.spigotmc.event.entity.EntityMountEvent"),
    /** Elytra toggle-glide event. */
    ENTITY_TOGGLE_GLIDE_EVENT("org.bukkit.event.entity.EntityToggleGlideEvent"),
    /** Lingering-potion splash event. */
    LINGERING_POTION_SPLASH_EVENT("org.bukkit.event.entity.LingeringPotionSplashEvent"),
    /** Persistent data container API (PDC). */
    PERSISTENT_DATA_CONTAINER("org.bukkit.persistence.PersistentDataContainer"),
    /** Paper Brigadier commands API. */
    BRIGADIER_COMMANDS("io.papermc.paper.command.brigadier.Commands"),
    /** Boss-bar colour enum (1.9+; resolved for the boss-bar feedback tier). */
    BOSS_BAR_COLOR("org.bukkit.boss.BarColor"),
    /** Boss-bar style enum (1.9+). */
    BOSS_BAR_STYLE("org.bukkit.boss.BarStyle"),
    /** Boss-bar flag enum (1.9+; an empty array of it feeds {@code createBossBar}). */
    BOSS_BAR_FLAG("org.bukkit.boss.BarFlag");

    private final String className;

    ProbeTarget(String className) {
        this.className = className;
    }

    /** The fully-qualified class name this target stands for. */
    public @NotNull String className() {
        return className;
    }

    /** This target's class, or {@code null} when this server lacks it (never throws). */
    public @Nullable Class<?> lookup() {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    /** True iff this target's class loads on this server — the capability probe. */
    public boolean present() {
        return lookup() != null;
    }

    /** True iff this target's class loads AND declares a public {@code name(paramTypes...)}. */
    public boolean hasMethod(@NotNull String name, @NotNull Class<?>... paramTypes) {
        Class<?> owner = lookup();
        return owner != null && Probes.methodPresent(owner, name, paramTypes);
    }
}
