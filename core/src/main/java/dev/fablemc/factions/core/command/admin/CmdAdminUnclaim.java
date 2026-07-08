package dev.fablemc.factions.core.command.admin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Location;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.intent.ClaimIntent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * {@code /fa unclaim <faction> [all|one|square|circle|fill] [radius]} — admin bulk-unclaim of a
 * faction's land (ref-commands-admin.md §2.3). {@code all} submits a paged
 * {@link ClaimIntent.UnclaimAll}; the shaped modes collect keys around the sender and submit an
 * {@link ClaimIntent.AdminUnclaimChunks}, which the reducer only applies to chunks that faction
 * actually owns.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread. <b>Mutability:</b>
 * a leaf node holding the injected {@link Worlds} registry.
 */
public final class CmdAdminUnclaim extends CommandNode {

    private static final String[] MODES = {"all", "one", "square", "circle", "fill"};

    private final Worlds worlds;

    /** Builds the admin-unclaim command with the world-index registry. */
    public CmdAdminUnclaim(Worlds worlds) {
        super("unclaim");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        setCommandPath("/fa");
        setPermission("factions.cmd.claim.other");
        setRequiresPlayer(true);
        setDescription("Admin-unclaim a faction's land.");
        setRequiredArgs("faction");
        setOptionalArgs("mode", "radius");
    }

    @Override
    protected void perform(CommandContext ctx) {
        KernelSnapshot snap = ctx.snap();
        Faction target = ArgParsers.factionByName(snap, ctx.arg(0));
        if (target == null) {
            ctx.sendReason(ReasonCode.FACTION_NOT_FOUND, ctx.argOrEmpty(0));
            return;
        }
        UUID actor = ctx.player().getUniqueId();
        int handle = CommandKit.handleOf(snap, target);
        String mode = ctx.argOrEmpty(1).trim().toLowerCase(Locale.ROOT);
        if (mode.equals("all")) {
            CommandKit.submit(ctx, new ClaimIntent.UnclaimAll(actor, handle), CommandKit.adminOrigin(ctx.sender()));
            return;
        }
        int max = Math.max(1, snap.config().land().maxPerCommand());
        int radius = AdminChunks.parseRadius(ctx.arg(2));
        Location loc = ctx.player().getLocation();
        int worldIdx = worlds.index(loc.getWorld());
        long[] keys = AdminChunks.collect(mode, loc.getBlockX() >> 4, loc.getBlockZ() >> 4, radius, max);
        CommandKit.submit(ctx, new ClaimIntent.AdminUnclaimChunks(handle, worldIdx, keys, actor),
                CommandKit.adminOrigin(ctx.sender()));
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        if (argIndex == 0) {
            return Completions.factionNames(ctx.snap(), ctx.argOrEmpty(0));
        }
        return argIndex == 1 ? Completions.matching(MODES, ctx.argOrEmpty(1)) : List.of();
    }
}
