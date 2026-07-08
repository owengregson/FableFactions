package dev.fablemc.factions.core.command.travel;

import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * {@code /f warp set <name> [x] [y] [z] [world]} — creates or overwrites a warp at the caller's
 * location, or at explicit coordinates (ref-commands-misc.md §5.3). Officer-or-above. Submits
 * {@link TravelIntent.SetWarp}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread.
 * <b>Mutability:</b> holds the injected {@link Worlds} registry; stateless per invocation.
 */
final class CmdWarpSet extends CommandNode {

    private static final int COORD_ARG_COUNT = 4;
    private static final MessageKey SET = MessageKey.of("warp.set");

    private final Worlds worlds;

    CmdWarpSet(Worlds worlds) {
        super("set");
        setPermission("factions.cmd.setwarp");
        setRequiresPlayer(true);
        setDescription("Set a faction warp");
        setRequiredArgs("name");
        setOptionalArgs("x", "y", "z", "world");
        this.worlds = worlds;
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireOfficerOrAbove(snap, actor))) {
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        Location target = resolveLocation(ctx);
        int worldIdx = worlds.register(target.getWorld().getUID());
        String name = ctx.arg(0).toLowerCase(Locale.ROOT);
        CommandFlow.submit(ctx, actor,
                new TravelIntent.SetWarp(CommandFlow.handleOf(snap, faction), name, worldIdx,
                        target.getX(), target.getY(), target.getZ(), target.getYaw(), target.getPitch(), actor),
                SET, name);
    }

    private static Location resolveLocation(CommandContext ctx) {
        Location self = ctx.player().getLocation();
        if (ctx.argCount() < COORD_ARG_COUNT) {
            return self;
        }
        double x = ArgParsers.parseDouble(ctx.arg(1)).orElse(self.getX());
        double y = ArgParsers.parseDouble(ctx.arg(2)).orElse(self.getY());
        double z = ArgParsers.parseDouble(ctx.arg(3)).orElse(self.getZ());
        World world = self.getWorld();
        String worldName = ctx.argOrEmpty(COORD_ARG_COUNT);
        if (!worldName.isBlank()) {
            World named = Bukkit.getWorld(worldName);
            if (named != null) {
                world = named;
            }
        }
        return new Location(world, x, y, z, self.getYaw(), self.getPitch());
    }
}
