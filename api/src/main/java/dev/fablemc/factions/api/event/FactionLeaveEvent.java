package dev.fablemc.factions.api.event;

import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player leaves (or is kicked from) a faction (proposal-C §10.2), during
 * pre-flight on the caller thread; cancelling aborts the departure.
 *
 * <p><b>Owning thread(s):</b> the caller's region/main thread. <b>Mutability:</b> only the
 * {@code cancelled} flag. Identity is {@link UUID} only (CONTRACTS §5).
 */
public final class FactionLeaveEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID factionId;
    private final UUID player;
    private final boolean kicked;
    private boolean cancelled;

    public FactionLeaveEvent(UUID factionId, UUID player, boolean kicked) {
        this.factionId = factionId;
        this.player = player;
        this.kicked = kicked;
    }

    /** The faction being left. */
    public UUID getFactionId() {
        return factionId;
    }

    /** The departing player. */
    public UUID getPlayer() {
        return player;
    }

    /** {@code true} when this departure is an involuntary kick rather than a voluntary leave. */
    public boolean isKicked() {
        return kicked;
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
