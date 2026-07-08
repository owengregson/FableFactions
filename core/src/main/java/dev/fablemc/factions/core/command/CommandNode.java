package dev.fablemc.factions.core.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * The abstract base of every {@code /f} and {@code /fa} subcommand (CONTRACTS §4,
 * ref-commands-core.md §4). A node carries its name, aliases, permission node, player-only flag,
 * required/optional argument placeholders and any children, and runs the fixed dispatch pipeline
 * in {@link #execute}: <b>permission → player-only → child routing → argument count →
 * {@link #perform}</b> (ref-commands-core.md §4.2). Subclasses configure themselves with the
 * chainable {@code set*}/{@code addChild} builders from their constructor and implement
 * {@link #perform} (and optionally {@link #complete} for tab-completion values).
 *
 * <p><b>Owning thread(s):</b> {@link #execute}/{@link #tabComplete} run on the sender's region/main
 * thread; a {@code perform} body does snapshot reads + intent submits + message sends only (never
 * JDBC / journal / kernel-state construction — CONTRACTS §4). <b>Mutability:</b> a node is
 * configured once at construction and is effectively immutable thereafter (shared across all
 * invocations); per-invocation state lives on the {@link CommandContext}.
 */
public abstract class CommandNode {

    private static final MessageKey MSG_NO_PERMISSION = MessageKey.of("general.no-permission");
    private static final MessageKey MSG_PLAYER_ONLY = MessageKey.of("general.player-only");
    private static final MessageKey MSG_INVALID_ARGS = MessageKey.of("general.invalid-args");

    /** The default usage prefix; children inherit {@code parentPath + " " + parentName}. */
    private static final String DEFAULT_COMMAND_PATH = "/f";

    private final String name;
    private final List<String> aliases;
    private final Map<String, CommandNode> children = new LinkedHashMap<>();
    private final List<CommandNode> childOrder = new ArrayList<>();

    private String permission;
    private String description = "";
    private boolean requiresPlayer;
    private List<String> requiredArgs = List.of();
    private List<String> optionalArgs = List.of();
    private String commandPath = DEFAULT_COMMAND_PATH;

    /** Names {@code name} (lower-cased) with optional extra registry {@code aliases}. */
    protected CommandNode(String name, String... aliases) {
        this.name = name.toLowerCase(Locale.ROOT);
        this.aliases = lowerCopy(aliases);
    }

    // ── configuration (chainable; call from the subclass constructor) ──────────────────────

    /** Sets the required permission node ({@code null} = open). */
    protected CommandNode setPermission(@Nullable String node) {
        this.permission = node;
        return this;
    }

    /** Sets the one-line help description. */
    protected CommandNode setDescription(String text) {
        this.description = text == null ? "" : text;
        return this;
    }

    /** Sets whether a console sender is rejected before {@link #perform} (player-only). */
    protected CommandNode setRequiresPlayer(boolean value) {
        this.requiresPlayer = value;
        return this;
    }

    /** Sets the required-argument placeholder names (drives the count check and usage). */
    protected CommandNode setRequiredArgs(String... names) {
        this.requiredArgs = names.length == 0 ? List.of() : List.of(names);
        return this;
    }

    /** Sets the optional-argument placeholder names (usage display only). */
    protected CommandNode setOptionalArgs(String... names) {
        this.optionalArgs = names.length == 0 ? List.of() : List.of(names);
        return this;
    }

    /** Sets the usage-string prefix (default {@code "/f"}); children override their own from this. */
    protected CommandNode setCommandPath(String path) {
        this.commandPath = path;
        return this;
    }

    /**
     * Registers {@code child} under its name and every alias, and sets the child's usage prefix to
     * this node's {@code path + " " + name}. Registration order is preserved for usage / completion.
     */
    protected void addChild(CommandNode child) {
        child.commandPath = this.commandPath + " " + this.name;
        childOrder.add(child);
        children.put(child.name, child);
        for (String alias : child.aliases) {
            children.put(alias, child);
        }
    }

    // ── accessors ──────────────────────────────────────────────────────────────────────────

    /** The primary (lower-cased) command name. */
    public String name() {
        return name;
    }

    /** The extra registry aliases (lower-cased, unmodifiable). */
    public List<String> aliases() {
        return aliases;
    }

    /** The required permission node, or {@code null} when open. */
    public @Nullable String permission() {
        return permission;
    }

    /** The one-line help description. */
    public String description() {
        return description;
    }

    /** Whether this command rejects console senders. */
    public boolean requiresPlayer() {
        return requiresPlayer;
    }

    /** The child nodes keyed by name and alias (unmodifiable). */
    public Map<String, CommandNode> children() {
        return Collections.unmodifiableMap(children);
    }

    /**
     * The usage string: {@code path name} followed by {@code <child1|child2|…>} for a group, or by
     * each {@code <required>} then {@code [optional]} placeholder for a leaf (ref-commands-core §4.4).
     */
    public String getUsage() {
        StringBuilder usage = new StringBuilder(commandPath).append(' ').append(name);
        if (!childOrder.isEmpty()) {
            usage.append(" <");
            for (int i = 0; i < childOrder.size(); i++) {
                if (i > 0) {
                    usage.append('|');
                }
                usage.append(childOrder.get(i).name);
            }
            usage.append('>');
        } else {
            for (String required : requiredArgs) {
                usage.append(" <").append(required).append('>');
            }
            for (String optional : optionalArgs) {
                usage.append(" [").append(optional).append(']');
            }
        }
        return usage.toString();
    }

    // ── dispatch ───────────────────────────────────────────────────────────────────────────

    /**
     * Runs the fixed pipeline: permission → player-only → child routing → argument count →
     * {@link #perform}. Each step short-circuits with the matching feedback message; a matched child
     * runs its OWN full pipeline (its permission is re-checked). This method is final.
     */
    public final void execute(CommandContext ctx) {
        if (permission != null && !ctx.sender().hasPermission(permission)) {
            ctx.send(MSG_NO_PERMISSION);
            return;
        }
        if (requiresPlayer && !ctx.isPlayer()) {
            ctx.send(MSG_PLAYER_ONLY);
            return;
        }
        if (!children.isEmpty() && ctx.argCount() > 0) {
            CommandNode child = children.get(ctx.arg(0).toLowerCase(Locale.ROOT));
            if (child != null) {
                child.execute(ctx.shift());
                return;
            }
        }
        if (ctx.argCount() < requiredArgs.size()) {
            ctx.send(MSG_INVALID_ARGS, getUsage());
            return;
        }
        perform(ctx);
    }

    /** The command action, run after the pipeline passes. A group with no default action prints usage. */
    protected void perform(CommandContext ctx) {
        ctx.send(MSG_INVALID_ARGS, getUsage());
    }

    /**
     * Produces tab-completions. A group merges permitted child names with {@link #complete}{@code
     * (ctx, 0)} at position 0, and routes into a matched child otherwise; a leaf delegates to
     * {@link #complete}. All results are filtered by the last token's prefix. This method is final.
     */
    public final List<String> tabComplete(CommandContext ctx) {
        String partial = ctx.argCount() == 0
                ? "" : ctx.argOrEmpty(ctx.argCount() - 1).toLowerCase(Locale.ROOT);
        if (!childOrder.isEmpty()) {
            if (ctx.argCount() <= 1) {
                LinkedHashSet<String> merged = new LinkedHashSet<>();
                for (CommandNode child : childOrder) {
                    if ((child.permission == null || ctx.sender().hasPermission(child.permission))
                            && startsWith(child.name, partial)) {
                        merged.add(child.name);
                    }
                }
                addFiltered(merged, complete(ctx, 0), partial);
                return new ArrayList<>(merged);
            }
            CommandNode child = children.get(ctx.arg(0).toLowerCase(Locale.ROOT));
            if (child != null) {
                return child.tabComplete(ctx.shift());
            }
            LinkedHashSet<String> out = new LinkedHashSet<>();
            addFiltered(out, complete(ctx, ctx.argCount() - 1), partial);
            return new ArrayList<>(out);
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        addFiltered(out, complete(ctx, Math.max(0, ctx.argCount() - 1)), partial);
        return new ArrayList<>(out);
    }

    /**
     * The dynamic-completion override point: candidate values for zero-based argument {@code
     * argIndex} (relative to this node). Default: none. Implementations use {@link Completions}.
     */
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return List.of();
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────

    private static void addFiltered(LinkedHashSet<String> out, List<String> candidates, String partial) {
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            if (startsWith(candidate, partial)) {
                out.add(candidate);
            }
        }
    }

    private static boolean startsWith(String candidate, String partial) {
        return candidate.regionMatches(true, 0, partial, 0, partial.length());
    }

    private static List<String> lowerCopy(String[] values) {
        if (values.length == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>(values.length);
        for (String value : values) {
            out.add(value.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableList(out);
    }
}
