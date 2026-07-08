package dev.fablemc.factions.platform.probe;

import org.jetbrains.annotations.NotNull;

/**
 * The registry of every Bukkit/Paper/Spigot class-presence probe target (CONTRACTS §3,
 * proposal-C §7.1). {@link Capabilities#detect()} probes each of these with
 * {@link Probes#classPresent(String)} rather than embedding the raw FQN inline, so no server
 * class name is scattered across the platform and a rename is a one-line change.
 *
 * <p><b>Owning thread(s):</b> read once on the boot thread. <b>Mutability:</b> immutable enum.
 * New capability probes that key on a class' presence MUST add their target here.
 */
public enum ProbeTarget {

    /** Folia region server — the SOLE Folia selector (AM-12). */
    FOLIA_REGIONIZED_SERVER("io.papermc.paper.threadedregions.RegionizedServer"),
    /** Folia/Paper 1.20+ entity scheduler — a boot-report fact, never a selector. */
    FOLIA_ENTITY_SCHEDULER("io.papermc.paper.threadedregions.scheduler.EntityScheduler"),
    /** Bungee chat base component (the 1.8+ hover/click sink). */
    BUNGEE_BASE_COMPONENT("net.md_5.bungee.api.chat.BaseComponent"),
    /** Paper async chat event (the modern chat path). */
    ASYNC_CHAT_EVENT("io.papermc.paper.event.player.AsyncChatEvent"),
    /** Modern block-explode event. */
    BLOCK_EXPLODE_EVENT("org.bukkit.event.block.BlockExplodeEvent"),
    /** Modern entity item-pickup event. */
    ENTITY_PICKUP_ITEM_EVENT("org.bukkit.event.entity.EntityPickupItemEvent"),
    /** Armor-stand entity type. */
    ARMOR_STAND("org.bukkit.entity.ArmorStand"),
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
    BRIGADIER_COMMANDS("io.papermc.paper.command.brigadier.Commands");

    private final String className;

    ProbeTarget(String className) {
        this.className = className;
    }

    /** The fully-qualified class name whose presence this target probes. */
    public @NotNull String className() {
        return className;
    }
}
