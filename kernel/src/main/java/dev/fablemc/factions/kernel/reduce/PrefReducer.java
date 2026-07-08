package dev.fablemc.factions.kernel.reduce;

import dev.fablemc.factions.kernel.effect.PrefEffect;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.rules.PrefRules;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.PlayerLedger;

/**
 * Reduces the preference and flag intents: faction flags / notify-locale-autoterritory-titles-fly-overriding player prefs / shield.
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved the code; the P3 sweep standardized the
 * guard/emission shapes without behavior change).
 */
final class PrefReducer {

    private PrefReducer() {
    }

    static void reduce(ReduceSupport s, PrefIntent i) {
        if (i instanceof PrefIntent.SetFactionFlag x) {
            setFactionFlag(s, x);
        } else if (i instanceof PrefIntent.SetNotifyPref x) {
            setNotifyPref(s, x);
        } else if (i instanceof PrefIntent.SetLocale x) {
            setLocale(s, x);
        } else if (i instanceof PrefIntent.SetAutoTerritoryMode x) {
            setAutoTerritoryMode(s, x);
        } else if (i instanceof PrefIntent.SetTerritoryTitles x) {
            setTerritoryTitles(s, x);
        } else if (i instanceof PrefIntent.SetFly x) {
            setFly(s, x);
        } else if (i instanceof PrefIntent.SetOverriding x) {
            setOverriding(s, x);
        } else if (i instanceof PrefIntent.SetShield x) {
            setShield(s, x);
        } else if (i instanceof PrefIntent.ClearShield x) {
            clearShield(s, x);
        } else {
            throw new IllegalStateException("unhandled pref intent: " + i.getClass().getName());
        }
    }

    static void setFactionFlag(ReduceSupport s, PrefIntent.SetFactionFlag c) {
        Faction f = s.factionOrReject(c.faction());
        if (f == null) {
            return;
        }
        if (c.flag() < 0 || c.flag() >= Faction.FLAG_COUNT) {
            s.reject(ReasonCode.FLAG_INVALID);
            return;
        }
        if (!c.byAdmin() && !s.state.config().flagDefaults().editableOf(c.flag())) {
            s.reject(ReasonCode.FLAG_NOT_EDITABLE);
            return;
        }
        long bits = Faction.withFlag(f.flagBits(), c.flag(), c.value());
        s.replaceFaction(FactionEdit.withFlagBits(f, bits));
        s.emit(new PrefEffect.FlagChanged(s.seq, s.origin, c.faction(), c.flag(), c.value()));
    }

    static void setNotifyPref(ReduceSupport s, PrefIntent.SetNotifyPref c) {
        int ord = s.memberOrd(c.player());
        if (ord < 0 || !s.state.ledger().has(ord)) {
            s.reject(ReasonCode.PLAYER_NOT_FOUND);
            return;
        }
        if (!PrefRules.isValidPrefBit(c.prefBit())) {
            s.reject(ReasonCode.FLAG_INVALID);
            return;
        }
        int bits = PlayerLedger.withPref(s.state.ledger().prefsBits(ord), c.prefBit(), c.on());
        s.state = s.state.withLedger(s.state.ledger().withPrefsBits(ord, bits));
        s.emit(new PrefEffect.PrefChanged(s.seq, s.origin, c.player(), c.prefBit(), c.on()));
    }

    static void setLocale(ReduceSupport s, PrefIntent.SetLocale c) {
        int ord = s.ensureMember(c.player(), "");
        int locale = PrefRules.clampLocale(c.localeIdx());
        s.state = s.state.withLedger(s.state.ledger().withLocaleIdx(ord, locale));
        s.emit(new PrefEffect.LocaleChanged(s.seq, s.origin, c.player(), locale));
    }

    static void setAutoTerritoryMode(ReduceSupport s, PrefIntent.SetAutoTerritoryMode c) {
        int ord = s.ensureMember(c.player(), "");
        int mode = PrefRules.clampAutoMode(c.mode());
        int bits = PlayerLedger.withAutoMode(s.state.ledger().prefsBits(ord), mode);
        s.state = s.state.withLedger(s.state.ledger().withPrefsBits(ord, bits));
        s.emit(new PrefEffect.AutoModeChanged(s.seq, s.origin, c.player(), mode));
    }

    static void setTerritoryTitles(ReduceSupport s, PrefIntent.SetTerritoryTitles c) {
        int ord = s.ensureMember(c.player(), "");
        int bits = PlayerLedger.withPref(s.state.ledger().prefsBits(ord),
                PlayerLedger.PREF_TERRITORY_TITLES, c.on());
        s.state = s.state.withLedger(s.state.ledger().withPrefsBits(ord, bits));
        s.emit(new PrefEffect.PrefChanged(s.seq, s.origin, c.player(),
                PlayerLedger.PREF_TERRITORY_TITLES, c.on()));
    }

    static void setFly(ReduceSupport s, PrefIntent.SetFly c) {
        int ord = s.ensureMember(c.player(), "");
        int bits = PlayerLedger.withPref(s.state.ledger().prefsBits(ord), PlayerLedger.PREF_FLY, c.on());
        s.state = s.state.withLedger(s.state.ledger().withPrefsBits(ord, bits));
        s.emit(new PrefEffect.FlyChanged(s.seq, s.origin, c.player(), c.on()));
    }

    static void setOverriding(ReduceSupport s, PrefIntent.SetOverriding c) {
        int ord = s.ensureMember(c.player(), "");
        int bits = PlayerLedger.withPref(s.state.ledger().prefsBits(ord),
                PlayerLedger.PREF_OVERRIDING, c.on());
        s.state = s.state.withLedger(s.state.ledger().withPrefsBits(ord, bits));
        s.emit(new PrefEffect.OverrideChanged(s.seq, s.origin, c.player(), c.on()));
    }

    static void setShield(ReduceSupport s, PrefIntent.SetShield c) {
        Faction f = s.factionOrReject(c.faction());
        if (f == null) {
            return;
        }
        if (!s.state.config().land().warShieldEnabled()) {
            s.reject(ReasonCode.SHIELD_FEATURE_DISABLED);
            return;
        }
        if (c.startHour() < 0 || c.startHour() > 23) {
            s.reject(ReasonCode.SHIELD_INVALID_HOUR);
            return;
        }
        int maxDur = s.state.config().land().warShieldMaxDurationHours();
        if (c.durationHours() < 1 || c.durationHours() > maxDur) {
            s.reject(ReasonCode.SHIELD_INVALID_DURATION);
            return;
        }
        s.replaceFaction(FactionEdit.withShield(f, c.startHour(), c.durationHours()));
        s.emit(new PrefEffect.ShieldChanged(s.seq, s.origin, c.faction(), c.startHour(),
                c.durationHours()));
    }

    static void clearShield(ReduceSupport s, PrefIntent.ClearShield c) {
        Faction f = s.factionOrReject(c.faction());
        if (f == null) {
            return;
        }
        s.replaceFaction(FactionEdit.withShield(f, Faction.NO_SHIELD, 0));
        s.emit(new PrefEffect.ShieldChanged(s.seq, s.origin, c.faction(), Faction.NO_SHIELD, 0));
    }
}
