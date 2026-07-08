package dev.fablemc.factions.kernel.rules;

/**
 * Packs the pre-resolved actor facts a protection verdict needs into one {@code long}, so
 * {@link Verdicts#decide} takes no object reference and allocates nothing (CONTRACTS §2,
 * proposal-C §5c).
 *
 * <p><b>Owning thread(s):</b> the platform layer packs it once per event on the firing thread;
 * {@link Verdicts} unpacks it. <b>Mutability:</b> stateless codec. <b>Reducer rule:</b> n/a —
 * a read-side value derived from a snapshot plus the cached {@code factions.bypass} bit.
 *
 * <p>Layout (low→high): faction handle (32 bits, unsigned), member ordinal (29 bits, with the
 * all-ones pattern reserved for "absent" / {@code -1}), then the {@code bypass}/{@code overriding}/
 * {@code player} flag bits.
 */
public final class ActorBits {

    private ActorBits() {
    }

    private static final long HANDLE_MASK = 0xFFFFFFFFL;
    private static final int ORDINAL_SHIFT = 32;
    private static final int ORDINAL_BITS = 29;
    private static final long ORDINAL_MASK = (1L << ORDINAL_BITS) - 1; // 0x1FFFFFFF, all-ones ⇒ -1
    private static final long BYPASS_BIT = 1L << 61;
    private static final long OVERRIDING_BIT = 1L << 62;
    private static final long PLAYER_BIT = 1L << 63;

    /** Packs the resolved actor facts. {@code memberOrdinal} of {@code -1} means "no member". */
    public static long of(int memberOrdinal, int factionHandle, boolean bypass, boolean overriding,
                          boolean player) {
        long bits = (factionHandle & HANDLE_MASK)
                | ((memberOrdinal & ORDINAL_MASK) << ORDINAL_SHIFT);
        if (bypass) {
            bits |= BYPASS_BIT;
        }
        if (overriding) {
            bits |= OVERRIDING_BIT;
        }
        if (player) {
            bits |= PLAYER_BIT;
        }
        return bits;
    }

    /** The actor's faction handle (may be {@link dev.fablemc.factions.kernel.ids.FactionHandle#WILDERNESS}). */
    public static int factionHandle(long bits) {
        return (int) (bits & HANDLE_MASK);
    }

    /** The actor's member ordinal, or {@code -1} when absent. */
    public static int memberOrdinal(long bits) {
        int o = (int) ((bits >>> ORDINAL_SHIFT) & ORDINAL_MASK);
        return o == (int) ORDINAL_MASK ? -1 : o;
    }

    /** {@code true} when the actor holds {@code factions.bypass}. */
    public static boolean bypass(long bits) {
        return (bits & BYPASS_BIT) != 0;
    }

    /** {@code true} when the actor's admin protection-override is on. */
    public static boolean overriding(long bits) {
        return (bits & OVERRIDING_BIT) != 0;
    }

    /** {@code true} when the actor is a player (vs. a non-player damage source). */
    public static boolean player(long bits) {
        return (bits & PLAYER_BIT) != 0;
    }
}
