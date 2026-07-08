package dev.fablemc.factions.kernel.effect;

import java.util.UUID;

import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Faction lifecycle effects: create/disband/rename, description/MOTD, ownership transfer and the
 * merge request/completed pair.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * See {@link Effect} for the hierarchy contract.
 */
public sealed interface LifecycleEffect extends Effect
        permits LifecycleEffect.FactionCreated, LifecycleEffect.FactionDisbanded,
        LifecycleEffect.FactionRenamed, LifecycleEffect.DescriptionChanged,
        LifecycleEffect.MotdChanged, LifecycleEffect.OwnershipTransferred,
        LifecycleEffect.MergeRequested, LifecycleEffect.MergeCompleted {

    record FactionCreated(long seq, Origin origin, int faction, UUID id, String name)
            implements LifecycleEffect {
    }

    record FactionDisbanded(long seq, Origin origin, int faction, String name)
            implements LifecycleEffect {
    }

    record FactionRenamed(long seq, Origin origin, int faction, String oldName, String newName)
            implements LifecycleEffect {
    }

    record DescriptionChanged(long seq, Origin origin, int faction, String description)
            implements LifecycleEffect {
    }

    record MotdChanged(long seq, Origin origin, int faction, String motd)
            implements LifecycleEffect {
    }

    record OwnershipTransferred(long seq, Origin origin, int faction, UUID oldOwner, UUID newOwner)
            implements LifecycleEffect {
    }

    record MergeRequested(long seq, Origin origin, int sender, int target)
            implements LifecycleEffect {
    }

    record MergeCompleted(long seq, Origin origin, int sender, int target, int memberMoves,
                          int claimMoves, double bankMoved) implements LifecycleEffect {
    }
}
