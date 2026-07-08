package dev.fablemc.factions.kernel.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.vocab.EscrowKind;

/**
 * Conservation bookkeeping for {@link EscrowTable}: the invariant
 * {@code wallet + bank + openEscrowTotal == constant} holds across every open/settle transition
 * (deposit, withdraw, and refund-on-failure).
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b>
 * test-confined fixtures; no shared state between tests.
 */
class EscrowTableTest {

    private static final int FACTION = 2;
    private static final int HANDLE = 2;

    @Test
    void depositWithdrawAndRefundConserveMoney() {
        final UUID player = UUID.randomUUID();
        double wallet = 100.0;
        double bank = 50.0;
        EscrowTable esc = EscrowTable.empty();
        final double total = wallet + bank + esc.openTotal();

        // ── Deposit saga: wallet → escrow → bank ─────────────────────────────────────────
        double dep = 30.0;
        wallet -= dep;
        esc = esc.open(new EscrowTable.Escrow(1, EscrowKind.DEPOSIT, player, FACTION, HANDLE,dep, 0));
        assertEquals(total, wallet + bank + esc.openTotal(), 1e-9, "conserved after deposit-open");
        assertTrue(esc.isOpen(1));

        esc = esc.settle(1); // credit bank
        bank += dep;
        assertEquals(total, wallet + bank + esc.openTotal(), 1e-9, "conserved after deposit-settle");
        assertFalse(esc.isOpen(1));
        assertEquals(0.0, esc.openTotal(), 1e-9);

        // ── Withdraw saga: bank → escrow → wallet ────────────────────────────────────────
        double wd = 20.0;
        bank -= wd;
        esc = esc.open(new EscrowTable.Escrow(2, EscrowKind.WITHDRAW, player, FACTION, HANDLE,wd, 0));
        assertEquals(total, wallet + bank + esc.openTotal(), 1e-9, "conserved after withdraw-open");

        esc = esc.settle(2); // deposit to wallet
        wallet += wd;
        assertEquals(total, wallet + bank + esc.openTotal(), 1e-9, "conserved after withdraw-settle");

        // ── Refund-on-failure: deposit debits wallet, Vault fails, refund wallet ──────────
        double buy = 15.0;
        wallet -= buy;
        esc = esc.open(new EscrowTable.Escrow(3, EscrowKind.BUY, player, FACTION, HANDLE,buy, 0));
        assertEquals(total, wallet + bank + esc.openTotal(), 1e-9, "conserved while buy in flight");

        esc = esc.settle(3); // compensating path removes the escrow ...
        wallet += buy;       // ... and refunds the wallet
        assertEquals(total, wallet + bank + esc.openTotal(), 1e-9, "conserved after refund");

        assertEquals(0, esc.size());
    }

    @Test
    void openTotalTracksMultipleConcurrentEscrows() {
        UUID p = UUID.randomUUID();
        EscrowTable esc = EscrowTable.empty()
                .open(new EscrowTable.Escrow(1, EscrowKind.DEPOSIT, p, FACTION, HANDLE,10.0, 0))
                .open(new EscrowTable.Escrow(2, EscrowKind.WITHDRAW, p, FACTION, HANDLE,5.5, 0))
                .open(new EscrowTable.Escrow(3, EscrowKind.BUY, p, FACTION, HANDLE,4.5, 0));
        assertEquals(3, esc.size());
        assertEquals(20.0, esc.openTotal(), 1e-9);

        esc = esc.settle(2);
        assertEquals(2, esc.size());
        assertEquals(14.5, esc.openTotal(), 1e-9);

        // Settling an unknown id is a no-op.
        EscrowTable same = esc.settle(999);
        assertEquals(esc.openTotal(), same.openTotal(), 1e-9);
        assertEquals(esc.size(), same.size());
    }

    @Test
    void cowIsolation() {
        UUID p = UUID.randomUUID();
        EscrowTable base = EscrowTable.empty()
                .open(new EscrowTable.Escrow(1, EscrowKind.DEPOSIT, p, FACTION, HANDLE,10.0, 0));
        EscrowTable settled = base.settle(1);
        assertTrue(base.isOpen(1), "old table unchanged by settle");
        assertEquals(10.0, base.openTotal(), 1e-9);
        assertFalse(settled.isOpen(1));
        assertEquals(0.0, settled.openTotal(), 1e-9);
    }
}
