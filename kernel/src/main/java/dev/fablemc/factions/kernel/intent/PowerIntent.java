package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

/**
 * Power intents: deaths, the periodic tick, purchases, and the admin power set/add/remove/reset/
 * freeze vocabulary.
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. See {@link Intent} for the hierarchy contract.
 */
public sealed interface PowerIntent extends Intent
        permits PowerIntent.RecordDeath, PowerIntent.PowerTick, PowerIntent.BuyPower,
        PowerIntent.AdminPowerSet, PowerIntent.AdminPowerAdd, PowerIntent.AdminPowerRemove,
        PowerIntent.AdminPowerReset, PowerIntent.SetPowerFrozen {

    /** A death: streak/multiplier/kill-scaling are computed inside the reducer from config+state. */
    record RecordDeath(UUID dead, UUID killer, int worldIdx, long chunkKey) implements PowerIntent {
    }

    /** Periodic power tick for tick {@code tick} (coalesced; O(online)). */
    record PowerTick(int tick) implements PowerIntent {
    }

    /** Buy {@code points} power for {@code cost}, backed by open escrow {@code escrowId}. */
    record BuyPower(UUID player, double points, double cost, long escrowId) implements PowerIntent {
    }

    /** Admin: set {@code target}'s power to {@code amount}. */
    record AdminPowerSet(UUID target, double amount, UUID actor, String reason)
            implements PowerIntent {
    }

    /** Admin: add {@code amount} power to {@code target}. */
    record AdminPowerAdd(UUID target, double amount, UUID actor, String reason)
            implements PowerIntent {
    }

    /** Admin: remove {@code amount} power from {@code target}. */
    record AdminPowerRemove(UUID target, double amount, UUID actor, String reason)
            implements PowerIntent {
    }

    /** Admin: reset {@code target}'s power to the configured max. */
    record AdminPowerReset(UUID target, UUID actor, String reason) implements PowerIntent {
    }

    /** Admin: freeze/unfreeze {@code target}'s power accrual. */
    record SetPowerFrozen(UUID target, boolean frozen, UUID actor, String reason)
            implements PowerIntent {
    }
}
