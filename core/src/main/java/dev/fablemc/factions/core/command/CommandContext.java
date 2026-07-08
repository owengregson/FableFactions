package dev.fablemc.factions.core.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.core.chest.Chests;
import dev.fablemc.factions.core.config.Reloads;
import dev.fablemc.factions.core.gui.Menus;
import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.core.session.Travel;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.sched.Scheduling;

/**
 * The per-invocation dispatch context handed to a {@link CommandNode} (CONTRACTS §4): the sender,
 * the argument tokens, the {@link KernelSnapshot} taken ONCE at dispatch, the parsed {@code
 * --key=value} long options, and the shared {@link Services} every command needs (bus, message
 * layer, scheduler, and the travel/chest/menu/reload seams).
 *
 * <p><b>Owning thread(s):</b> constructed and used on the sender's region/main thread (the thread
 * the command event fired on); a command body reads the snapshot, submits intents and sends
 * messages here — never JDBC or kernel-state construction (CONTRACTS §4). <b>Mutability:</b>
 * immutable value; {@link #args()} is an unmodifiable copy and {@link #options()} an unmodifiable
 * map computed once at construction.
 *
 * <p>{@link #arg(int)} returns {@code null} when the index is out of range (never throws — command
 * bodies rely on this); {@link #argOrEmpty(int)} is the {@code ""}-default variant for the common
 * {@code isBlank()} guard. {@code arg(i)} and {@link #argCount()} operate on the RAW tokens (child
 * routing and the required-argument count use them), while {@link #options()} exposes the
 * {@code --key=value} tokens as a convenience map; commands needing strict positional/option
 * separation (both {@code --k=v} and {@code --k v} forms, with error reporting) use
 * {@link ArgParsers#parseOptions}.
 */
public final class CommandContext {

    private final Services services;
    private final CommandSender sender;
    private final Player player;
    private final List<String> args;
    private final Map<String, String> options;
    private final KernelSnapshot snapshot;

    /**
     * Builds a context. {@code player} is the sender cast to {@link Player} (or {@code null} for a
     * non-player sender); {@code snapshot} is taken once at dispatch and reused for the whole command.
     */
    public CommandContext(@NotNull Services services, @NotNull CommandSender sender,
                          @Nullable Player player, @NotNull List<String> args,
                          @NotNull KernelSnapshot snapshot) {
        this.services = services;
        this.sender = sender;
        this.player = player;
        this.args = List.copyOf(args);
        this.options = parseOptions(this.args);
        this.snapshot = snapshot;
    }

    // ── pinned surface (CONTRACTS §4) ──────────────────────────────────────────────────────

    /** The {@code i}-th raw argument token, or {@code null} when out of range (never throws). */
    public @Nullable String arg(int i) {
        return (i >= 0 && i < args.size()) ? args.get(i) : null;
    }

    /** The parsed {@code --key=value} long options (keys lower-cased); empty when none. */
    public @NotNull Map<String, String> options() {
        return options;
    }

    /** The command sender. */
    public @NotNull CommandSender sender() {
        return sender;
    }

    /** The sender as a {@link Player}, or {@code null} when the sender is not a player. */
    public @Nullable Player player() {
        return player;
    }

    /** The snapshot taken once at dispatch — the whole command reads this one consistent state. */
    public @NotNull KernelSnapshot snap() {
        return snapshot;
    }

    // ── framework extras ───────────────────────────────────────────────────────────────────

    /** The shared command services (message layer, intent bus, scheduler, seams). */
    public @NotNull Services services() {
        return services;
    }

    /** The raw argument tokens (unmodifiable). */
    public @NotNull List<String> args() {
        return args;
    }

    /** The number of raw argument tokens (drives child routing and the required-arg count check). */
    public int argCount() {
        return args.size();
    }

    /** {@code true} when the sender is a player. */
    public boolean isPlayer() {
        return player != null;
    }

    /** The {@code i}-th argument, or {@code ""} when out of range (the {@code isBlank()}-guard form). */
    public @NotNull String argOrEmpty(int i) {
        String value = arg(i);
        return value == null ? "" : value;
    }

    /** A new context over {@code args[1..]} (empty when already empty) — used to route into a child. */
    public @NotNull CommandContext shift() {
        List<String> rest = args.isEmpty() ? List.of() : args.subList(1, args.size());
        return new CommandContext(services, sender, player, rest, snapshot);
    }

    /** Renders {@code key} in the sender's locale and delivers it to the sender. */
    public void send(@NotNull MessageKey key, String... args) {
        services.messages().to(sender, key, args);
    }

    /** Sends the message a {@link ReasonCode} maps to (CONTRACTS §6.4: never invent text). */
    public void sendReason(@NotNull ReasonCode reason, String... args) {
        send(reason.messageKey(), args);
    }

    // ── option parsing ─────────────────────────────────────────────────────────────────────

    private static Map<String, String> parseOptions(List<String> args) {
        Map<String, String> parsed = null;
        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            if (token.length() > 2 && token.charAt(0) == '-' && token.charAt(1) == '-') {
                int eq = token.indexOf('=', 2);
                if (eq > 2) {
                    if (parsed == null) {
                        parsed = new LinkedHashMap<>(4);
                    }
                    parsed.put(token.substring(2, eq).toLowerCase(Locale.ROOT), token.substring(eq + 1));
                }
            }
        }
        return parsed == null ? Map.of() : Collections.unmodifiableMap(parsed);
    }

    /**
     * The shared collaborators every command needs, injected once at boot and carried on every
     * context. Fields may be {@code null} in narrow unit tests that exercise only the dispatch
     * pipeline; production wiring supplies them all.
     *
     * @param plugin     the owning plugin (for {@code getLogger()})
     * @param bus        the intent bus (the only mutation path from a command body)
     * @param messages   the message layer
     * @param scheduling the platform scheduler
     * @param travel     the teleport seam ({@code /f home}, {@code /f warp})
     * @param chests     the team-chest seam ({@code /f chest})
     * @param menus      the GUI seam ({@code /f gui}, {@code /f language})
     * @param reloads    the config-reload seam ({@code /fa reload})
     */
    public record Services(Plugin plugin, IntentBus bus, Messages messages, Scheduling scheduling,
                           Travel travel, Chests chests, Menus menus, Reloads reloads) {
    }
}
