package dev.fablemc.factions.core.command.claim;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * {@code /f map [on|off|once] [--size=<n>]} — a pure snapshot chat render of the claims around the
 * player (ref-commands-core.md §7.28), or a toggle of the territory-title auto-display. Zero DB: the
 * grid reads {@link KernelSnapshot#claimOwnerAt} only. Per-cell colour/click (which needs the rich
 * text seam unavailable to the command layer) is rendered as distinct glyphs instead.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread.
 * <b>Mutability:</b> holds the injected {@link Worlds} registry; stateless per invocation.
 */
final class CmdMap extends CommandNode {

    private static final String[] MODES = {"on", "off", "once"};
    private static final char GLYPH_CURRENT = '+';
    private static final char GLYPH_WILDERNESS = '-';
    private static final char GLYPH_SAFEZONE = 's';
    private static final char GLYPH_WARZONE = 'w';
    private static final char GLYPH_OWN = '#';
    private static final char GLYPH_OTHER = '=';

    private static final MessageKey HEADER = MessageKey.of("map.header");
    private static final MessageKey ROW = MessageKey.of("map.row");
    private static final MessageKey LEGEND = MessageKey.of("map.legend");
    private static final MessageKey ENABLED = MessageKey.of("map.enabled");
    private static final MessageKey DISABLED = MessageKey.of("map.disabled");

    private final Worlds worlds;

    CmdMap(Worlds worlds) {
        super("map");
        setPermission("factions.cmd.map");
        setRequiresPlayer(true);
        setDescription("Show a map of nearby claims");
        setOptionalArgs("mode");
        this.worlds = worlds;
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        String mode = ctx.argOrEmpty(0).toLowerCase(Locale.ROOT);
        if (mode.equals("on")) {
            CommandFlow.submit(ctx, actor, new PrefIntent.SetTerritoryTitles(actor, true), ENABLED);
            return;
        }
        if (mode.equals("off")) {
            CommandFlow.submit(ctx, actor, new PrefIntent.SetTerritoryTitles(actor, false), DISABLED);
            return;
        }
        renderOnce(ctx, actor);
    }

    private void renderOnce(CommandContext ctx, UUID actor) {
        KernelSnapshot snap = ctx.snap();
        Player player = ctx.player();
        Location loc = player.getLocation();
        int worldIdx = worlds.register(player.getWorld().getUID());
        int centerX = loc.getBlockX() >> 4;
        int centerZ = loc.getBlockZ() >> 4;
        int radius = ShapeCollectors.clampRadius(size(ctx, Math.max(1, snap.config().display().mapOnceRadius())));

        Faction own = CommandGuards.factionOf(snap, actor);
        int ownOrdinal = own == null ? -1 : own.idx();

        ctx.send(HEADER, player.getWorld().getName(), Integer.toString(centerX), Integer.toString(centerZ));
        StringBuilder row = new StringBuilder(2 * radius + 1);
        for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            row.setLength(0);
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                row.append(glyph(snap, worldIdx, x, z, centerX, centerZ, ownOrdinal));
            }
            ctx.send(ROW, row.toString());
        }
        ctx.send(LEGEND);
    }

    private static char glyph(KernelSnapshot snap, int worldIdx, int x, int z, int centerX, int centerZ,
                              int ownOrdinal) {
        if (x == centerX && z == centerZ) {
            return GLYPH_CURRENT;
        }
        int owner = snap.claimOwnerAt(worldIdx, ChunkKeys.key(x, z));
        if (owner == FactionHandle.WILDERNESS) {
            return GLYPH_WILDERNESS;
        }
        int ordinal = FactionHandle.ordinal(owner);
        if (ordinal == FactionHandle.SAFEZONE_ORDINAL) {
            return GLYPH_SAFEZONE;
        }
        if (ordinal == FactionHandle.WARZONE_ORDINAL) {
            return GLYPH_WARZONE;
        }
        return ordinal == ownOrdinal ? GLYPH_OWN : GLYPH_OTHER;
    }

    private static int size(CommandContext ctx, int fallback) {
        String size = ctx.options().get("size");
        return size == null ? fallback : ArgParsers.parseInt(size, fallback);
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0
                ? Completions.matching(MODES, ctx.argOrEmpty(0).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
