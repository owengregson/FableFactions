package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

/**
 * Team-chest intents: create/delete and the guarded contents commit.
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. See {@link Intent} for the hierarchy contract.
 */
public sealed interface ChestIntent extends Intent
        permits ChestIntent.CreateChest, ChestIntent.DeleteChest, ChestIntent.CommitChestContents {

    /** Create team chest {@code name}. */
    record CreateChest(int faction, String name, UUID actor) implements ChestIntent {
    }

    /** Delete team chest {@code name}. */
    record DeleteChest(int faction, String name, UUID actor) implements ChestIntent {
    }

    /** Commit new contents (blob ref) for chest {@code name}, guarded by {@code sessionNonce}. */
    record CommitChestContents(int faction, String name, long blobRef, long sessionNonce,
                               UUID actor) implements ChestIntent {
    }
}
