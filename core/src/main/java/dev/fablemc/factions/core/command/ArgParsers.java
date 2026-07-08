package dev.fablemc.factions.core.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.kernel.rules.MoneyMath;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.vocab.Relation;
import dev.fablemc.factions.platform.resolve.Players;

/**
 * Argument parsers shared by every command (CONTRACTS §4, ref-commands-core.md §4.6): typed
 * numeric / money parsing (money via the kernel {@link MoneyMath} so parity with the reducer's bank
 * chain holds), page parsing, on/off flags, relation and faction-name resolution over the snapshot,
 * online/offline player-name resolution, and the {@code --key=value}/{@code --key value}
 * long-option parser. Every method is null-tolerant and returns an absent / {@code null} sentinel
 * rather than throwing (command bodies rely on this).
 *
 * <p><b>Owning thread(s):</b> pure static (the snapshot / numeric parsers) or the sender's
 * region/main thread (the player-name resolvers, which read live server state).
 * <b>Mutability:</b> stateless.
 */
public final class ArgParsers {

    private ArgParsers() {
    }

    // ── numbers ────────────────────────────────────────────────────────────────────────────

    /** Parses a base-10 int; absent on {@code null}, blank, or malformed input. */
    public static OptionalInt parseInt(@Nullable String value) {
        if (value == null) {
            return OptionalInt.empty();
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(trimmed));
        } catch (NumberFormatException malformed) {
            return OptionalInt.empty();
        }
    }

    /** Parses a base-10 int, returning {@code fallback} when absent / malformed. */
    public static int parseInt(@Nullable String value, int fallback) {
        OptionalInt parsed = parseInt(value);
        return parsed.isPresent() ? parsed.getAsInt() : fallback;
    }

    /** Parses a page number (reference {@code parsePage}): blank / malformed → 1, else {@code max(1, n)}. */
    public static int parsePage(@Nullable String value) {
        OptionalInt parsed = parseInt(value);
        return parsed.isPresent() ? Math.max(1, parsed.getAsInt()) : 1;
    }

    /** Parses a double; absent on {@code null}, blank, or malformed input. */
    public static OptionalDouble parseDouble(@Nullable String value) {
        if (value == null) {
            return OptionalDouble.empty();
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(trimmed));
        } catch (NumberFormatException malformed) {
            return OptionalDouble.empty();
        }
    }

    /**
     * Parses a money amount with an optional magnitude suffix ({@code k/m/b/t}) via the kernel
     * {@link MoneyMath#parse}. Returns {@link MoneyMath#INVALID} ({@code NaN}) on failure; test with
     * {@link #isValidAmount}. Negatives parse fine — positivity is enforced by callers.
     */
    public static double parseMoney(@Nullable String value) {
        return MoneyMath.parse(value);
    }

    /** {@code true} when {@code amount} is not the {@link MoneyMath#INVALID} sentinel. */
    public static boolean isValidAmount(double amount) {
        return !MoneyMath.isInvalid(amount);
    }

    // ── flags / enums ──────────────────────────────────────────────────────────────────────

    /** {@code TRUE} for {@code "on"}, {@code FALSE} for {@code "off"} (case-insensitive), else {@code null}. */
    public static @Nullable Boolean parseOnOff(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("on")) {
            return Boolean.TRUE;
        }
        if (normalized.equals("off")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** The {@link Relation} named by {@code value} (case-insensitive), or {@code null} when unknown. */
    public static @Nullable Relation parseRelation(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        for (Relation relation : Relation.values()) {
            if (relation.name().equals(normalized)) {
                return relation;
            }
        }
        return null;
    }

    // ── snapshot / player resolution ───────────────────────────────────────────────────────

    /** The faction whose (folded) name equals {@code name}, or {@code null}. Case-insensitive. */
    public static @Nullable Faction factionByName(KernelSnapshot snap, @Nullable String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : snap.factionByName(NameIndex.fold(trimmed));
    }

    /** The online player whose name equals {@code name} exactly (case-insensitive), or {@code null}. */
    public static @Nullable Player onlineExact(@Nullable String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String target = name.trim();
        for (Player player : Players.online()) {
            if (player.getName().equalsIgnoreCase(target)) {
                return player;
            }
        }
        return null;
    }

    /** The first online player whose name starts with {@code name} (case-insensitive), or {@code null}. */
    public static @Nullable Player onlinePrefix(@Nullable String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String target = name.trim();
        for (Player player : Players.online()) {
            String playerName = player.getName();
            if (playerName.regionMatches(true, 0, target, 0, target.length())) {
                return player;
            }
        }
        return null;
    }

    /**
     * The UUID of the offline (or online) player named {@code name} via {@code Bukkit.getOfflinePlayer}
     * — always non-null for a syntactically valid name (a typo yields a bogus UUID whose kernel lookup
     * simply fails, matching the reference {@code promote}/{@code demote}/{@code leader} behavior).
     * Returns {@code null} only for a blank name.
     */
    @SuppressWarnings("deprecation")
    public static @Nullable UUID offlineId(@Nullable String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name.trim());
        return offline.getUniqueId();
    }

    // ── long options ───────────────────────────────────────────────────────────────────────

    /**
     * Parses {@code --key=value} and {@code --key value} long options out of {@code args}, returning
     * the positional tokens plus the option map (ref-commands-core.md §4.6). {@code valuedOptions}
     * are the known option names; when non-empty, an unknown option or a valued option missing its
     * value yields an {@linkplain ParsedOptions#error error} result naming the offending option.
     * Every value is trimmed; option keys are lower-cased.
     */
    public static ParsedOptions parseOptions(List<String> args, String... valuedOptions) {
        Set<String> valued = new HashSet<>();
        for (String option : valuedOptions) {
            valued.add(option.toLowerCase(Locale.ROOT));
        }
        List<String> positionals = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            if (!token.startsWith("--")) {
                positionals.add(token);
                continue;
            }
            String body = token.substring(2);
            int eq = body.indexOf('=');
            String key;
            String value;
            if (eq >= 0) {
                key = body.substring(0, eq).toLowerCase(Locale.ROOT);
                value = body.substring(eq + 1).trim();
            } else {
                key = body.toLowerCase(Locale.ROOT);
                if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
                    value = args.get(++i).trim();
                } else {
                    value = "";
                }
            }
            if (key.isEmpty()) {
                return ParsedOptions.error(token);
            }
            if (!valued.isEmpty() && !valued.contains(key)) {
                return ParsedOptions.error("--" + key);
            }
            if (value.isEmpty()) {
                return ParsedOptions.error("--" + key);
            }
            options.put(key, value);
        }
        return ParsedOptions.ok(positionals, options);
    }

    /**
     * The result of {@link #parseOptions}: the positional tokens, the parsed option map, and — on
     * failure — {@link #ok()} {@code false} with {@link #badOption()} naming the offending option.
     */
    public static final class ParsedOptions {

        private final boolean ok;
        private final String badOption;
        private final List<String> positionals;
        private final Map<String, String> options;

        private ParsedOptions(boolean ok, String badOption, List<String> positionals,
                              Map<String, String> options) {
            this.ok = ok;
            this.badOption = badOption;
            this.positionals = positionals;
            this.options = options;
        }

        static ParsedOptions ok(List<String> positionals, Map<String, String> options) {
            return new ParsedOptions(true, null, List.copyOf(positionals), Map.copyOf(options));
        }

        static ParsedOptions error(String badOption) {
            return new ParsedOptions(false, badOption, List.of(), Map.of());
        }

        /** {@code true} when parsing succeeded. */
        public boolean ok() {
            return ok;
        }

        /** The offending option token on failure, else {@code null}. */
        public @Nullable String badOption() {
            return badOption;
        }

        /** The positional tokens (option tokens removed). */
        public List<String> positionals() {
            return positionals;
        }

        /** The value of option {@code name} (lower-cased lookup), or {@code null} when unset. */
        public @Nullable String option(String name) {
            return options.get(name.toLowerCase(Locale.ROOT));
        }

        /** The full option map (keys lower-cased). */
        public Map<String, String> options() {
            return options;
        }
    }
}
