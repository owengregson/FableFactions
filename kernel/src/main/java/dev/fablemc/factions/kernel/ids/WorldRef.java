package dev.fablemc.factions.kernel.ids;

import java.util.UUID;

/**
 * Kernel-side world identity (AM-15).
 *
 * <p><b>Owning thread(s):</b> constructed at boot / on world load by the writer;
 * published inside the snapshot. <b>Mutability:</b> immutable value. <b>Reducer rule:</b>
 * created only by the reducer / boot builders.
 *
 * <p>Worlds are keyed on hot paths by a small dense {@code worldIdx} {@code int} — never by
 * object identity, never by name string. This value pairs that index with the stable world
 * {@link UUID} ({@code World#getUID}) and its display name; the {@code UUID}→{@code idx}
 * registry itself is maintained platform-side and fed into kernel state as intents.
 */
public record WorldRef(int idx, UUID id, String name) {

    /** Sentinel index meaning "no world" (e.g. an unresolved reference). */
    public static final int NONE = -1;

    public WorldRef {
        if (idx < 0) {
            throw new IllegalArgumentException("worldIdx must be >= 0: " + idx);
        }
    }
}
