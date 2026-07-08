package dev.fablemc.factions.core.command.admin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Location;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.intent.ClaimIntent;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * {@code /fa safezone|warzone [one|square|circle|remove] [radius]} — assigns or removes system-zone
 * chunks around the sender (ref-commands-admin.md §2.4–§2.5). One class parameterized by the zone's
 * sentinel ordinal (SAFEZONE=0 / WARZONE=1): {@code remove} submits a
 * {@link ClaimIntent.RemoveZoneChunk} for the current chunk; the shaped modes collect keys and submit
 * a paged {@link ClaimIntent.SetZoneChunks} (which overwrites any existing claim, per spec).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread. <b>Mutability:</b>
 * a leaf node holding the injected {@link Worlds} registry plus its fixed zone identity.
 */
public final class CmdAdminZone extends CommandNode {

    private static final String[] MODES = {"one", "square", "circle", "remove"};

    private final Worlds worlds;
    private final int zoneOrdinal;

    /** Builds a zone command for {@code zoneOrdinal} (SAFEZONE / WARZONE) under {@code permission}. */
    public CmdAdminZone(String name, String permission, int zoneOrdinal, Worlds worlds) {
        super(name);
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.zoneOrdinal = zoneOrdinal;
        setCommandPath("/fa");
        setPermission(permission);
        setRequiresPlayer(true);
        setDescription("Assign or remove " + name + " chunks.");
        setOptionalArgs("mode", "radius");
    }

    @Override
    protected void perform(CommandContext ctx) {
        KernelSnapshot snap = ctx.snap();
        UUID actor = ctx.player().getUniqueId();
        Location loc = ctx.player().getLocation();
        int worldIdx = worlds.index(loc.getWorld());
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        String mode = ctx.argOrEmpty(0).trim().toLowerCase(Locale.ROOT);
        if (mode.equals("remove")) {
            CommandKit.submit(ctx, new ClaimIntent.RemoveZoneChunk(zoneOrdinal, worldIdx,
                    ChunkKeys.key(chunkX, chunkZ), actor), CommandKit.adminOrigin(ctx.sender()));
            return;
        }
        int max = Math.max(1, snap.config().land().maxPerCommand());
        int radius = AdminChunks.parseRadius(ctx.arg(1));
        long[] keys = AdminChunks.collect(mode, chunkX, chunkZ, radius, max);
        CommandKit.submit(ctx, new ClaimIntent.SetZoneChunks(zoneOrdinal, worldIdx, keys, actor),
                CommandKit.adminOrigin(ctx.sender()));
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0 ? Completions.matching(MODES, ctx.argOrEmpty(0)) : List.of();
    }
}
