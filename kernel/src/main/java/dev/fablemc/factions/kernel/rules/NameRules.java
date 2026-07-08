package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.NameIndex;

/**
 * Faction-name validation and fold-case uniqueness gate (D-5, CONTRACTS §6.4).
 *
 * <p><b>Owning thread(s):</b> pure static — the command layer pre-validates on a snapshot and
 * the reducer re-checks in the same step that inserts, so a lost TOCTOU race and a pre-checked
 * failure surface the identical {@link ReasonCode}. <b>Mutability:</b> stateless.
 * <b>Reducer rule:</b> the reducer registers the folded name in {@link NameIndex} atomically.
 *
 * <p>Charset {@code [A-Za-z0-9_-]}, length {@code [3,32]}. Uniqueness is checked against the
 * fold-cased {@link NameIndex} (locale-independent lower-case).
 */
public final class NameRules {

    private NameRules() {
    }

    /** Minimum name length (inclusive). */
    public static final int MIN_LEN = 3;
    /** Maximum name length (inclusive). */
    public static final int MAX_LEN = 32;

    /** {@code true} when every character of {@code name} is in {@code [A-Za-z0-9_-]}. */
    public static boolean charsetOk(String name) {
        if (name == null) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates {@code name}'s length and charset only (not uniqueness). Returns {@code null}
     * when valid, else the mapped rejection.
     */
    public static ReasonCode validateFormat(String name) {
        if (name == null) {
            return ReasonCode.NAME_TOO_SHORT;
        }
        int len = name.length();
        if (len < MIN_LEN) {
            return ReasonCode.NAME_TOO_SHORT;
        }
        if (len > MAX_LEN) {
            return ReasonCode.NAME_TOO_LONG;
        }
        if (!charsetOk(name)) {
            return ReasonCode.NAME_INVALID;
        }
        return null;
    }

    /**
     * Full name validation: format then fold-case uniqueness against {@code names}. Returns
     * {@code null} when valid.
     */
    public static ReasonCode validate(String name, NameIndex names) {
        ReasonCode fmt = validateFormat(name);
        if (fmt != null) {
            return fmt;
        }
        if (names != null && names.contains(NameIndex.fold(name))) {
            return ReasonCode.NAME_TAKEN;
        }
        return null;
    }
}
