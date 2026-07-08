package dev.fablemc.factions.api.event;

import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a chunk is claimed for a faction (proposal-C §10.2), during pre-flight on the
 * caller thread; cancelling aborts the claim.
 *
 * <p><b>Owning thread(s):</b> the caller's region/main thread. <b>Mutability:</b> only the
 * {@code cancelled} flag. {@code overclaimedFrom} is {@code null} when the chunk was wilderness
 * (proposal-C §10.2). Identity is {@link String} / {@code int} / {@link UUID} only
 * (CONTRACTS §5).
 */
public final class FactionChunkClaimEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String world;
    private final int chunkX;
    private final int chunkZ;
    private final UUID factionId;
    private final UUID overclaimedFrom;
    private final UUID actor;
    private boolean cancelled;

    public FactionChunkClaimEvent(String world, int chunkX, int chunkZ, UUID factionId,
                                  UUID overclaimedFrom, UUID actor) {
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.factionId = factionId;
        this.overclaimedFrom = overclaimedFrom;
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

    /** The faction claiming the chunk. */
    public UUID getFactionId() {
        return factionId;
    }

    /** The faction being overclaimed, or {@code null} if the chunk was wilderness. */
    public UUID getOverclaimedFrom() {
        return overclaimedFrom;
    }

    /** The player performing the claim ({@code null} for admin/system claims). */
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
