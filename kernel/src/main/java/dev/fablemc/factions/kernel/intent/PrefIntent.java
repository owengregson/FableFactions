package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

/**
 * Faction-flag and per-player preference intents, plus the war-shield set/clear pair.
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. See {@link Intent} for the hierarchy contract.
 */
public sealed interface PrefIntent extends Intent
        permits PrefIntent.SetFactionFlag, PrefIntent.SetNotifyPref, PrefIntent.SetLocale,
        PrefIntent.SetAutoTerritoryMode, PrefIntent.SetTerritoryTitles, PrefIntent.SetFly,
        PrefIntent.SetOverriding, PrefIntent.SetShield, PrefIntent.ClearShield {

    /** Set a faction {@code flag} (a {@code Faction.FLAG_*} ordinal); {@code byAdmin} bypasses locks. */
    record SetFactionFlag(int faction, int flag, boolean value, boolean byAdmin, UUID actor)
            implements PrefIntent {
    }

    /** Toggle a per-player notification preference bit. */
    record SetNotifyPref(UUID player, int prefBit, boolean on) implements PrefIntent {
    }

    /** Set a player's locale. */
    record SetLocale(UUID player, int localeIdx) implements PrefIntent {
    }

    /** Set a player's auto-territory mode ({@code PlayerLedger.AUTO_MODE_*}). */
    record SetAutoTerritoryMode(UUID player, int mode) implements PrefIntent {
    }

    /** Toggle a player's territory-title display. */
    record SetTerritoryTitles(UUID player, boolean on) implements PrefIntent {
    }

    /** Toggle a player's faction flight state. */
    record SetFly(UUID player, boolean on) implements PrefIntent {
    }

    /** Toggle a player's admin protection-override state (persisted). */
    record SetOverriding(UUID player, boolean on) implements PrefIntent {
    }

    /** Set {@code faction}'s war-shield window (UTC start hour + duration). */
    record SetShield(int faction, int startHour, int durationHours, UUID actor)
            implements PrefIntent {
    }

    /** Clear {@code faction}'s war shield. */
    record ClearShield(int faction, UUID actor) implements PrefIntent {
    }
}
