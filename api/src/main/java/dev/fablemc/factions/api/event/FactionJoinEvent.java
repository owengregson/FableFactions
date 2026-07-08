package dev.fablemc.factions.api.event;

import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player joins a faction (proposal-C §10.2), during pre-flight on the caller
 * thread; cancelling aborts the join.
 *
 * <p><b>Owning thread(s):</b> the caller's region/main thread. <b>Mutability:</b> only the
 * {@code cancelled} flag. Identity is {@link UUID} only (CONTRACTS §5).
 */
public final class FactionJoinEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID factionId;
    private final UUID player;
    private boolean cancelled;

    public FactionJoinEvent(UUID factionId, UUID player) {
        this.factionId = factionId;
        this.player = player;
    }

    /** The faction being joined. */
    public UUID getFactionId() {
        return factionId;
    }

    /** The joining player. */
    public UUID getPlayer() {
        return player;
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
