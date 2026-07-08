package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

/**
 * Travel intents: faction home set/clear and the warp create/delete/password/cost vocabulary.
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. See {@link Intent} for the hierarchy contract.
 */
public sealed interface TravelIntent extends Intent
        permits TravelIntent.SetHome, TravelIntent.UnsetHome, TravelIntent.SetWarp,
        TravelIntent.DeleteWarp, TravelIntent.SetWarpPassword, TravelIntent.SetWarpCost {

    /** Set {@code faction}'s home. */
    record SetHome(int faction, int worldIdx, double x, double y, double z, float yaw, float pitch,
                   UUID actor) implements TravelIntent {
    }

    /** Clear {@code faction}'s home. */
    record UnsetHome(int faction, UUID actor) implements TravelIntent {
    }

    /** Create/update warp {@code name}. */
    record SetWarp(int faction, String name, int worldIdx, double x, double y, double z, float yaw,
                   float pitch, UUID creator) implements TravelIntent {
    }

    /** Delete warp {@code name}. */
    record DeleteWarp(int faction, String name, UUID actor) implements TravelIntent {
    }

    /** Set/clear warp {@code name}'s password ({@code null}/blank clears). */
    record SetWarpPassword(int faction, String name, String password, UUID actor)
            implements TravelIntent {
    }

    /** Set warp {@code name}'s per-use cost. */
    record SetWarpCost(int faction, String name, double cost, UUID actor) implements TravelIntent {
    }
}
