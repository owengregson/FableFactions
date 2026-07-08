package dev.fablemc.factions.core.listen;

import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import dev.fablemc.factions.platform.life.Scope;

/**
 * Registers a handler for a Bukkit event that is ABSENT from the 1.13 compile API — the raid events
 * (1.14+) and the {@code org.bukkit} mount event (1.20.3+) — so those probe-gated listeners can exist
 * without a compile-time reference to a class the floor jar lacks. The event class is loaded by name
 * and registered through {@link org.bukkit.plugin.PluginManager#registerEvent} with a base-{@code Event}
 * {@link EventExecutor}, so no absent type ever appears in a descriptor and no baseline class links it.
 *
 * <p><b>Owning thread(s):</b> {@link #register} runs on the plugin boot thread; the registered handler
 * runs on the event's region/main thread. <b>Mutability:</b> static-only. The registration is recorded
 * in the feature {@link Scope} for symmetric teardown (the reflective-event twin of
 * {@link dev.fablemc.factions.platform.life.ListenerGate}).
 */
final class ReflectiveEvents {

    private ReflectiveEvents() {
    }

    /**
     * Registers {@code handler} for the event named {@code eventClassName} if the class resolves and
     * is a Bukkit {@code Event}; returns {@code false} (a no-op) otherwise. {@code marker} is the
     * bookkeeping {@link Listener} whose handlers are unregistered when {@code scope} closes.
     */
    static boolean register(Plugin plugin, Scope scope, String eventClassName, EventPriority priority,
                            boolean ignoreCancelled, Listener marker, Consumer<Event> handler) {
        Class<?> raw;
        try {
            raw = Class.forName(eventClassName);
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
        if (!Event.class.isAssignableFrom(raw)) {
            return false;
        }
        Class<? extends Event> eventClass = raw.asSubclass(Event.class);
        EventExecutor executor = (listener, event) -> handler.accept(event);
        Bukkit.getPluginManager().registerEvent(eventClass, marker, priority, executor, plugin,
                ignoreCancelled);
        scope.closeable(() -> HandlerList.unregisterAll(marker));
        return true;
    }
}
