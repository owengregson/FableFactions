package dev.fablemc.factions.kernel.vocab;

/**
 * The audience of a {@code Effect.Broadcast} (formerly the {@code SCOPE_*} byte constants on
 * {@code Effect}).
 *
 * <p><b>Owning thread(s):</b> chosen by the reducer; read by the feedback fan-out on any thread.
 * <b>Mutability:</b> immutable enum. <b>Reducer rule:</b> n/a — a classification value.
 *
 * <p>{@link #code()} is the stable wire/DB code (the historical {@code SCOPE_*} value) the
 * journal codec persists, never the ordinal.
 */
public enum BroadcastScope {

    /** All online players. */
    SERVER(0),
    /** Staff (permission-gated) plus console. */
    STAFF(1);

    private final int code;

    BroadcastScope(int code) {
        this.code = code;
    }

    /** The stable wire/DB code (historical {@code SCOPE_*} value). */
    public int code() {
        return code;
    }

    private static final BroadcastScope[] VALUES = values();

    /** The scope with the given stable {@link #code()}; throws for an unknown code. */
    public static BroadcastScope fromCode(int code) {
        for (BroadcastScope s : VALUES) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown BroadcastScope code: " + code);
    }
}
