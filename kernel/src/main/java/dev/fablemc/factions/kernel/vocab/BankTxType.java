package dev.fablemc.factions.kernel.vocab;

/**
 * The kind of bank movement carried by {@code Effect.BankChanged} (formerly the {@code TX_*}
 * byte constants on {@code Effect}).
 *
 * <p><b>Owning thread(s):</b> chosen by the reducer's economy branches; read on any thread.
 * <b>Mutability:</b> immutable enum. <b>Reducer rule:</b> n/a — a classification value.
 *
 * <p>{@link #code()} is the stable wire/DB code (the historical {@code TX_*} value) the journal
 * codec persists; the {@code :api} {@code BankTransactionType} mirror shares these ordinals.
 */
public enum BankTxType {

    /** A member deposit into the faction bank. */
    DEPOSIT(0),
    /** A member withdrawal from the faction bank. */
    WITHDRAW(1),
    /** A transfer between two faction banks. */
    TRANSFER(2),
    /** An automatic tax charge. */
    TAX(3);

    private final int code;

    BankTxType(int code) {
        this.code = code;
    }

    /** The stable wire/DB code (historical {@code TX_*} value). */
    public int code() {
        return code;
    }

    private static final BankTxType[] VALUES = values();

    /** The type with the given stable {@link #code()}; throws for an unknown code. */
    public static BankTxType fromCode(int code) {
        for (BankTxType t : VALUES) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown BankTxType code: " + code);
    }
}
