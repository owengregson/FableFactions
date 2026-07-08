package dev.fablemc.factions.kernel.effect;

import java.util.UUID;

import dev.fablemc.factions.kernel.intent.Origin;

/**
 * External-request effects: executed by adapters (Vault payout/refund, WorldGuard region sync, LWC
 * purge) and may re-enter the pipeline as intents.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * See {@link Effect} for the hierarchy contract.
 */
public sealed interface ExternalEffect extends Effect
        permits ExternalEffect.PayoutRequested, ExternalEffect.EscrowRefund,
        ExternalEffect.WgRegionUpsert, ExternalEffect.WgRegionRemove,
        ExternalEffect.LwcPurgeRequested {

    record PayoutRequested(long seq, Origin origin, long escrowId, UUID player, double amount)
            implements ExternalEffect {
    }

    record EscrowRefund(long seq, Origin origin, long escrowId, UUID player, double amount)
            implements ExternalEffect {
    }

    record WgRegionUpsert(long seq, Origin origin, int worldIdx, long key, int faction)
            implements ExternalEffect {
    }

    record WgRegionRemove(long seq, Origin origin, int worldIdx, long key)
            implements ExternalEffect {
    }

    record LwcPurgeRequested(long seq, Origin origin, int worldIdx, long key, int newOwner)
            implements ExternalEffect {
    }
}
