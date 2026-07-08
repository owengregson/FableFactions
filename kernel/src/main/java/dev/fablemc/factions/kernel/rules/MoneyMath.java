package dev.fablemc.factions.kernel.rules;

import java.util.Locale;

/**
 * Money parsing and rounding, transcribed from the reference {@code MoneyParser} and
 * {@code roundMoney} (ref-services.md §8.2, §5(e); ref-bugs-concurrency Appendix).
 *
 * <p><b>Owning thread(s):</b> pure static, callable from the command layer (pre-validate) and
 * the reducer alike. <b>Mutability:</b> stateless. <b>Reducer rule:</b> the reducer rounds every
 * money mutation through {@link #round2} for exact parity with the reference bank chain.
 *
 * <p>{@link #parse} accepts a trailing magnitude suffix ({@code k}=1e3, {@code m}=1e6,
 * {@code b}=1e9, {@code t}=1e12) and returns {@link #INVALID} ({@code NaN}) on null/blank/parse
 * error, matching the reference {@code OptionalDouble.empty()} contract.
 */
public final class MoneyMath {

    private MoneyMath() {
    }

    /** Sentinel returned by {@link #parse} for an unparseable amount (test with {@link #isInvalid}). */
    public static final double INVALID = Double.NaN;

    /** {@code true} when {@code v} is the {@link #INVALID} sentinel. */
    public static boolean isInvalid(double v) {
        return Double.isNaN(v);
    }

    /** Rounds to 2 decimal places: {@code Math.round(v*100)/100.0}. */
    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Parses an amount with an optional magnitude suffix. Returns {@link #INVALID} when the input
     * is null, blank, or not a number.
     */
    public static double parse(String raw) {
        if (raw == null) {
            return INVALID;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return INVALID;
        }
        double mult = 1.0;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k':
                mult = 1e3;
                break;
            case 'm':
                mult = 1e6;
                break;
            case 'b':
                mult = 1e9;
                break;
            case 't':
                mult = 1e12;
                break;
            default:
                mult = 1.0;
                break;
        }
        String numeric = mult == 1.0 ? s : s.substring(0, s.length() - 1);
        if (numeric.isEmpty()) {
            return INVALID;
        }
        try {
            return Double.parseDouble(numeric) * mult;
        } catch (NumberFormatException ex) {
            return INVALID;
        }
    }
}
