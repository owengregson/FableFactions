package dev.fablemc.factions.kernel.effect;

import java.util.UUID;

import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.vocab.BankTxType;

/**
 * Economy effects: bank movement and tax charge.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * {@code txType} carries the cold-path {@link BankTxType} enum (the codec persists
 * {@code txType.code()}). See {@link Effect} for the hierarchy contract.
 */
public sealed interface EconomyEffect extends Effect
        permits EconomyEffect.BankChanged, EconomyEffect.TaxCharged {

    record BankChanged(long seq, Origin origin, int faction, double delta, double balance,
                       BankTxType txType, UUID actor, int counterparty, String note)
            implements EconomyEffect {
    }

    record TaxCharged(long seq, Origin origin, int faction, double amount, double balance)
            implements EconomyEffect {
    }
}
