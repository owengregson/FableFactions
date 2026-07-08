package dev.fablemc.factions.api;

/**
 * The kind of bank movement carried by a {@link dev.fablemc.factions.api.event.FactionBankTransactionEvent}.
 *
 * <p><b>Owning thread(s):</b> any (immutable enum). <b>Mutability:</b> immutable value.
 *
 * <p>Defined in {@code :api} (not re-exported from the kernel) so the event surface carries no
 * kernel type (CONTRACTS §5). The ordinals intentionally match the kernel's {@code TX_*} byte
 * codes so the {@code :core} bridge can translate by ordinal.
 */
public enum BankTransactionType {
    /** A member deposit into the faction bank. */
    DEPOSIT,
    /** A member withdrawal from the faction bank. */
    WITHDRAW,
    /** A transfer between two faction banks. */
    TRANSFER,
    /** An automatic tax charge. */
    TAX
}
