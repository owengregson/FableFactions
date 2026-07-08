package dev.fablemc.factions.api;

/**
 * The relation kinds a caller of the public API observes or requests.
 *
 * <p><b>Owning thread(s):</b> any (immutable enum). <b>Mutability:</b> immutable value.
 *
 * <p>This is the API-surface mirror of the kernel's internal relation vocabulary — it is a
 * distinct enum defined <em>in</em> {@code :api} precisely so no kernel type leaks across the
 * public boundary (CONTRACTS §5). {@link #MEMBER} is the relation of a faction to itself (both
 * factions are the same one).
 */
public enum RelationType {
    /** Same faction (self-relation). */
    MEMBER,
    /** An ally: shared protection, friendly-fire rules apply. */
    ALLY,
    /** A truce: limited protection, no PvP by default. */
    TRUCE,
    /** No declared relation (the default between two factions). */
    NEUTRAL,
    /** An enemy: hostile, land is overclaimable when raidable. */
    ENEMY
}
