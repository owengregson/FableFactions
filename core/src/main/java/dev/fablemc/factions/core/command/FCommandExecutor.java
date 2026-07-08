package dev.fablemc.factions.core.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import dev.fablemc.factions.core.command.bank.BankCommands;
import dev.fablemc.factions.core.command.claim.ClaimCommands;
import dev.fablemc.factions.core.command.member.MemberCommands;
import dev.fablemc.factions.core.command.power.PowerCommands;
import dev.fablemc.factions.core.command.travel.TravelCommands;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * The {@code /f} root executor and tab-completer (ref-commands-core.md §2/§3): it owns the
 * insertion-ordered subcommand registry, dispatches {@code args[0]} to the matching {@link
 * CommandNode} (taking ONE {@link KernelSnapshot} per invocation), opens the default GUI on bare
 * {@code /f}, and routes an unknown token to the rich help hint. Registered are the member, claim,
 * travel, bank and power trees this work order owns, plus any {@code additionalNodes} the integrator
 * supplies for the relation / role / flag / chest / merge / predefined trees.
 *
 * <p><b>Owning thread(s):</b> {@link #onCommand}/{@link #onTabComplete} run on the sender's
 * region/main thread (whatever thread Bukkit fires the command on). A {@code perform} body reads the
 * snapshot, submits intents and sends messages only (CONTRACTS §4). <b>Mutability:</b> the registry
 * is built once at construction and read-only thereafter; per-invocation state lives on the
 * {@link CommandContext}.
 */
public final class FCommandExecutor implements CommandExecutor, TabCompleter {

    private static final MessageKey UNKNOWN = MessageKey.of("general.unknown-subcommand-detailed");
    private static final String HELP_NAME = "help";

    private final SnapshotHub snapshots;
    private final CommandContext.Services services;
    private final Map<String, CommandNode> registry = new LinkedHashMap<>();
    private final List<CommandNode> ordered = new ArrayList<>();

    /**
     * Builds the executor. {@code worlds} feeds the claim/travel trees (world→{@code worldIdx});
     * {@code vault} feeds the bank/power money flows; {@code additionalNodes} are the trees owned by
     * the other command work orders (registered in the same registry so help / completion see them).
     */
    public FCommandExecutor(SnapshotHub snapshots, CommandContext.Services services, Worlds worlds,
                            VaultBridge vault, List<CommandNode> additionalNodes) {
        this.snapshots = snapshots;
        this.services = services;
        registerAll(MemberCommands.create());
        registerAll(ClaimCommands.create(worlds));
        registerAll(TravelCommands.create(worlds));
        registerAll(BankCommands.create(vault));
        registerAll(PowerCommands.create(vault));
        if (additionalNodes != null) {
            registerAll(additionalNodes);
        }
        register(MemberCommands.createHelp(registry));
    }

    private void registerAll(List<CommandNode> nodes) {
        for (CommandNode node : nodes) {
            register(node);
        }
    }

    private void register(CommandNode node) {
        ordered.add(node);
        registry.put(node.name(), node);
        for (String alias : node.aliases()) {
            registry.put(alias, node);
        }
    }

    // ── executor ─────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        KernelSnapshot snapshot = snapshots.current();
        if (args.length == 0) {
            if (sender instanceof Player player && services.menus() != null
                    && services.menus().openDefault(player)) {
                return true;
            }
            dispatch(HELP_NAME, sender, args, snapshot);
            return true;
        }
        CommandNode node = registry.get(args[0].toLowerCase(Locale.ROOT));
        if (node == null) {
            services.messages().to(sender, UNKNOWN, args[0]);
            return true;
        }
        node.execute(context(sender, tail(args), snapshot));
        return true;
    }

    private void dispatch(String name, CommandSender sender, String[] args, KernelSnapshot snapshot) {
        CommandNode node = registry.get(name);
        if (node != null) {
            node.execute(context(sender, tail(args), snapshot));
        }
    }

    // ── tab completer ────────────────────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return completionNames(sender, args[0].toLowerCase(Locale.ROOT));
        }
        CommandNode node = registry.get(args[0].toLowerCase(Locale.ROOT));
        if (node == null) {
            return List.of();
        }
        return node.tabComplete(context(sender, tail(args), snapshots.current()));
    }

    private List<String> completionNames(CommandSender sender, String partial) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, CommandNode> entry : registry.entrySet()) {
            CommandNode node = entry.getValue();
            if (node.permission() != null && !sender.hasPermission(node.permission())) {
                continue;
            }
            String key = entry.getKey();
            if (key.regionMatches(true, 0, partial, 0, partial.length())) {
                out.add(key);
            }
        }
        return out;
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private CommandContext context(CommandSender sender, List<String> args, KernelSnapshot snapshot) {
        Player player = sender instanceof Player casted ? casted : null;
        return new CommandContext(services, sender, player, args, snapshot);
    }

    private static List<String> tail(String[] args) {
        if (args.length <= 1) {
            return List.of();
        }
        List<String> out = new ArrayList<>(args.length - 1);
        for (int i = 1; i < args.length; i++) {
            out.add(args[i]);
        }
        return out;
    }
}
