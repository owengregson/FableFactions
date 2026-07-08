package dev.fablemc.factions.api.event;

import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a faction is created (proposal-C §10.2). Cancellable events fire during
 * command pre-flight on the caller thread, before any side effect and before the intent is
 * enqueued; cancelling one aborts the operation.
 *
 * <p><b>Owning thread(s):</b> the caller's region/main thread (pre-flight). <b>Mutability:</b>
 * only the {@code cancelled} flag is mutable. All identity is expressed as {@link UUID} /
 * {@link String}; no kernel type and no post-1.13.2 Bukkit type appears here (CONTRACTS §5).
 */
public final class FactionCreateEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID creator;
    private final String name;
    private boolean cancelled;

    public FactionCreateEvent(UUID creator, String name) {
        this.creator = creator;
        this.name = name;
    }

    /** The player creating the faction. */
    public UUID getCreator() {
        return creator;
    }

    /** The requested faction name (unfolded, as typed). */
    public String getName() {
        return name;
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
