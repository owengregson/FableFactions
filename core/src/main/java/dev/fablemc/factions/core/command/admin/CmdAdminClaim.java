package dev.fablemc.factions.core.command.admin;

import java.util.List;
import java.util.Objects;

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
 * {@code /fa claim <faction> [one|square|circle|fill] [radius]} — admin bulk-claim of unclaimed
 * chunks for another faction (ref-commands-admin.md §2.2). Collects the target chunk keys around the
 * sender via {@link AdminChunks} (capped at {@code land.max-per-command}) and submits an
 * {@link ClaimIntent.AdminClaimChunks}; the reducer skips already-owned chunks (fill-only), so this
 * never overwrites another faction's land.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread. <b>Mutability:</b>
 * a leaf node holding the injected {@link Worlds} registry.
 */
public final class CmdAdminClaim extends CommandNode {

    private static final String[] MODES = {"one", "square", "circle", "fill"};

    private final Worlds worlds;

    /** Builds the admin-claim command with the world-index registry. */
    public CmdAdminClaim(Worlds worlds) {
        super("claim");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        setCommandPath("/fa");
        setPermission("factions.cmd.claim.other");
        setRequiresPlayer(true);
        setDescription("Admin-claim unclaimed chunks for a faction.");
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
        int max = Math.max(1, snap.config().land().maxPerCommand());
        int radius = AdminChunks.parseRadius(ctx.arg(2));
        Location loc = ctx.player().getLocation();
        int worldIdx = worlds.index(loc.getWorld());
        long[] keys = AdminChunks.collect(ctx.arg(1), loc.getBlockX() >> 4, loc.getBlockZ() >> 4, radius, max);
        CommandKit.submit(ctx, new ClaimIntent.AdminClaimChunks(CommandKit.handleOf(snap, target),
                worldIdx, keys, ctx.player().getUniqueId()), CommandKit.adminOrigin(ctx.sender()));
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        if (argIndex == 0) {
            return Completions.factionNames(ctx.snap(), ctx.argOrEmpty(0));
        }
        return argIndex == 1 ? Completions.matching(MODES, ctx.argOrEmpty(1)) : List.of();
    }
}
