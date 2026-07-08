package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

import dev.fablemc.factions.kernel.vocab.EscrowOutcome;

/**
 * Economy intents: bank credit/withdraw/transfer, escrow settlement, and the periodic tax sweep
 * (paged).
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. See {@link Intent} for the hierarchy contract.
 */
public sealed interface EconomyIntent extends Intent
        permits EconomyIntent.CreditBank, EconomyIntent.RequestBankWithdrawal,
        EconomyIntent.SettleEscrow, EconomyIntent.TransferBank, EconomyIntent.TaxSweep,
        EconomyIntent.TaxSweepPage {

    /** Deposit phase-2: credit {@code faction}'s bank, settling escrow {@code escrowId}. */
    record CreditBank(int faction, double amount, UUID actor, long escrowId)
            implements EconomyIntent {
    }

    /** Request a bank withdrawal (reducer debits and opens the payout escrow). */
    record RequestBankWithdrawal(int faction, double amount, UUID actor) implements EconomyIntent {
    }

    /** Settle escrow {@code escrowId} with {@code outcome}. */
    record SettleEscrow(long escrowId, EscrowOutcome outcome) implements EconomyIntent {
    }

    /** Transfer {@code amount} from {@code from}'s bank to {@code to}'s bank. */
    record TransferBank(int from, int to, double amount, UUID actor) implements EconomyIntent {
    }

    /** Periodic tax sweep for tick {@code tick} (coalesced). Paged (AM-5). */
    record TaxSweep(int tick) implements EconomyIntent {
    }

    /** A tax-sweep page over factions, at {@code cursor}, for tick {@code tick}. */
    record TaxSweepPage(int tick, int cursor) implements EconomyIntent {
    }
}
