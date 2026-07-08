package dev.fablemc.factions.platform.probe;

import java.util.Collection;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Feature detection, computed once at boot (CONTRACTS §3, proposal-C §7.1). Every
 * optional code path keys off a capability rather than a version comparison: a class
 * (or method / enum constant) either exists on this server or it does not. Version
 * numbers are reserved for the boot report only.
 *
 * <p>Owning thread(s): built once on the boot thread; read (as a final field) from any
 * thread thereafter. Mutability class: immutable value (a record of booleans).
 *
 * <p>Selector rule (AM-12): {@code SchedulingFactory} keys on {@link #folia()} ALONE.
 * {@link #foliaSchedulers()} is a boot-report fact, never a selector — the
 * {@code EntityScheduler} classes also exist on plain Paper 1.20+.
 */
public record Capabilities(
        boolean folia,
        boolean foliaSchedulers,
        boolean bungeeChat,
        boolean onlineCollection,
        boolean flattened,
        boolean asyncTeleport,
        boolean asyncChunkGet,
        boolean modernChatEvent,
        boolean blockExplode,
        boolean entityPickup,
        boolean armorStands,
        boolean raids,
        boolean mountBukkit,
        boolean mountSpigot,
        boolean toggleGlide,
        boolean lingering,
        boolean pdc,
        boolean brigadier,
        boolean serializeAsBytes,
        boolean hexColors,
        boolean minHeight,
        boolean hidePlayerPlugin,
        boolean clickedInventory) {

    public static @NotNull Capabilities detect() {
        boolean folia = Probes.classPresent("io.papermc.paper.threadedregions.RegionizedServer");
        // Report-only (AM-12): also true on plain Paper 1.20+, so NEVER a Folia selector.
        boolean foliaSchedulers =
                folia || Probes.classPresent("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
        // Bungee BaseComponent (+ its Player.Spigot#sendMessage sink) — the 1.8+ hover/click path.
        boolean bungeeChat = Probes.classPresent("net.md_5.bungee.api.chat.BaseComponent");
        // The 1.7.10 binary split: getOnlinePlayers() returns Collection (1.8+) vs Player[] (stock 1.7.10).
        boolean onlineCollection = onlineReturnsCollection();
        boolean flattened = Material.getMaterial("WHITE_WOOL") != null;
        boolean asyncTeleport = Probes.methodPresent("org.bukkit.entity.Entity", "teleportAsync", "org.bukkit.Location");
        boolean asyncChunkGet = anyMethodNamed(World.class, "getChunkAtAsync");
        boolean modernChatEvent = Probes.classPresent("io.papermc.paper.event.player.AsyncChatEvent");
        boolean blockExplode = Probes.classPresent("org.bukkit.event.block.BlockExplodeEvent");
        boolean entityPickup = Probes.classPresent("org.bukkit.event.entity.EntityPickupItemEvent");
        boolean armorStands = Probes.classPresent("org.bukkit.entity.ArmorStand");
        boolean raids = Probes.classPresent("org.bukkit.Raid");
        boolean mountBukkit = Probes.classPresent("org.bukkit.event.entity.EntityMountEvent");
        boolean mountSpigot = Probes.classPresent("org.spigotmc.event.entity.EntityMountEvent");
        boolean toggleGlide = Probes.classPresent("org.bukkit.event.entity.EntityToggleGlideEvent");
        boolean lingering = Probes.classPresent("org.bukkit.event.entity.LingeringPotionSplashEvent");
        boolean pdc = Probes.classPresent("org.bukkit.persistence.PersistentDataContainer");
        boolean brigadier = Probes.classPresent("io.papermc.paper.command.brigadier.Commands");
        boolean serializeAsBytes = Probes.methodPresent(ItemStack.class, "serializeAsBytes");
        // Spigot bungee ChatColor.of(String) — the 1.16 hex-colour marker (§x hex support).
        boolean hexColors = Probes.methodPresent("net.md_5.bungee.api.ChatColor", "of", "java.lang.String");
        boolean minHeight = Probes.methodPresent(World.class, "getMinHeight");
        boolean hidePlayerPlugin = Probes.methodPresent(Player.class, "hidePlayer", Plugin.class, Player.class);
        boolean clickedInventory = Probes.methodPresent(InventoryClickEvent.class, "getClickedInventory");
        return new Capabilities(folia, foliaSchedulers, bungeeChat, onlineCollection, flattened, asyncTeleport,
                asyncChunkGet, modernChatEvent, blockExplode, entityPickup, armorStands, raids, mountBukkit,
                mountSpigot, toggleGlide, lingering, pdc, brigadier, serializeAsBytes, hexColors, minHeight,
                hidePlayerPlugin, clickedInventory);
    }

    /** The single boot-report line (B10 / no silent degradation). */
    public @NotNull String describe() {
        return "folia=" + folia + " foliaSchedulers=" + foliaSchedulers + " bungeeChat=" + bungeeChat
                + " onlineCollection=" + onlineCollection + " flattened=" + flattened
                + " asyncTeleport=" + asyncTeleport + " asyncChunkGet=" + asyncChunkGet
                + " modernChatEvent=" + modernChatEvent + " blockExplode=" + blockExplode
                + " entityPickup=" + entityPickup + " armorStands=" + armorStands + " raids=" + raids
                + " mountBukkit=" + mountBukkit + " mountSpigot=" + mountSpigot + " toggleGlide=" + toggleGlide
                + " lingering=" + lingering + " pdc=" + pdc + " brigadier=" + brigadier
                + " serializeAsBytes=" + serializeAsBytes + " hexColors=" + hexColors + " minHeight=" + minHeight
                + " hidePlayerPlugin=" + hidePlayerPlugin + " clickedInventory=" + clickedInventory;
    }

    /**
     * Reflectively reads the {@code Bukkit.getOnlinePlayers()} return descriptor: a
     * {@link Collection} on 1.8+ (and every modern build), a {@code Player[]} on stock
     * 1.7.10. Any slip degrades to the modern default (Collection).
     */
    private static boolean onlineReturnsCollection() {
        try {
            Method online = Bukkit.class.getMethod("getOnlinePlayers");
            return Collection.class.isAssignableFrom(online.getReturnType());
        } catch (NoSuchMethodException | LinkageError absent) {
            return true;
        }
    }

    /** True iff {@code owner} declares any public method named {@code name} (arity-blind). */
    private static boolean anyMethodNamed(@NotNull Class<?> owner, @NotNull String name) {
        try {
            for (Method m : owner.getMethods()) {
                if (m.getName().equals(name)) {
                    return true;
                }
            }
        } catch (LinkageError ignored) {
            // A partially-linkable API surface degrades to "absent".
        }
        return false;
    }
}
