package dev.fablemc.factions.core.command.admin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

/**
 * Shared rendering helpers for the paged admin read commands ({@code /fa audit},
 * {@code /fa power history}): UTC timestamp formatting and actor-name resolution
 * (ref-commands-admin.md §2.10, §3.7, §7.3).
 *
 * <p><b>Owning thread(s):</b> called from the read commands' delivery hop on the sender's
 * region/main thread (the actor lookup reads live server state). <b>Mutability:</b> stateless.
 */
public final class AdminFormat {

    private static final DateTimeFormatter UTC_MINUTES =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private AdminFormat() {
    }

    /** {@code createdAtMillis} formatted as {@code yyyy-MM-dd HH:mm} in UTC. */
    public static String formatUtc(long createdAtMillis) {
        return UTC_MINUTES.format(Instant.ofEpochMilli(createdAtMillis));
    }

    /**
     * A display name for an audit/history actor: {@code "System"} when {@code null}, else the offline
     * player's name, falling back to the raw uuid string.
     */
    public static String resolveActor(@Nullable UUID actor) {
        if (actor == null) {
            return "System";
        }
        String name = Bukkit.getOfflinePlayer(actor).getName();
        return name != null && !name.isEmpty() ? name : actor.toString();
    }
}
