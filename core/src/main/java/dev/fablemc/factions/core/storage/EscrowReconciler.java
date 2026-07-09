package dev.fablemc.factions.core.storage;

import java.util.function.LongConsumer;
import java.util.logging.Logger;

import dev.fablemc.factions.kernel.state.EscrowTable;

/**
 * Boot-time reconciliation of the durable escrow sagas (AM-7, finding #3). {@code ff_escrows} rows
 * that were still {@code OPEN} when the previous instance died are loaded back into the boot
 * {@link KernelState} by {@code BaselineLoader}; this helper then submits a
 * {@code SettleEscrow(id, FAILED)} for each, which the kernel {@code EconomyReducer.settleEscrow}
 * turns into the conservative AM-7 compensation:
 *
 * <ul>
 *   <li>an unsettled <b>WITHDRAW</b> re-credits the faction bank (or, if the faction is gone, refunds
 *       the player's wallet) — the withdrawal debit is rolled back because the Vault payout may not
 *       have landed;</li>
 *   <li>an unsettled <b>DEPOSIT</b>/<b>BUY</b> refunds the player's wallet — the Vault withdraw was
 *       never observed as bank-credited.</li>
 * </ul>
 *
 * <p>This never duplicates money and, in the unavoidable crash window, may refund an amount the crash
 * already delivered (documented AM-7 trade-off). Every reconciliation is logged loudly.
 *
 * <p><b>Owning thread(s):</b> the boot thread, after the writer + projector daemons start (the
 * submitted {@code SettleEscrow} intents are reduced by the writer). <b>Mutability:</b> stateless.
 */
public final class EscrowReconciler {

    private EscrowReconciler() {
    }

    /**
     * Submits a FAILED settlement for every open escrow in {@code recovered}, via {@code settleFailed}
     * (typically {@code id -> bus.submitSystem(new SettleEscrow(id, FAILED))}). Returns how many were
     * reconciled.
     */
    public static int reconcile(EscrowTable recovered, LongConsumer settleFailed, Logger log) {
        if (recovered == null || recovered.size() == 0) {
            return 0;
        }
        int[] count = {0};
        recovered.forEach(escrow -> {
            log.warning("[boot] escrow recovery: settling FAILED escrow id=" + escrow.id() + " kind="
                    + escrow.kind() + " player=" + escrow.player() + " amount=" + escrow.amount()
                    + " — AM-7 compensating rollback (bank re-credit / wallet refund)");
            settleFailed.accept(escrow.id());
            count[0]++;
        });
        log.warning("[boot] escrow recovery: submitted " + count[0] + " compensating settlement(s)");
        return count[0];
    }
}
