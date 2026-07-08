package dev.fablemc.factions.platform.resolve;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import org.bukkit.ChatColor;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import dev.fablemc.factions.platform.probe.Probes;

/**
 * Scoreboard team prefix budgeting and name colour (CONTRACTS §3, version-deltas Risk #10).
 * Pre-1.13 the client and API enforce a 16-char team prefix/suffix limit
 * ({@code IllegalArgumentException} at 17); 1.13+ raises it to 64. Faction tags plus
 * {@code §} colour codes overflow 16 trivially, so the prefix is truncated to the budget
 * <b>after §-code accounting</b> — a colour-code pair is never split, so a dangling
 * {@code §} can never survive and eat the next glyph.
 *
 * <p>Name colour comes from the prefix tail pre-1.13 (the last colour code bleeds into the
 * name); 1.13+ needs {@code Team#setColor}, resolved reflectively so a pre-1.12 server
 * simply skips it.
 *
 * <p>Owning thread(s): the main/region thread (scoreboard mutation). Mutability class:
 * static-only; the pure {@link #truncateToBudget} math is thread-safe and Bukkit-free.
 */
public final class Nametags {

    /** The prefix/suffix character budget: 64 on a flattened (1.13+) server, else 16. */
    private static final int BUDGET = Probes.flattened() ? 64 : 16;

    /** {@code Team#setColor(ChatColor)} handle (1.12/1.13+), or {@code null} — prefix-tail colour. */
    private static final @Nullable MethodHandle SET_COLOR = Probes.virtualHandle(
            Team.class, "setColor", MethodType.methodType(void.class, ChatColor.class));

    private Nametags() {}

    /** This server's prefix/suffix character budget (16 pre-1.13, 64 after). */
    public static int budget() {
        return BUDGET;
    }

    /** Truncates {@code rawPrefix} to this server's budget without splitting a §-code pair. */
    public static @NotNull String truncatePrefix(@NotNull String rawPrefix) {
        return truncateToBudget(rawPrefix, BUDGET);
    }

    /**
     * Truncates {@code text} to at most {@code budget} raw characters, counting {@code §}
     * colour codes as the two characters they occupy and NEVER splitting a {@code §X} pair
     * (so the result is always a legal, self-consistent legacy string ≤ {@code budget}).
     * Pure and Bukkit-free — this is the unit-tested budget math.
     */
    public static @NotNull String truncateToBudget(@NotNull String text, int budget) {
        if (budget <= 0) {
            return "";
        }
        if (text.length() <= budget) {
            return text;
        }
        StringBuilder out = new StringBuilder(budget);
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (c == ChatColor.COLOR_CHAR && i + 1 < length) {
                if (out.length() + 2 > budget) {
                    break;
                }
                out.append(c).append(text.charAt(i + 1));
                i += 2;
            } else {
                if (out.length() + 1 > budget) {
                    break;
                }
                out.append(c);
                i += 1;
            }
        }
        return out.toString();
    }

    /**
     * Sets {@code team}'s name colour: via {@code setColor} on 1.13+ (returns {@code true}),
     * or a no-op on pre-1.13 where the colour rides the prefix tail (returns {@code false},
     * so the caller knows to encode the colour into the prefix instead).
     */
    public static boolean colorName(@NotNull Team team, @NotNull ChatColor color) {
        if (SET_COLOR == null) {
            return false;
        }
        try {
            SET_COLOR.invoke(team, color);
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Whether the native {@code Team#setColor} name-colour mechanism is available (1.13+). */
    public static boolean nativeColor() {
        return SET_COLOR != null;
    }

    /** For the boot report: the prefix budget and colour mechanism. */
    public static String describe() {
        return "prefixBudget=" + BUDGET + " nameColor=" + (SET_COLOR != null ? "setColor" : "prefix-tail");
    }
}
