package dev.fablemc.factions.kernel.state;

/**
 * A named team-chest reference.
 *
 * <p><b>Owning thread(s):</b> constructed by the writer, published in {@link ChestTable}.
 * <b>Mutability:</b> immutable value. <b>Reducer rule:</b> created only by the reducer.
 *
 * <p>The kernel keeps only a {@code blobRef} identity for the serialized inventory contents;
 * the actual Base64/byte payload lives in storage (proposal-C {@code ChestContentsChanged}
 * carries {@code blobRef}). {@code name} is the normalized (trimmed, lower-cased) chest name.
 */
public record ChestRef(String name, long blobRef, long createdAt) {

    /** Sentinel {@code blobRef} for a freshly created, empty chest. */
    public static final long EMPTY_BLOB = 0L;
}
