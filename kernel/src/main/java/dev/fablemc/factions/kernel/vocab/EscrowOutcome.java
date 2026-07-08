package dev.fablemc.factions.kernel.vocab;

/**
 * The settlement outcome carried by {@code SettleEscrow} (formerly the {@code Intent.ESCROW_OK}/
 * {@code ESCROW_FAILED} byte constants).
 *
 * <p><b>Owning thread(s):</b> produced by the external Vault adapter, read by the reducer's
 * economy branches. <b>Mutability:</b> immutable enum. <b>Reducer rule:</b> n/a.
 *
 * <p>{@link #code()} is the stable wire/DB code (historical value), never the ordinal.
 */
public enum EscrowOutcome {

    /** The external mutation succeeded. */
    OK(0),
    /** The external mutation failed (Vault failed) — triggers compensation. */
    FAILED(1);

    private final int code;

    EscrowOutcome(int code) {
        this.code = code;
    }

    /** The stable wire/DB code (historical value). */
    public int code() {
        return code;
    }

    private static final EscrowOutcome[] VALUES = values();

    /** The outcome with the given stable {@link #code()}; throws for an unknown code. */
    public static EscrowOutcome fromCode(int code) {
        for (EscrowOutcome o : VALUES) {
            if (o.code == code) {
                return o;
            }
        }
        throw new IllegalArgumentException("unknown EscrowOutcome code: " + code);
    }
}
