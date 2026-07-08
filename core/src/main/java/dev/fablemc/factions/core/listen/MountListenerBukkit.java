package dev.fablemc.factions.core.listen;

import java.lang.reflect.Method;
import java.util.Objects;

import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import dev.fablemc.factions.platform.life.Scope;

/**
 * Vehicle-mount protection on the modern Bukkit event ({@code org.bukkit.event.entity.EntityMountEvent},
 * 1.20.3+ — the package the Spigot event moved to). Registered at {@code HIGH}/{@code ignoreCancelled=true}
 * ONLY behind the {@code mountBukkit} capability through {@link ReflectiveEvents}, because that event
 * is absent from the 1.13 compile API; the rider is read via base-{@code Event} reflection and the
 * decision runs through the shared {@link MountGuard}. Its older {@code org.spigotmc} twin is the
 * directly-typed {@link MountListenerSpigot}.
 *
 * <p><b>Owning thread(s):</b> {@link #register} on boot; the handler on the mount's region/main thread
 * — snapshot read + feedback only (CONTRACTS §4). <b>Mutability:</b> immutable.
 */
public final class MountListenerBukkit implements Listener {

    private static final String ENTITY_MOUNT_EVENT = "org.bukkit.event.entity.EntityMountEvent";

    private final ListenerContext ctx;

    public MountListenerBukkit(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Registers the reflective mount handler; returns {@code false} if the event class is absent. */
    public boolean register(Plugin plugin, Scope scope) {
        return ReflectiveEvents.register(plugin, scope, ENTITY_MOUNT_EVENT, EventPriority.HIGH, true,
                this, this::onMount);
    }

    private void onMount(Event event) {
        if (!(event instanceof Cancellable cancellable)) {
            return;
        }
        Entity rider = mountRider(event);
        if (rider != null) {
            MountGuard.check(ctx, rider, cancellable);
        }
    }

    private static Entity mountRider(Event event) {
        try {
            Method getEntity = event.getClass().getMethod("getEntity");
            Object rider = getEntity.invoke(event);
            return rider instanceof Entity entity ? entity : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }
}
