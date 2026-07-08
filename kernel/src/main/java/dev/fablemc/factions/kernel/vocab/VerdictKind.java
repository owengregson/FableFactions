package dev.fablemc.factions.kernel.vocab;

import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * Cold-path enum companion of the {@code Verdict} protection-verdict int codes. The hot verdict
 * path keeps the raw {@code int} ({@code Verdicts.decide(...)} returns an {@code int} and that
 * pinned signature DOES NOT change, CONTRACTS §2); this enum is the readable, off-hot-path
 * companion that also carries the deny {@link MessageKey} the listener renders when it cancels
 * the event.
 *
 * <p><b>Owning thread(s):</b> none — a classification value. <b>Mutability:</b> immutable enum.
 * <b>Reducer rule:</b> n/a — verdicts are read-side (protection) decisions, never state.
 *
 * <p>{@link #code()} is the stable code matching {@code Verdict.*}, never the ordinal;
 * {@link #ALLOW} is {@code 0} and carries no message. Each {@code DENY_*} maps to the reference
 * protection message key (ref-engines.md §7). The build-family denials share
 * {@code custom.protection.no-break}; the listener substitutes {@code custom.protection.no-place}
 * for place-family actions per the action it was checking.
 */
public enum VerdictKind {

    /** The action is permitted (carries no deny message). */
    ALLOW(0, null),
    /** Building in unclaimed wilderness is denied by config. */
    DENY_WILDERNESS(1, VerdictKind.NO_BREAK),
    /** No modification inside the safezone. */
    DENY_SAFEZONE(2, VerdictKind.NO_BREAK),
    /** No modification inside the warzone. */
    DENY_WARZONE(3, VerdictKind.NO_BREAK),
    /** No modification in enemy territory. */
    DENY_ENEMY(4, VerdictKind.NO_BREAK),
    /** No modification in a neutral faction's territory. */
    DENY_NEUTRAL(5, VerdictKind.NO_BREAK),
    /** No modification in an ally's territory. */
    DENY_ALLY(6, VerdictKind.NO_BREAK),
    /** No modification in a truce partner's territory. */
    DENY_TRUCE(7, VerdictKind.NO_BREAK),
    /** PvP is disabled by the territory's {@code pvp} flag. */
    DENY_PVP_FLAG(8, "custom.protection.pvp-territory"),
    /** Friendly fire is disabled within the attacker's faction. */
    DENY_FRIENDLY_FIRE(9, "custom.protection.friendly-fire-disabled"),
    /** Explosions are disabled by the territory's {@code explosions} flag. */
    DENY_EXPLOSIONS(10, VerdictKind.NO_BREAK),
    /** Fire spread is disabled by the territory's {@code fire-spread} flag. */
    DENY_FIRE(11, VerdictKind.NO_BREAK),
    /** An internal error denied the action fail-safe. */
    DENY_INTERNAL(12, "general.internal-error");

    /** Shared deny key for the build-family denials (compile-time constant; see class javadoc). */
    private static final String NO_BREAK = "custom.protection.no-break";

    private final int code;
    private final MessageKey denyMessage;

    VerdictKind(int code, String denyMessageKey) {
        this.code = code;
        this.denyMessage = denyMessageKey == null ? null : MessageKey.of(denyMessageKey);
    }

    /** The stable code (matching {@code Verdict.*}). */
    public int code() {
        return code;
    }

    /** {@code true} when this verdict permits the action ({@link #ALLOW}). */
    public boolean allowed() {
        return this == ALLOW;
    }

    /** The deny message key, interned once at class initialization; {@code null} for {@link #ALLOW}. */
    public MessageKey messageKey() {
        return denyMessage;
    }

    private static final VerdictKind[] VALUES = values();

    /** The verdict with the given stable {@link #code()}; throws for an unknown code. */
    public static VerdictKind fromCode(int code) {
        for (VerdictKind v : VALUES) {
            if (v.code == code) {
                return v;
            }
        }
        throw new IllegalArgumentException("unknown VerdictKind code: " + code);
    }
}
