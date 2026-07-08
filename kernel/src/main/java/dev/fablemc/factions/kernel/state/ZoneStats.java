package dev.fablemc.factions.kernel.state;

/**
 * Aggregate counts for the system zones (SAFEZONE / WARZONE).
 *
 * <p><b>Owning thread(s):</b> read on any thread; mutated on the writer only.
 * <b>Mutability:</b> immutable, COW. <b>Reducer rule:</b> maintained by the reducer as
 * safezone/warzone chunks are assigned or removed via {@code SetZoneChunks}/{@code RemoveZoneChunk}.
 *
 * <p>The zone chunks themselves live in the {@link ClaimAtlas} under the SAFEZONE (ordinal 0)
 * and WARZONE (ordinal 1) sentinel handles; this record is the cheap running tally for
 * displays and reconciliation.
 */
public record ZoneStats(int safezoneChunks, int warzoneChunks) {

    private static final ZoneStats EMPTY = new ZoneStats(0, 0);

    /** The shared zero-count stats. */
    public static ZoneStats empty() {
        return EMPTY;
    }

    /** Returns a copy with the safezone chunk count adjusted by {@code delta}. */
    public ZoneStats withSafezoneDelta(int delta) {
        return new ZoneStats(Math.max(0, safezoneChunks + delta), warzoneChunks);
    }

    /** Returns a copy with the warzone chunk count adjusted by {@code delta}. */
    public ZoneStats withWarzoneDelta(int delta) {
        return new ZoneStats(safezoneChunks, Math.max(0, warzoneChunks + delta));
    }
}
