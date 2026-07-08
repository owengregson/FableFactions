package dev.fablemc.factions.compat.modern;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.Collection;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Optional Brigadier command-tree installer (CONTRACTS §3, proposal-C §7.6). On 1.20.6+ this
 * registers a Brigadier literal through Paper's command lifecycle so tab-completion is
 * richer than the plugin.yml executor path — which remains the source of truth (the literal
 * here just declares the root so the client-side suggestion tree exists).
 *
 * <p>Compiled against paper-api 1.20.6 and loaded by FQN string only when the
 * {@code brigadier} capability probes true; on older servers it is never touched.
 *
 * <p>Owning thread(s): the plugin main/boot thread. Mutability class: stateless.
 */
public final class BrigadierInstaller {

    public BrigadierInstaller() {}

    /** Whether the Paper Brigadier command API is present on this server. */
    public static boolean supported() {
        try {
            Class.forName("io.papermc.paper.command.brigadier.Commands");
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Registers a Brigadier literal for {@code rootName} (with {@code aliases}) via the
     * command lifecycle. The literal has no executor — the plugin.yml command remains the
     * source of truth; this exists only so modern clients get the suggestion tree.
     */
    public void install(@NotNull Plugin plugin, @NotNull String rootName, @NotNull Collection<String> aliases) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            LiteralCommandNode<CommandSourceStack> node = Commands.literal(rootName).build();
            commands.register(node, "FableFactions root command", aliases);
        });
    }
}
