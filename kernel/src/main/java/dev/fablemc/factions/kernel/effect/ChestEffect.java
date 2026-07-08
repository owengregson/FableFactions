package dev.fablemc.factions.kernel.effect;

import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Team-chest effects: create/delete and contents change.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * See {@link Effect} for the hierarchy contract.
 */
public sealed interface ChestEffect extends Effect
        permits ChestEffect.ChestCreated, ChestEffect.ChestDeleted,
        ChestEffect.ChestContentsChanged {

    record ChestCreated(long seq, Origin origin, int faction, String name) implements ChestEffect {
    }

    record ChestDeleted(long seq, Origin origin, int faction, String name) implements ChestEffect {
    }

    record ChestContentsChanged(long seq, Origin origin, int faction, String name, long blobRef)
            implements ChestEffect {
    }
}
