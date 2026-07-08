package dev.fablemc.factions.core.storage.project;

import dev.fablemc.factions.kernel.effect.EconomyEffect;
import dev.fablemc.factions.kernel.vocab.BankTxType;

/**
 * Projects economy effects into the faction {@code money} column and the {@code bank_transactions}
 * ledger: bank changes (deposit/withdraw/transfer) and tax charges.
 *
 * <p><b>Owning thread(s):</b> {@code fable-storage}. <b>Mutability:</b> stateless static applier;
 * all mutable state lives in the passed {@link ProjectionContext}.
 */
public final class EconomyProjection {

    private EconomyProjection() {
    }

    public static void apply(ProjectionContext ctx, EconomyEffect.BankChanged x) {
        String fid = ctx.factionId(x.faction());
        if (fid != null) {
            ctx.factionUpdate(x.faction(), "`money`=?", x.balance());
            bankTxInsert(ctx, x.seq(), fid, txLabel(x.txType()), x.delta(),
                    x.actor() == null ? null : x.actor().toString(),
                    ctx.factionId(x.counterparty()), x.note());
        }
    }

    public static void apply(ProjectionContext ctx, EconomyEffect.TaxCharged x) {
        String fid = ctx.factionId(x.faction());
        if (fid != null) {
            ctx.factionUpdate(x.faction(), "`money`=?", x.balance());
            bankTxInsert(ctx, x.seq(), fid, "TAX", -x.amount(), null, null, null);
        }
    }

    private static void bankTxInsert(ProjectionContext ctx, long seq, String factionId,
                                     String type, double amount, String actor,
                                     String counterparty, String note) {
        ctx.upsertById("bank_transactions", new String[] {"id", "faction_id", "actor_uuid", "type",
                "amount", "counterparty_faction_id", "created_at", "note"},
                ctx.ledgerId(seq), factionId, actor, type, amount, counterparty, ctx.now(), note);
    }

    /**
     * The reference-parity {@code bank_transactions.type} label. Deliberately an exhaustive switch
     * (no {@code default}) rather than {@code name()}: the compiler flags a new {@link BankTxType},
     * and an enum-constant rename can never silently change the persisted DB vocabulary.
     */
    private static String txLabel(BankTxType txType) {
        return switch (txType) {
            case DEPOSIT -> "DEPOSIT";
            case WITHDRAW -> "WITHDRAW";
            case TRANSFER -> "TRANSFER";
            case TAX -> "TAX";
        };
    }
}
