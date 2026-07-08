package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.state.PlayerLedger;

/**
 * Per-player preference bit / auto-mode / locale helpers (ref-commands-misc.md prefs).
 *
 * <p><b>Owning thread(s):</b> pure static. <b>Mutability:</b> stateless. <b>Reducer rule:</b>
 * the reducer edits the packed {@code prefsBits} through {@link PlayerLedger#withPref}/
 * {@link PlayerLedger#withAutoMode} and clamps values into range here.
 */
public final class PrefRules {

    private PrefRules() {
    }

    /** {@code true} when {@code bit} is a recognised toggle-able preference bit. */
    public static boolean isValidPrefBit(int bit) {
        switch (bit) {
            case PlayerLedger.PREF_NOTIFY_STATUS:
            case PlayerLedger.PREF_NOTIFY_INVITES:
            case PlayerLedger.PREF_NOTIFY_TERRITORY:
            case PlayerLedger.PREF_NOTIFY_TAX:
            case PlayerLedger.PREF_TERRITORY_TITLES:
            case PlayerLedger.PREF_OVERRIDING:
            case PlayerLedger.PREF_FLY:
                return true;
            default:
                return false;
        }
    }

    /** Clamps an auto-territory mode into {@code {OFF, CLAIM, UNCLAIM}}. */
    public static int clampAutoMode(int mode) {
        if (mode == PlayerLedger.AUTO_MODE_CLAIM || mode == PlayerLedger.AUTO_MODE_UNCLAIM) {
            return mode;
        }
        return PlayerLedger.AUTO_MODE_OFF;
    }

    /** Clamps a locale index into the signed-byte storage range. */
    public static int clampLocale(int localeIdx) {
        if (localeIdx < 0) {
            return 0;
        }
        if (localeIdx > 127) {
            return 127;
        }
        return localeIdx;
    }
}
