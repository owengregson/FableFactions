package dev.fablemc.factions.kernel.state;

import java.util.Arrays;
import java.util.UUID;

import dev.fablemc.factions.kernel.vocab.EscrowKind;

/**
 * Open Vault sagas (escrows) — the durable in-flight-money record backing exactly-once
 * economy operations (AM-7, proposal-C §4.6).
 *
 * <p><b>Owning thread(s):</b> queries on any reader thread; mutators on the writer only.
 * <b>Mutability:</b> immutable, COW. <b>Reducer rule:</b> the reducer {@link #open}s an escrow
 * before any external Vault mutation and {@link #settle}s (removes) it when the saga completes;
 * a compensating refund is emitted on any non-delivery path.
 *
 * <p><b>Conservation bookkeeping:</b> an open escrow holds {@code amount} money "in flight". The
 * global invariant {@code Σ wallet + Σ bank + Σ open-escrow == constant} holds across every
 * open/settle transition because opening moves money into the escrow and settling moves it out
 * to exactly one destination. {@link #openTotal()} is the running in-flight sum used by the
 * conservation property test.
 */
public final class EscrowTable {

    /** One open escrow. Only open escrows are held; settlement removes the row. */
    public record Escrow(long id, EscrowKind kind, UUID player, int factionOrdinal, double amount,
                         long createdAt) {
    }

    private static final Escrow[] NONE = new Escrow[0];
    private static final EscrowTable EMPTY = new EscrowTable(NONE, 0.0);

    private final Escrow[] escrows;
    private final double openTotal;

    private EscrowTable(Escrow[] escrows, double openTotal) {
        this.escrows = escrows;
        this.openTotal = openTotal;
    }

    /** The shared empty table. */
    public static EscrowTable empty() {
        return EMPTY;
    }

    /** Number of open escrows. */
    public int size() {
        return escrows.length;
    }

    /** Total money currently parked in open escrows (conservation ledger). */
    public double openTotal() {
        return openTotal;
    }

    /** Open escrow {@code id}, or {@code null}. */
    public Escrow byId(long id) {
        for (Escrow e : escrows) {
            if (e.id() == id) {
                return e;
            }
        }
        return null;
    }

    /** {@code true} when escrow {@code id} is open. */
    public boolean isOpen(long id) {
        return byId(id) != null;
    }

    /** Returns a copy with {@code escrow} opened (its amount joins the in-flight total). */
    public EscrowTable open(Escrow escrow) {
        Escrow[] out = Arrays.copyOf(escrows, escrows.length + 1);
        out[escrows.length] = escrow;
        return new EscrowTable(out, openTotal + escrow.amount());
    }

    /**
     * Returns a copy with escrow {@code id} settled (removed): its amount leaves the in-flight
     * total for the destination the caller credits. No-op if the id is unknown.
     */
    public EscrowTable settle(long id) {
        int idx = -1;
        for (int i = 0; i < escrows.length; i++) {
            if (escrows[i].id() == id) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return this;
        }
        double amt = escrows[idx].amount();
        if (escrows.length == 1) {
            return EMPTY;
        }
        Escrow[] out = new Escrow[escrows.length - 1];
        System.arraycopy(escrows, 0, out, 0, idx);
        System.arraycopy(escrows, idx + 1, out, idx, escrows.length - idx - 1);
        return new EscrowTable(out, openTotal - amt);
    }

    /** Visits every open escrow (e.g. boot reconciliation). */
    public void forEach(EscrowVisitor visitor) {
        for (Escrow e : escrows) {
            visitor.visit(e);
        }
    }

    /** Callback for {@link #forEach}. */
    @FunctionalInterface
    public interface EscrowVisitor {
        void visit(Escrow escrow);
    }
}
