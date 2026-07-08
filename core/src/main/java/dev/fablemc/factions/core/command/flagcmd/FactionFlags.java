package dev.fablemc.factions.core.command.flagcmd;

import java.util.Locale;

import dev.fablemc.factions.kernel.state.Faction;

/**
 * The faction-flag id ↔ ordinal registry shared by {@code /f flag} and {@code /fa flag}
 * (ref-commands-admin.md §4.1). Ids are the reference kebab strings, indexed by the kernel
 * {@code Faction.FLAG_*} ordinal, so the command layer speaks ids and the intent carries the
 * ordinal.
 *
 * <p><b>Owning thread(s):</b> pure static, called on a command thread. <b>Mutability:</b> stateless
 * (the id table is a shared immutable array; callers never mutate it).
 */
public final class FactionFlags {

    private FactionFlags() {
    }

    /** Flag ids indexed by {@code Faction.FLAG_*} ordinal (PVP..OPEN). */
    private static final String[] IDS = {
            "pvp",           // FLAG_PVP
            "friendly-fire", // FLAG_FRIENDLY_FIRE
            "explosions",    // FLAG_EXPLOSIONS
            "fire-spread",   // FLAG_FIRE_SPREAD
            "open",          // FLAG_OPEN
    };

    /** The id of a {@code Faction.FLAG_*} ordinal (e.g. {@code "friendly-fire"}). */
    public static String id(int flagOrdinal) {
        return IDS[flagOrdinal];
    }

    /** The {@code Faction.FLAG_*} ordinal for {@code id} (case-insensitive), or {@code -1} if unknown. */
    public static int ordinalOf(String id) {
        if (id == null) {
            return -1;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(normalized)) {
                return i;
            }
        }
        return -1;
    }

    /** A fresh array of every flag id (for tab-completion; the caller may mutate its copy). */
    public static String[] ids() {
        String[] copy = new String[Faction.FLAG_COUNT];
        System.arraycopy(IDS, 0, copy, 0, Faction.FLAG_COUNT);
        return copy;
    }

    /**
     * Parses a flag value word: {@code on/true/yes → TRUE}, {@code off/false/no → FALSE} (case
     * insensitive), else {@code null} (shared by {@code /f flag set} and {@code /fa flag}).
     */
    public static Boolean parseValue(String raw) {
        if (raw == null) {
            return null;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "on":
            case "true":
            case "yes":
                return Boolean.TRUE;
            case "off":
            case "false":
            case "no":
                return Boolean.FALSE;
            default:
                return null;
        }
    }
}
