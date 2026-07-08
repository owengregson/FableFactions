package dev.fablemc.factions.platform.resolve;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The one online-players accessor (CONTRACTS §3, version-deltas Risk #1). Bridges the
 * 1.7.10 binary split where {@code Bukkit.getOnlinePlayers()} returns {@code Player[]}
 * on stock builds and {@code Collection<? extends Player>} on 1.8+ — a descriptor baked
 * into a floor-compiled call site would {@code NoSuchMethodError} on the other half.
 *
 * <p>Both descriptors are resolved ONCE at class load via {@link MethodHandle}
 * (dispatch-correct for either return shape); every iteration over online players in the
 * whole plugin goes through {@link #online()}. {@link #get(UUID)} prefers the native
 * {@code Bukkit.getPlayer(UUID)} and falls back to a linear scan.
 *
 * <p>Owning thread(s): the caller's (reads live server state — main/region thread).
 * Mutability class: static-only; the resolved handles are immutable.
 */
public final class Players {

    private static final MethodHandle ONLINE;
    private static final boolean ONLINE_IS_COLLECTION;
    private static final @Nullable MethodHandle GET_BY_UUID;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle online = null;
        boolean collection = true;
        try {
            online = lookup.findStatic(Bukkit.class, "getOnlinePlayers", MethodType.methodType(Collection.class));
            collection = true;
        } catch (ReflectiveOperationException | LinkageError modernAbsent) {
            try {
                online = lookup.findStatic(Bukkit.class, "getOnlinePlayers", MethodType.methodType(Player[].class));
                collection = false;
            } catch (ReflectiveOperationException | LinkageError legacyAbsent) {
                online = null;
            }
        }
        ONLINE = online;
        ONLINE_IS_COLLECTION = collection;

        MethodHandle byUuid = null;
        try {
            byUuid = lookup.findStatic(Bukkit.class, "getPlayer", MethodType.methodType(Player.class, UUID.class));
        } catch (ReflectiveOperationException | LinkageError absent) {
            byUuid = null; // pre-UUID-accessor builds: linear scan fallback
        }
        GET_BY_UUID = byUuid;
    }

    private Players() {}

    /** Every online player, via the boot-resolved descriptor. Never null (empty if unresolved). */
    @SuppressWarnings("unchecked")
    public static @NotNull Iterable<Player> online() {
        if (ONLINE == null) {
            return Collections.emptyList();
        }
        try {
            if (ONLINE_IS_COLLECTION) {
                Collection<? extends Player> players = (Collection<? extends Player>) ONLINE.invoke();
                return (Iterable<Player>) (Iterable<?>) players;
            }
            Player[] players = (Player[]) ONLINE.invoke();
            return Arrays.asList(players);
        } catch (Throwable failure) {
            throw new IllegalStateException("Players.online() invocation failed", failure);
        }
    }

    /** The online player with {@code id}, or {@code null} if none is online. */
    public static @Nullable Player get(@NotNull UUID id) {
        if (GET_BY_UUID != null) {
            try {
                return (Player) GET_BY_UUID.invoke(id);
            } catch (Throwable failure) {
                throw new IllegalStateException("Players.get(UUID) invocation failed", failure);
            }
        }
        for (Player player : online()) {
            if (id.equals(player.getUniqueId())) {
                return player;
            }
        }
        return null;
    }
}
