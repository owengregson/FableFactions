package dev.fablemc.factions.core.integration.lwc;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import dev.fablemc.factions.core.integration.Reflect;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;
import dev.fablemc.factions.kernel.state.RelationKind;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * The reflection-backed {@link LwcInterop} (ref-integrations §6): resolves the two LWC event classes
 * and {@code com.griefcraft.lwc.LWC} by name, gates lock creation and stale-protection removal on
 * faction build rights, and purges alien protections after an ownership change. No
 * {@code com.griefcraft} type is ever imported.
 *
 * <p><b>Owning thread(s):</b> event handlers on the interacting player's region thread; {@link #purge}
 * on the chunk's region thread. <b>Mutability:</b> holds the resolved event classes and a
 * registration flag; faction facts are read live from the snapshot.
 */
public final class ReflectiveLwcInterop implements LwcInterop, Listener {

    private static final String EVENT_REGISTER = "com.griefcraft.scripting.event.LWCProtectionRegisterEvent";
    private static final String EVENT_INTERACT = "com.griefcraft.scripting.event.LWCProtectionInteractEvent";
    private static final String LWC_MAIN = "com.griefcraft.lwc.LWC";
    private static final MessageKey STALE_REMOVED = MessageKey.of("custom.lwc.stale-removed");

    private final SnapshotHub snapshots;
    private final Worlds worlds;
    private final Messages messages;
    private final Logger logger;
    private final boolean requireBuildRights;
    private final boolean removeIfNoBuildRights;

    private Class<? extends Event> registerEventClass;
    private Class<? extends Event> interactEventClass;
    private boolean registered;

    /** Constructor injection: snapshot source, world registry, messages, logger, and the two gates. */
    public ReflectiveLwcInterop(SnapshotHub snapshots, Worlds worlds, Messages messages, Logger logger,
                                boolean requireBuildRights, boolean removeIfNoBuildRights) {
        this.snapshots = snapshots;
        this.worlds = worlds;
        this.messages = messages;
        this.logger = logger;
        this.requireBuildRights = requireBuildRights;
        this.removeIfNoBuildRights = removeIfNoBuildRights;
    }

    @Override
    public void register(Plugin plugin) {
        if (registered) {
            return;
        }
        registerEventClass = asEvent(Reflect.findClass(EVENT_REGISTER));
        interactEventClass = asEvent(Reflect.findClass(EVENT_INTERACT));
        if (registerEventClass == null || interactEventClass == null) {
            logger.info("LWC events not found - integration listeners not registered.");
            return;
        }
        try {
            EventExecutor executor = (listener, event) -> onLwcEvent(event);
            Bukkit.getPluginManager().registerEvent(
                    registerEventClass, this, EventPriority.HIGH, executor, plugin, true);
            Bukkit.getPluginManager().registerEvent(
                    interactEventClass, this, EventPriority.HIGH, executor, plugin, true);
            registered = true;
            logger.info("LWC integration listeners registered.");
        } catch (RuntimeException | LinkageError failed) {
            logger.log(Level.WARNING, "Failed to register LWC integration listeners", failed);
        }
    }

    @Override
    public void unregister() {
        if (!registered) {
            return;
        }
        try {
            HandlerList.unregisterAll(this);
        } catch (RuntimeException | LinkageError ignored) {
            // Best-effort teardown — a failing unregister must not block shutdown.
        }
        registered = false;
    }

    @Override
    public void purge(World world, int chunkX, int chunkZ, int newOwnerOrdinal) {
        try {
            List<?> protections = protectionsInChunk(world.getName(), chunkX, chunkZ);
            KernelSnapshot snapshot = snapshots.current();
            for (Object protection : protections) {
                if (!ownerBelongsTo(snapshot, Reflect.call(protection, "getOwner"), newOwnerOrdinal)) {
                    Reflect.call(protection, "remove");
                }
            }
        } catch (RuntimeException | LinkageError failed) {
            logger.log(Level.FINE, "Failed LWC cleanup for " + world.getName()
                    + ":" + chunkX + ":" + chunkZ, failed);
        }
    }

    // ── reflective event dispatch ────────────────────────────────────────────────────────────

    private void onLwcEvent(Object event) {
        try {
            if (registerEventClass.isInstance(event)) {
                onProtectionRegister(event);
            } else if (interactEventClass.isInstance(event)) {
                onProtectionInteract(event);
            }
        } catch (RuntimeException | LinkageError failed) {
            logger.log(Level.FINE, "LWC event handling failed", failed);
        }
    }

    private void onProtectionRegister(Object event) {
        if (!requireBuildRights) {
            return;
        }
        if (!(Reflect.call(event, "getPlayer") instanceof Player player)
                || !(Reflect.call(event, "getBlock") instanceof Block block)) {
            return;
        }
        if (canModify(snapshots.current(), player.getUniqueId(), block)) {
            return;
        }
        Reflect.call(event, "setCancelled", Reflect.sig(boolean.class), true);
    }

    private void onProtectionInteract(Object event) {
        if (!removeIfNoBuildRights) {
            return;
        }
        Object protection = Reflect.call(event, "getProtection");
        if (!(Reflect.call(protection, "getBlock") instanceof Block block)) {
            return;
        }
        UUID ownerUuid = resolveUuid(Reflect.call(protection, "getOwner"));
        if (ownerUuid != null && canModify(snapshots.current(), ownerUuid, block)) {
            return;
        }
        Reflect.call(protection, "remove");
        Object cancel = resolveCancelResult();
        if (cancel != null) {
            Reflect.call1(event, "setResult", cancel);
        }
        if (Reflect.call(event, "getPlayer") instanceof Player actor) {
            messages.toPlayer(actor.getUniqueId(), STALE_REMOVED);
        }
    }

    // ── faction build-rights model (LWC's own, snapshot-backed) ──────────────────────────────

    private boolean canModify(KernelSnapshot snapshot, UUID playerId, Block block) {
        World world = block.getWorld();
        int worldIdx = worlds.indexOf(world.getUID());
        int owner = worldIdx < 0 ? FactionHandle.WILDERNESS
                : snapshot.claimOwnerAt(worldIdx, ChunkKeys.key(block.getX() >> 4, block.getZ() >> 4));
        if (owner == FactionHandle.WILDERNESS) {
            return true;
        }
        int ownerOrd = FactionHandle.ordinal(owner);
        if (ownerOrd == FactionHandle.SAFEZONE_ORDINAL || ownerOrd == FactionHandle.WARZONE_ORDINAL) {
            return false;
        }
        int playerOrdinal = snapshot.memberOrdinal(playerId);
        if (playerOrdinal < 0) {
            return false;
        }
        MemberView member = snapshot.member(playerOrdinal);
        int playerHandle = member != null ? member.factionHandle() : FactionHandle.WILDERNESS;
        if (playerHandle == FactionHandle.WILDERNESS) {
            return false;
        }
        if (FactionHandle.ordinal(playerHandle) == ownerOrd) {
            return true;
        }
        return snapshot.relationBetween(playerHandle, owner) == RelationKind.ALLY;
    }

    private boolean ownerBelongsTo(KernelSnapshot snapshot, Object ownerName, int factionOrdinal) {
        UUID ownerUuid = resolveUuid(ownerName);
        if (ownerUuid == null) {
            return false;
        }
        int ordinal = snapshot.memberOrdinal(ownerUuid);
        if (ordinal < 0) {
            return false;
        }
        MemberView member = snapshot.member(ordinal);
        return member != null && FactionHandle.ordinal(member.factionHandle()) == factionOrdinal;
    }

    // ── reflection helpers ───────────────────────────────────────────────────────────────────

    private List<?> protectionsInChunk(String world, int chunkX, int chunkZ) {
        Object lwc = Reflect.callStatic(Reflect.findClass(LWC_MAIN), "getInstance");
        Object db = Reflect.call(lwc, "getPhysicalDatabase");
        int xmin = chunkX * 16;
        int zmin = chunkZ * 16;
        Object result = Reflect.call(db, "loadProtections",
                new Class<?>[] {String.class, int.class, int.class, int.class, int.class, int.class, int.class},
                world, xmin, xmin + 15, 0, 255, zmin, zmin + 15);
        return result instanceof List<?> list ? list : List.of();
    }

    private static UUID resolveUuid(Object ownerName) {
        if (!(ownerName instanceof String name) || name.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException notAUuid) {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) {
                return online.getUniqueId();
            }
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            return offline != null ? offline.getUniqueId() : null;
        }
    }

    private Object resolveCancelResult() {
        for (Class<?> nested : interactEventClass.getClasses()) {
            if (nested.isEnum() && nested.getSimpleName().equals("Result")) {
                return enumConstant(nested, "CANCEL");
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        try {
            return Enum.valueOf((Class) enumClass, name);
        } catch (RuntimeException absent) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> asEvent(Class<?> candidate) {
        if (candidate == null || !Event.class.isAssignableFrom(candidate)) {
            return null;
        }
        return (Class<? extends Event>) candidate;
    }
}
