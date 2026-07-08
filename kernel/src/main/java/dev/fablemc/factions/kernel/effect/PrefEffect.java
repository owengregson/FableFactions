package dev.fablemc.factions.kernel.effect;

import java.util.UUID;

import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Faction-flag and per-player preference effects, plus the war-shield change.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * See {@link Effect} for the hierarchy contract.
 */
public sealed interface PrefEffect extends Effect
        permits PrefEffect.FlagChanged, PrefEffect.PrefChanged, PrefEffect.LocaleChanged,
        PrefEffect.AutoModeChanged, PrefEffect.FlyChanged, PrefEffect.OverrideChanged,
        PrefEffect.ShieldChanged {

    record FlagChanged(long seq, Origin origin, int faction, int flag, boolean value)
            implements PrefEffect {
    }

    record PrefChanged(long seq, Origin origin, UUID player, int prefBit, boolean value)
            implements PrefEffect {
    }

    record LocaleChanged(long seq, Origin origin, UUID player, int localeIdx)
            implements PrefEffect {
    }

    record AutoModeChanged(long seq, Origin origin, UUID player, int mode) implements PrefEffect {
    }

    record FlyChanged(long seq, Origin origin, UUID player, boolean on) implements PrefEffect {
    }

    record OverrideChanged(long seq, Origin origin, UUID player, boolean on)
            implements PrefEffect {
    }

    record ShieldChanged(long seq, Origin origin, int faction, int startHour, int durationHours)
            implements PrefEffect {
    }
}
