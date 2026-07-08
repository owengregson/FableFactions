package dev.fablemc.factions.core.integration.essentials;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import dev.fablemc.factions.core.integration.Reflect;

/**
 * The reflection-backed {@link EssentialsInterop}: drives {@code IEssentials.getUser(...)},
 * {@code User.getAsyncTeleport().now(...)}, {@code isJailed()} and {@code isVanished()} entirely by
 * name so {@code :core} never imports {@code com.earth2me.essentials} (ref-integrations §4.3).
 *
 * <p><b>Owning thread(s):</b> the travel flow on the player's region thread. <b>Mutability:</b>
 * holds the resolved Essentials plugin handle and lazily-cached method handles.
 */
public final class ReflectiveEssentialsInterop implements EssentialsInterop {

    private final Object essentials;
    private final Logger logger;
    private volatile Method getUser;

    /** Wraps the confirmed {@code IEssentials} plugin instance (as an opaque {@link Object}). */
    public ReflectiveEssentialsInterop(Object essentials, Logger logger) {
        this.essentials = essentials;
        this.logger = logger;
    }

    @Override
    public boolean teleport(Player player, Location dest, Runnable onSuccess, Runnable onFailure) {
        Object user = user(player);
        if (user == null) {
            return false;
        }
        // /back returns here after the teleport (ref-integrations §4.3).
        Reflect.call(user, "setLastLocation");
        Object asyncTeleport = Reflect.call(user, "getAsyncTeleport");
        Method now = findNow(asyncTeleport);
        if (now == null) {
            return false;
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Object called = Reflect.invoke(asyncTeleport, now, dest, true, TeleportCause.COMMAND, future);
        if (called == null && now.getReturnType() != void.class) {
            // A non-void 'now' that returned null means the reflective call failed — native fallback.
            return false;
        }
        future.whenComplete((ok, err) -> {
            if (err != null) {
                logger.warning("EssentialsX async teleport threw: " + err.getMessage());
                onFailure.run();
            } else if (Boolean.TRUE.equals(ok)) {
                onSuccess.run();
            } else {
                onFailure.run();
            }
        });
        return true;
    }

    @Override
    public boolean isJailed(Player player) {
        Object user = user(player);
        return user != null && Boolean.TRUE.equals(Reflect.call(user, "isJailed"));
    }

    @Override
    public boolean isVanished(Player player) {
        Object user = user(player);
        return user != null && Boolean.TRUE.equals(Reflect.call(user, "isVanished"));
    }

    private Object user(Player player) {
        if (getUser == null) {
            getUser = Reflect.method(essentials.getClass(), "getUser", Player.class);
        }
        return Reflect.invoke(essentials, getUser, player);
    }

    /**
     * Finds the {@code AsyncTeleport.now(Location, boolean, TeleportCause, CompletableFuture)}
     * method by shape (4 params, {@code CompletableFuture} last) rather than an exact descriptor —
     * Essentials has shuffled this signature across versions.
     */
    private static Method findNow(Object asyncTeleport) {
        if (asyncTeleport == null) {
            return null;
        }
        for (Method m : asyncTeleport.getClass().getMethods()) {
            if (!m.getName().equals("now")) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 4 && CompletableFuture.class.isAssignableFrom(params[3])
                    && Location.class.isAssignableFrom(params[0])) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
}
