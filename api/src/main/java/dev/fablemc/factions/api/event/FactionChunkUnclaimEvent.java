package dev.fablemc.factions.api.event;

import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a chunk is unclaimed (proposal-C §10.2), during pre-flight on the caller
 * thread; cancelling aborts the unclaim.
 *
 * <p><b>Owning thread(s):</b> the caller's region/main thread. <b>Mutability:</b> only the
 * {@code cancelled} flag. Identity is {@link String} / {@code int} / {@link UUID} only
 * (CONTRACTS §5).
 */
public final class FactionChunkUnclaimEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String world;
    private final int chunkX;
    private final int chunkZ;
    private final UUID factionId;
    private final UUID actor;
    private boolean cancelled;

    public FactionChunkUnclaimEvent(String world, int chunkX, int chunkZ, UUID factionId,
                                    UUID actor) {
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.factionId = factionId;
        this.actor = actor;
    }

    public String getWorld() {
        return world;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    /** The faction that previously owned the chunk. */
    public UUID getFactionId() {
        return factionId;
    }

    /** The player performing the unclaim ({@code null} for admin/system unclaims). */
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
