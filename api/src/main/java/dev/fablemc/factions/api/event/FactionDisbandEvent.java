package dev.fablemc.factions.api.event;

import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a faction is disbanded (proposal-C §10.2), during pre-flight on the caller
 * thread; cancelling aborts the disband.
 *
 * <p><b>Owning thread(s):</b> the caller's region/main thread. <b>Mutability:</b> only the
 * {@code cancelled} flag. Identity is {@link UUID} / {@link String} only (CONTRACTS §5).
 */
public final class FactionDisbandEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID factionId;
    private final String name;
    private final UUID actor;
    private boolean cancelled;

    public FactionDisbandEvent(UUID factionId, String name, UUID actor) {
        this.factionId = factionId;
        this.name = name;
        this.actor = actor;
    }

    /** The id of the faction being disbanded. */
    public UUID getFactionId() {
        return factionId;
    }

    /** The faction's name at disband time. */
    public String getName() {
        return name;
    }

    /** The player or admin triggering the disband ({@code null} for a system-triggered disband). */
    public UUID getActor() {
        return actor;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
