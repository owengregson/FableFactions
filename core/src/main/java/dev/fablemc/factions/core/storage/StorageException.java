package dev.fablemc.factions.core.storage;

/**
 * Wraps a storage-layer failure (JDBC, checkpoint, projection). Thrown only on the
 * {@code fable-storage} thread; the projector's failure boundary logs it and keeps the
 * journal as the durable authority (the DB catches up on the next flush or boot replay).
 *
 * <p><b>Owning thread(s):</b> fable-storage. <b>Mutability:</b> immutable.</p>
 */
public final class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
