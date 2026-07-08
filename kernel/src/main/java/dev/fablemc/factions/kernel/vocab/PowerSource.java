package dev.fablemc.factions.kernel.vocab;

/**
 * The provenance of a power change (formerly the {@code PowerMath.SRC_*} int constants).
 *
 * <p><b>Owning thread(s):</b> chosen by the reducer's power branches; read on any thread from
 * {@code Effect.PowerChanged}. <b>Mutability:</b> immutable enum. <b>Reducer rule:</b> n/a — a
 * classification value, never state.
 *
 * <p>{@link #code()} is the stable wire/DB code (the historical {@code SRC_*} value); it is what
 * the journal codec persists, never the ordinal. {@link #fromCode(int)} is the inverse.
 * {@link #isAdmin()}/{@link #isAutomatic()}/{@link #sourceName()} carry the reference semantics
 * that used to live as static helpers on {@code PowerMath}.
 */
public enum PowerSource {

    /** Online passive regeneration. */
    REGEN_ONLINE(0, "REGEN_ONLINE"),
    /** Offline passive regeneration. */
    REGEN_OFFLINE(1, "REGEN_OFFLINE"),
    /** Death loss (streak-scaled, world/zone-multiplied). */
    DEATH(2, "DEATH"),
    /** Kill gain (F3 scaled). */
    KILL(3, "KILL"),
    /** Purchased power (Vault-backed). */
    BUY(4, "BUY"),
    /** Admin: set to an absolute value. */
    ADMIN_SET(5, "ADMIN_SET"),
    /** Admin: add a delta. */
    ADMIN_ADD(6, "ADMIN_ADD"),
    /** Admin: remove a delta. */
    ADMIN_REMOVE(7, "ADMIN_REMOVE"),
    /** Admin: reset to the configured max. */
    ADMIN_RESET(8, "ADMIN_RESET");

    private final int code;
    private final String sourceName;

    PowerSource(int code, String sourceName) {
        this.code = code;
        this.sourceName = sourceName;
    }

    /** The stable wire/DB code (historical {@code SRC_*} value). */
    public int code() {
        return code;
    }

    /** The uppercase reference source name for reason codes / power-history rows. */
    public String sourceName() {
        return sourceName;
    }

    /** {@code true} for the three {@code ADMIN_*} sources (never freeze/clamp gated). */
    public boolean isAdmin() {
        return code >= ADMIN_SET.code;
    }

    /** {@code true} for the automatic sources (REGEN/DEATH/KILL) that the per-event clamp gates. */
    public boolean isAutomatic() {
        return this == REGEN_ONLINE || this == REGEN_OFFLINE || this == DEATH || this == KILL;
    }

    private static final PowerSource[] VALUES = values();

    /** The source with the given stable {@link #code()}; throws for an unknown code. */
    public static PowerSource fromCode(int code) {
        for (PowerSource s : VALUES) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown PowerSource code: " + code);
    }
}
