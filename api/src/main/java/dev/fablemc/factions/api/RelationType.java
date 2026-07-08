package dev.fablemc.factions.api;

/**
 * The relation kinds a caller of the public API observes or requests.
 *
 * <p><b>Owning thread(s):</b> any (immutable enum). <b>Mutability:</b> immutable value.
 *
 * <p>This is the API-surface mirror of the kernel's internal relation vocabulary — it is a
 * distinct enum defined <em>in</em> {@code :api} precisely so no kernel type leaks across the
 * public boundary (CONTRACTS §5). {@link #code()} is the explicit stable code matching the
 * kernel's relation vocabulary — the {@code :core} bridge maps by code, never by ordinal.
 * {@link #MEMBER} is the relation of a faction to itself (both factions are the same one).
 */
public enum RelationType {
    /** Same faction (self-relation). */
    MEMBER(0),
    /** An ally: shared protection, friendly-fire rules apply. */
    ALLY(1),
    /** A truce: limited protection, no PvP by default. */
    TRUCE(2),
    /** No declared relation (the default between two factions). */
    NEUTRAL(3),
    /** An enemy: hostile, land is overclaimable when raidable. */
    ENEMY(4);

    private final int code;

    RelationType(int code) {
        this.code = code;
    }

    /** The explicit stable cross-boundary code (matches the kernel vocabulary), never the ordinal. */
    public int code() {
        return code;
    }

    /** The relation with the given stable {@link #code()}; throws for an unknown code. */
    public static RelationType fromCode(int code) {
        switch (code) {
            case 0: return MEMBER;
            case 1: return ALLY;
            case 2: return TRUCE;
            case 3: return NEUTRAL;
            case 4: return ENEMY;
            default: throw new IllegalArgumentException("unknown relation code " + code);
        }
    }
}
