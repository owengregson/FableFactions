package dev.fablemc.factions.core.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.core.command.admin.AdminQueries;
import dev.fablemc.factions.core.command.admin.CmdAdminAudit;
import dev.fablemc.factions.core.command.admin.CmdAdminBypass;
import dev.fablemc.factions.core.command.admin.CmdAdminClaim;
import dev.fablemc.factions.core.command.admin.CmdAdminDisband;
import dev.fablemc.factions.core.command.admin.CmdAdminFlag;
import dev.fablemc.factions.core.command.admin.CmdAdminHelp;
import dev.fablemc.factions.core.command.admin.CmdAdminPower;
import dev.fablemc.factions.core.command.admin.CmdAdminReload;
import dev.fablemc.factions.core.command.admin.CmdAdminShield;
import dev.fablemc.factions.core.command.admin.CmdAdminUnclaim;
import dev.fablemc.factions.core.command.admin.CmdAdminZone;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * The {@code /fa} (factionadmin) Bukkit executor and tab-completer (CONTRACTS §4,
 * ref-commands-admin.md §0). Owns the admin command registry — bypass, claim, unclaim, safezone,
 * warzone, disband, reload, shield, flag, audit, power, help — and dispatches an incoming
 * {@code /fa <sub> …} to the matching {@link CommandNode} with a snapshot taken once at dispatch.
 * Each subcommand carries its own {@code factions.cmd.*}/{@code factions.admin} permission (checked
 * inside {@link CommandNode#execute}); the bare {@code /fa} and {@code /fa help} paths are gated on
 * {@code factions.admin} by the help node itself.
 *
 * <p><b>Owning thread(s):</b> {@link #onCommand}/{@link #onTabComplete} on the sender's region/main
 * thread (the Bukkit command thread). <b>Mutability:</b> the registry is built once at construction
 * and immutable thereafter; per-invocation state lives on the {@link CommandContext}.
 */
public final class FaCommandExecutor implements CommandExecutor, TabCompleter {

    private final CommandContext.Services services;
    private final SnapshotHub snapshots;
    private final Map<String, CommandNode> byName = new LinkedHashMap<>();
    private final List<CommandNode> roots = new ArrayList<>();
    private final CmdAdminHelp help;

    /**
     * Builds the {@code /fa} tree. {@code worlds} resolves the sender's world index for the bulk
     * claim/zone commands; {@code queries} backs the paging audit / power-history reads.
     */
    public FaCommandExecutor(CommandContext.Services services, SnapshotHub snapshots,
                             Worlds worlds, AdminQueries queries) {
        this.services = Objects.requireNonNull(services, "services");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(worlds, "worlds");
        Objects.requireNonNull(queries, "queries");
        register(new CmdAdminBypass());
        register(new CmdAdminClaim(worlds));
        register(new CmdAdminUnclaim(worlds));
        register(new CmdAdminZone("safezone", "factions.cmd.safezone", FactionHandle.SAFEZONE_ORDINAL, worlds));
        register(new CmdAdminZone("warzone", "factions.cmd.warzone", FactionHandle.WARZONE_ORDINAL, worlds));
        register(new CmdAdminDisband());
        register(new CmdAdminReload());
        register(new CmdAdminShield());
        register(new CmdAdminFlag());
        register(new CmdAdminAudit(queries));
        register(new CmdAdminPower(queries));
        this.help = new CmdAdminHelp(roots);
        register(help);
    }

    private void register(CommandNode node) {
        roots.add(node);
        byName.put(node.name(), node);
        for (String alias : node.aliases()) {
            byName.put(alias, node);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            help.execute(context(sender, List.of()));
            return true;
        }
        CommandNode node = byName.get(args[0].toLowerCase(Locale.ROOT));
        if (node == null) {
            help.execute(context(sender, List.of()));
            return true;
        }
        node.execute(context(sender, tail(args)));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (CommandNode root : roots) {
                if ((root.permission() == null || sender.hasPermission(root.permission()))
                        && root.name().regionMatches(true, 0, prefix, 0, prefix.length())) {
                    out.add(root.name());
                }
            }
            return out;
        }
        CommandNode node = byName.get(args[0].toLowerCase(Locale.ROOT));
        if (node == null) {
            return Collections.emptyList();
        }
        return node.tabComplete(context(sender, tail(args)));
    }

    private CommandContext context(CommandSender sender, List<String> args) {
        Player player = sender instanceof Player casted ? casted : null;
        KernelSnapshot snapshot = snapshots.current();
        return new CommandContext(services, sender, player, args, snapshot);
    }

    private static List<String> tail(String[] args) {
        List<String> all = List.of(args);
        return all.subList(1, all.size());
    }
}
