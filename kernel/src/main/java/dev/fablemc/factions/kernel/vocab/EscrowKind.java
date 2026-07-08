package dev.fablemc.factions.kernel.vocab;

/**
 * The saga kind of an open Vault escrow (formerly the {@code EscrowTable.KIND_*} byte constants).
 *
 * <p><b>Owning thread(s):</b> chosen by the reducer's economy branches; read on any thread.
 * <b>Mutability:</b> immutable enum. <b>Reducer rule:</b> n/a — a classification value carried by
 * the {@code EscrowTable.Escrow} saga record.
 *
 * <p>{@link #code()} is the stable wire/DB code (the historical {@code KIND_*} value), never the
 * ordinal.
 */
public enum EscrowKind {

    /** Deposit saga: wallet -&gt; escrow -&gt; bank. */
    DEPOSIT(0),
    /** Withdraw saga: bank -&gt; escrow -&gt; wallet. */
    WITHDRAW(1),
    /** Power-buy saga: wallet -&gt; escrow -&gt; power. */
    BUY(2);

    private final int code;

    EscrowKind(int code) {
        this.code = code;
    }

    /** The stable wire/DB code (historical {@code KIND_*} value). */
    public int code() {
        return code;
    }

    private static final EscrowKind[] VALUES = values();

    /** The kind with the given stable {@link #code()}; throws for an unknown code. */
    public static EscrowKind fromCode(int code) {
        for (EscrowKind k : VALUES) {
            if (k.code == code) {
                return k;
            }
        }
        throw new IllegalArgumentException("unknown EscrowKind code: " + code);
    }
}
