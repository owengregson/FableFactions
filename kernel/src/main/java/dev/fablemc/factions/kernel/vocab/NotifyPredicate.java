package dev.fablemc.factions.kernel.vocab;

/**
 * Which members a {@code Effect.NotifyFaction} targets (formerly the {@code MEMBERS_*} byte
 * constants on {@code Effect}).
 *
 * <p><b>Owning thread(s):</b> chosen by the reducer; read by the feedback fan-out on any thread.
 * <b>Mutability:</b> immutable enum. <b>Reducer rule:</b> n/a — a classification value.
 *
 * <p>{@link #code()} is the stable wire/DB code (the historical {@code MEMBERS_*} value) the
 * journal codec persists, never the ordinal.
 */
public enum NotifyPredicate {

    /** Every member of the faction. */
    MEMBERS_ALL(0),
    /** Officers and above only. */
    MEMBERS_OFFICERS(1);

    private final int code;

    NotifyPredicate(int code) {
        this.code = code;
    }

    /** The stable wire/DB code (historical {@code MEMBERS_*} value). */
    public int code() {
        return code;
    }

    private static final NotifyPredicate[] VALUES = values();

    /** The predicate with the given stable {@link #code()}; throws for an unknown code. */
    public static NotifyPredicate fromCode(int code) {
        for (NotifyPredicate p : VALUES) {
            if (p.code == code) {
                return p;
            }
        }
        throw new IllegalArgumentException("unknown NotifyPredicate code: " + code);
    }
}
