package dev.fablemc.factions.kernel.effect;

import java.util.UUID;

import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.vocab.PowerSource;

/**
 * Power effects: the power delta, freeze toggle, death-streak advance and raidable transition.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * {@code source} carries the cold-path {@link PowerSource} enum (the codec persists
 * {@code source.code()}). See {@link Effect} for the hierarchy contract.
 */
public sealed interface PowerEffect extends Effect
        permits PowerEffect.PowerChanged, PowerEffect.PowerFrozenChanged,
        PowerEffect.DeathStreakAdvanced, PowerEffect.RaidableChanged {

    record PowerChanged(long seq, Origin origin, UUID player, double before, double after,
                        PowerSource source, String reasonCode) implements PowerEffect {
    }

    record PowerFrozenChanged(long seq, Origin origin, UUID player, boolean frozen)
            implements PowerEffect {
    }

    record DeathStreakAdvanced(long seq, Origin origin, UUID player, int streak)
            implements PowerEffect {
    }

    record RaidableChanged(long seq, Origin origin, int faction, boolean nowRaidable)
            implements PowerEffect {
    }
}
