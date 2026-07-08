package dev.fablemc.factions.kernel.reduce;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import dev.fablemc.factions.kernel.vocab.FactionAuditAction;
import dev.fablemc.factions.kernel.config.BakedTables;
import dev.fablemc.factions.kernel.config.PowerConfig;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.ChestRules;
import dev.fablemc.factions.kernel.rules.ClaimRules;
import dev.fablemc.factions.kernel.rules.DisbandRules;
import dev.fablemc.factions.kernel.rules.EconomyRules;
import dev.fablemc.factions.kernel.rules.FactionAggregates;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.rules.InviteRules;
import dev.fablemc.factions.kernel.rules.MergeRules;
import dev.fablemc.factions.kernel.rules.MoneyMath;
import dev.fablemc.factions.kernel.rules.NameRules;
import dev.fablemc.factions.kernel.rules.PowerMath;
import dev.fablemc.factions.kernel.rules.PrefRules;
import dev.fablemc.factions.kernel.rules.RelationRules;
import dev.fablemc.factions.kernel.rules.RoleRules;
import dev.fablemc.factions.kernel.rules.TravelRules;
import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.ChestTable;
import dev.fablemc.factions.kernel.state.EscrowTable;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.Home;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.MergeTable;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.kernel.state.RelationKind;
import dev.fablemc.factions.kernel.state.Warp;
import dev.fablemc.factions.kernel.state.WarpTable;
import dev.fablemc.factions.kernel.state.ZoneStats;
import dev.fablemc.factions.kernel.vocab.Relation;
import dev.fablemc.factions.kernel.vocab.PowerSource;
import dev.fablemc.factions.kernel.vocab.PagePhase;
import dev.fablemc.factions.kernel.vocab.NotifyPredicate;
import dev.fablemc.factions.kernel.vocab.InviteRemovalReason;
import dev.fablemc.factions.kernel.vocab.EscrowOutcome;
import dev.fablemc.factions.kernel.vocab.EscrowKind;
import dev.fablemc.factions.kernel.vocab.BroadcastScope;
import dev.fablemc.factions.kernel.vocab.BankTxType;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.kernel.intent.SessionIntent;
import dev.fablemc.factions.kernel.intent.RoleIntent;
import dev.fablemc.factions.kernel.intent.RelationIntent;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.intent.PowerIntent;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.kernel.intent.ClaimIntent;
import dev.fablemc.factions.kernel.intent.ChestIntent;
import dev.fablemc.factions.kernel.effect.TravelEffect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.effect.SessionEffect;
import dev.fablemc.factions.kernel.effect.RoleEffect;
import dev.fablemc.factions.kernel.effect.RelationEffect;
import dev.fablemc.factions.kernel.effect.PrefEffect;
import dev.fablemc.factions.kernel.effect.PowerEffect;
import dev.fablemc.factions.kernel.effect.MembershipEffect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.effect.ExternalEffect;
import dev.fablemc.factions.kernel.effect.EconomyEffect;
import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.ChestEffect;
import dev.fablemc.factions.kernel.effect.AuditEffect;

/**
 * Economy intents: bank credit / withdrawal request / escrow settle / transfer / tax sweep (paged).
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved this code unchanged).
 */
final class EconomyReducer {

    private EconomyReducer() {
    }

    static void reduce(ReduceSupport s, EconomyIntent i) {
        if (i instanceof EconomyIntent.CreditBank x) {
            creditBank(s, x);
        } else if (i instanceof EconomyIntent.RequestBankWithdrawal x) {
            requestBankWithdrawal(s, x);
        } else if (i instanceof EconomyIntent.SettleEscrow x) {
            settleEscrow(s, x);
        } else if (i instanceof EconomyIntent.TransferBank x) {
            transferBank(s, x);
        } else if (i instanceof EconomyIntent.TaxSweep x) {
            taxSweepPage(s, x.tick(), 0);
        } else if (i instanceof EconomyIntent.TaxSweepPage x) {
            taxSweepPage(s, x.tick(), x.cursor());
        } else {
            throw new IllegalStateException("unhandled economy intent: " + i.getClass().getName());
        }
    }
    static void creditBank(ReduceSupport s, EconomyIntent.CreditBank c) {
        Faction f = s.resolve(c.faction());
        double amount = MoneyMath.round2(c.amount());
        if (f == null) {
            // Faction vanished after the Vault withdraw — refund the wallet (AM-7).
            s.effects.add(new ExternalEffect.EscrowRefund(s.seq, s.origin, c.escrowId(), c.actor(), amount));
            return;
        }
        double balance = MoneyMath.round2(f.bank() + amount);
        s.replaceFaction(FactionEdit.withBank(f, balance));
        s.effects.add(new EconomyEffect.BankChanged(s.seq, s.origin, c.faction(), amount, balance,
                BankTxType.DEPOSIT, c.actor(), ReduceSupport.NO_HANDLE, "Player deposit"));
        s.audit(c.faction(), c.actor(), FactionAuditAction.BANK_DEPOSIT, s.fmt2(amount));
    }

    static void requestBankWithdrawal(ReduceSupport s, EconomyIntent.RequestBankWithdrawal c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        double amount = MoneyMath.round2(c.amount());
        ReasonCode err = EconomyRules.validateWithdraw(s.state.config().economy(), f.bank(), amount);
        if (err != null) {
            s.reject(err);
            return;
        }
        double balance = MoneyMath.round2(f.bank() - amount);
        s.replaceFaction(FactionEdit.withBank(f, balance));
        long escrowId = s.seq;
        s.state = s.state.withEscrows(s.state.escrows().open(new EscrowTable.Escrow(escrowId,
                EscrowKind.WITHDRAW, c.actor(), f.idx(), amount, s.epochMillis)));
        s.effects.add(new EconomyEffect.BankChanged(s.seq, s.origin, c.faction(), -amount, balance,
                BankTxType.WITHDRAW, c.actor(), ReduceSupport.NO_HANDLE, "Player withdraw"));
        s.effects.add(new ExternalEffect.PayoutRequested(s.seq, s.origin, escrowId, c.actor(), amount));
        s.audit(c.faction(), c.actor(), FactionAuditAction.BANK_WITHDRAW, s.fmt2(amount));
    }

    static void settleEscrow(ReduceSupport s, EconomyIntent.SettleEscrow c) {
        EscrowTable.Escrow e = s.state.escrows().byId(c.escrowId());
        if (e == null) {
            return;
        }
        s.state = s.state.withEscrows(s.state.escrows().settle(c.escrowId()));
        if (c.outcome() == EscrowOutcome.FAILED) {
            if (e.kind() == EscrowKind.WITHDRAW) {
                // Vault deposit failed — re-credit the bank (conservation).
                Faction f = s.state.factions().at(e.factionOrdinal());
                if (f != null) {
                    double balance = MoneyMath.round2(f.bank() + e.amount());
                    s.replaceFaction(FactionEdit.withBank(f, balance));
                    s.effects.add(new EconomyEffect.BankChanged(s.seq, s.origin,
                            s.state.factions().handleOf(e.factionOrdinal()), e.amount(), balance,
                            BankTxType.DEPOSIT, e.player(), ReduceSupport.NO_HANDLE, "Withdraw rollback"));
                }
            } else {
                s.effects.add(new ExternalEffect.EscrowRefund(s.seq, s.origin, e.id(), e.player(), e.amount()));
            }
        }
    }

    static void transferBank(ReduceSupport s, EconomyIntent.TransferBank c) {
        Faction from = s.resolve(c.from());
        Faction to = s.resolve(c.to());
        if (from == null || to == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        // A self-transfer would double-apply the same slot (credit computed from the pre-debit
        // balance overwrites the debit), fabricating money — reject before touching the bank.
        if (from.idx() == to.idx()) {
            s.reject(ReasonCode.INVALID_AMOUNT);
            return;
        }
        double amount = MoneyMath.round2(c.amount());
        ReasonCode err = EconomyRules.validateTransfer(s.state.config().economy(), from.bank(), amount);
        if (err != null) {
            s.reject(err);
            return;
        }
        double fromBal = MoneyMath.round2(from.bank() - amount);
        double toBal = MoneyMath.round2(to.bank() + amount);
        s.replaceFaction(FactionEdit.withBank(from, fromBal));
        s.replaceFaction(FactionEdit.withBank(s.resolve(c.to()), toBal));
        s.effects.add(new EconomyEffect.BankChanged(s.seq, s.origin, c.from(), -amount, fromBal,
                BankTxType.TRANSFER, c.actor(), c.to(), "Transfer out"));
        s.effects.add(new EconomyEffect.BankChanged(s.seq, s.origin, c.to(), amount, toBal,
                BankTxType.TRANSFER, c.actor(), c.from(), "Transfer in"));
        s.audit(c.from(), c.actor(), FactionAuditAction.BANK_TRANSFER, s.fmt2(amount));
    }

    static void taxSweepPage(ReduceSupport s, int sweepTick, int cursor) {
        FactionArena arena = s.state.factions();
        int hw = arena.highWater();
        int processed = 0;
        int ord = cursor;
        for (; ord < hw && processed < Reducer.PAGE_SIZE; ord++) {
            Faction f = arena.at(ord);
            if (f == null || !f.isNormal()) {
                continue;
            }
            processed++;
            double tax = dev.fablemc.factions.kernel.rules.TaxMath
                    .taxFor(s.state.config().economy(), f.bank());
            if (tax <= 0.0) {
                continue;
            }
            double newBank = dev.fablemc.factions.kernel.rules.TaxMath.bankAfter(f.bank(), tax);
            Faction nf = FactionEdit.withBank(f, newBank);
            s.state = s.state.withFactions(s.state.factions().replace(ord, nf));
            arena = s.state.factions();
            s.effects.add(new EconomyEffect.TaxCharged(s.seq, s.origin, s.state.factions().handleOf(ord), tax,
                    newBank));
            s.effects.add(new EconomyEffect.BankChanged(s.seq, s.origin, s.state.factions().handleOf(ord), -tax,
                    newBank, BankTxType.TAX, null, ReduceSupport.NO_HANDLE, "Periodic bank tax"));
            s.notifyFaction(s.state.factions().handleOf(ord), "bank.tax-charged", s.fmt2(tax));
        }
        if (ord < hw) {
            s.continuation(new EconomyIntent.TaxSweepPage(sweepTick, ord));
        }
    }
}
