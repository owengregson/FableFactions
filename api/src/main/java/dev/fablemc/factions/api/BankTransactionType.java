package dev.fablemc.factions.api;

/**
 * The kind of bank movement carried by a {@link dev.fablemc.factions.api.event.FactionBankTransactionEvent}.
 *
 * <p><b>Owning thread(s):</b> any (immutable enum). <b>Mutability:</b> immutable value.
 *
 * <p>Defined in {@code :api} (not re-exported from the kernel) so the event surface carries no
 * kernel type (CONTRACTS §5). {@link #code()} is the explicit stable code matching the kernel's
 * bank-transaction vocabulary — the {@code :core} bridge maps by code, never by ordinal.
 */
public enum BankTransactionType {
    /** A member deposit into the faction bank. */
    DEPOSIT(0),
    /** A member withdrawal from the faction bank. */
    WITHDRAW(1),
    /** A transfer between two faction banks. */
    TRANSFER(2),
    /** An automatic tax charge. */
    TAX(3);

    private final int code;

    BankTransactionType(int code) {
        this.code = code;
    }

    /** The explicit stable cross-boundary code (matches the kernel vocabulary), never the ordinal. */
    public int code() {
        return code;
    }

    /** The type with the given stable {@link #code()}; throws for an unknown code. */
    public static BankTransactionType fromCode(int code) {
        switch (code) {
            case 0: return DEPOSIT;
            case 1: return WITHDRAW;
            case 2: return TRANSFER;
            case 3: return TAX;
            default: throw new IllegalArgumentException("unknown bank transaction code " + code);
        }
    }
}
