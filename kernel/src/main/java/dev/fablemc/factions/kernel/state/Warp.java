package dev.fablemc.factions.kernel.state;

import java.util.UUID;

/**
 * A named faction warp.
 *
 * <p><b>Owning thread(s):</b> constructed by the writer, published in {@link WarpTable}.
 * <b>Mutability:</b> immutable value. <b>Reducer rule:</b> created only by the reducer.
 *
 * <p>{@code password} is nullable (no password); {@code useCost} is clamped {@code >= 0} at
 * intake. World is a dense {@code worldIdx} (AM-15). {@code creator} may be {@code null} for
 * legacy imports.
 */
public record Warp(String name, int worldIdx, double x, double y, double z, float yaw,
                   float pitch, UUID creator, long createdAt, String password, double useCost) {
}
