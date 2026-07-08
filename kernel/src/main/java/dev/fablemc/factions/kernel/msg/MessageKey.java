package dev.fablemc.factions.kernel.msg;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Interned message-catalog key handle.
 *
 * <p><b>Owning thread(s):</b> any (the intern table is thread-safe). <b>Mutability:</b>
 * immutable value. <b>Reducer rule:</b> n/a — a pure identifier carried by effects.
 *
 * <p>Kernel effects carry {@code MessageKey} + {@code String[] args} only; they never carry
 * rendered text (the catalog and locale resolution live in {@code :core}). Keys are interned
 * through {@link #of(String)} so equal keys share one instance, making equality and hashing
 * reference-cheap on the feedback fan-out path.
 */
public record MessageKey(String key) {

    private static final ConcurrentHashMap<String, MessageKey> INTERN = new ConcurrentHashMap<>();

    public MessageKey {
        if (key == null) {
            throw new IllegalArgumentException("message key must not be null");
        }
    }

    /** Returns the interned key for {@code key}, creating it once if absent. */
    public static MessageKey of(String key) {
        MessageKey existing = INTERN.get(key);
        if (existing != null) {
            return existing;
        }
        MessageKey created = new MessageKey(key);
        MessageKey prior = INTERN.putIfAbsent(key, created);
        return prior != null ? prior : created;
    }

    @Override
    public String toString() {
        return key;
    }
}
