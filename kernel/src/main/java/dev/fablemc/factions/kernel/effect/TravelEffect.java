package dev.fablemc.factions.kernel.effect;

import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Travel effects: faction home set/cleared and the warp set/delete/password/cost vocabulary.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * See {@link Effect} for the hierarchy contract.
 */
public sealed interface TravelEffect extends Effect
        permits TravelEffect.HomeSet, TravelEffect.HomeCleared, TravelEffect.WarpSet,
        TravelEffect.WarpDeleted, TravelEffect.WarpPasswordSet, TravelEffect.WarpCostSet {

    record HomeSet(long seq, Origin origin, int faction) implements TravelEffect {
    }

    record HomeCleared(long seq, Origin origin, int faction) implements TravelEffect {
    }

    record WarpSet(long seq, Origin origin, int faction, String name) implements TravelEffect {
    }

    record WarpDeleted(long seq, Origin origin, int faction, String name) implements TravelEffect {
    }

    record WarpPasswordSet(long seq, Origin origin, int faction, String name, boolean cleared)
            implements TravelEffect {
    }

    record WarpCostSet(long seq, Origin origin, int faction, String name, double cost)
            implements TravelEffect {
    }
}
