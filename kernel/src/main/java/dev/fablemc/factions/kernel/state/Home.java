package dev.fablemc.factions.kernel.state;

/**
 * A faction home location.
 *
 * <p><b>Owning thread(s):</b> constructed by the writer, published in {@link Faction}.
 * <b>Mutability:</b> immutable value. <b>Reducer rule:</b> created only by the reducer.
 *
 * <p>World is a dense {@code worldIdx} (AM-15), never a name string on hot paths. A
 * {@code null} {@link Faction#home()} means "no home set".
 */
public record Home(int worldIdx, double x, double y, double z, float yaw, float pitch) {
}
