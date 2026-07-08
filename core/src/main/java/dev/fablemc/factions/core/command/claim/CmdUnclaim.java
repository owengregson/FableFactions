package dev.fablemc.factions.core.command.claim;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import dev.fablemc.factions.api.event.FactionChunkUnclaimEvent;
import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.intent.ClaimIntent;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.vocab.ClaimMode;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * {@code /f unclaim [one|auto|square|circle|fill|all]} — releases the current chunk, a shaped
 * region, or (owner-only, {@code confirm}-gated) all faction land (ref-commands-core.md §7.22). Area
 * unclaims fire a cancellable {@link FactionChunkUnclaimEvent} per chunk; {@code all} submits the
 * paged {@link ClaimIntent.UnclaimAll}.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread.
 * <b>Mutability:</b> holds the injected {@link Worlds} registry; stateless per invocation.
 */
final class CmdUnclaim extends CommandNode {

    private static final String[] MODES = {"one", "auto", "square", "circle", "fill", "all"};
    private static final String[] ON_OFF = {"on", "off"};
    private static final String CONFIRM_TOKEN = "confirm";
    private static final MessageKey UNCLAIMED = MessageKey.of("claim.unclaimed");
    private static final MessageKey UNCLAIMED_ALL = MessageKey.of("claim.unclaimed-all");
    private static final MessageKey CONFIRM_ALL = MessageKey.of("custom.claim.unclaim-all-confirm");
    private static final MessageKey AUTO_ENABLED = MessageKey.of("custom.claim.auto-unclaim-enabled");
    private static final MessageKey AUTO_DISABLED = MessageKey.of("custom.claim.auto-unclaim-disabled");

    private final Worlds worlds;

    CmdUnclaim(Worlds worlds) {
        super("unclaim");
        setPermission("factions.cmd.unclaim");
        setRequiresPlayer(true);
        setDescription("Unclaim faction land");
        setOptionalArgs("mode");
        this.worlds = worlds;
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        String token = ctx.argOrEmpty(0).toLowerCase(Locale.ROOT);
        if (token.equals("auto")) {
            handleAuto(ctx, snap, actor);
            return;
        }
        if (token.equals("all")) {
            handleAll(ctx, snap, actor);
            return;
        }
        if (CommandFlow.blocked(ctx, CommandGuards.requireFaction(snap, actor))) {
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        int handle = CommandFlow.handleOf(snap, faction);
        Player player = ctx.player();
        Location loc = player.getLocation();
        int worldIdx = worlds.register(player.getWorld().getUID());
        int centerX = loc.getBlockX() >> 4;
        int centerZ = loc.getBlockZ() >> 4;

        ClaimMode mode = modeOf(token);
        long[] keys = mode == ClaimMode.SINGLE
                ? new long[] {ChunkKeys.key(centerX, centerZ)}
                : ShapeCollectors.collect(mode, centerX, centerZ,
                        ShapeCollectors.clampRadius(ArgParsers.parseInt(ctx.arg(1), ShapeCollectors.MIN_RADIUS)),
                        Math.max(1, snap.config().land().maxPerCommand()));

        long[] survivors = fireEvents(ctx, faction, worldIdx, player.getWorld().getName(), keys, actor);
        if (survivors.length == 0) {
            return;
        }
        CommandFlow.submit(ctx, actor,
                new ClaimIntent.UnclaimChunks(actor, handle, worldIdx, survivors), UNCLAIMED, faction.name());
    }

    private void handleAuto(CommandContext ctx, KernelSnapshot snap, UUID actor) {
        if (CommandFlow.blocked(ctx, CommandGuards.requireFaction(snap, actor))) {
            return;
        }
        Boolean explicit = ArgParsers.parseOnOff(ctx.arg(1));
        boolean enable = explicit != null ? explicit : (currentAutoMode(snap, actor) != PlayerLedger.AUTO_MODE_UNCLAIM);
        int mode = enable ? PlayerLedger.AUTO_MODE_UNCLAIM : PlayerLedger.AUTO_MODE_OFF;
        CommandFlow.submit(ctx, actor, new PrefIntent.SetAutoTerritoryMode(actor, mode),
                enable ? AUTO_ENABLED : AUTO_DISABLED);
    }

    private void handleAll(CommandContext ctx, KernelSnapshot snap, UUID actor) {
        if (CommandFlow.blocked(ctx, CommandGuards.requireOwner(snap, actor))) {
            return;
        }
        if (!ctx.argOrEmpty(1).equalsIgnoreCase(CONFIRM_TOKEN)) {
            ctx.send(CONFIRM_ALL);
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        CommandFlow.submit(ctx, actor,
                new ClaimIntent.UnclaimAll(actor, CommandFlow.handleOf(snap, faction)), UNCLAIMED_ALL, faction.name());
    }

    private static int currentAutoMode(KernelSnapshot snap, UUID actor) {
        int ordinal = snap.memberOrdinal(actor);
        if (ordinal < 0) {
            return PlayerLedger.AUTO_MODE_OFF;
        }
        return PlayerLedger.autoMode(snap.state().ledger().prefsBits(ordinal));
    }

    private long[] fireEvents(CommandContext ctx, Faction faction, int worldIdx, String worldName,
                              long[] keys, UUID actor) {
        long[] survivors = new long[keys.length];
        int n = 0;
        for (long key : keys) {
            FactionChunkUnclaimEvent event = new FactionChunkUnclaimEvent(worldName,
                    ChunkKeys.x(key), ChunkKeys.z(key), faction.id(), actor);
            if (!CommandFlow.fireCancelled(event)) {
                survivors[n++] = key;
            }
        }
        return n == keys.length ? survivors : Arrays.copyOf(survivors, n);
    }

    private static ClaimMode modeOf(String token) {
        return switch (token) {
            case "square" -> ClaimMode.SQUARE;
            case "circle" -> ClaimMode.CIRCLE;
            case "fill" -> ClaimMode.FILL;
            default -> ClaimMode.SINGLE;
        };
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        String partial = ctx.argOrEmpty(argIndex).toLowerCase(Locale.ROOT);
        if (argIndex == 0) {
            return Completions.matching(MODES, partial);
        }
        if (argIndex == 1 && ctx.argOrEmpty(0).equalsIgnoreCase("auto")) {
            return Completions.matching(ON_OFF, partial);
        }
        if (argIndex == 1 && ctx.argOrEmpty(0).equalsIgnoreCase("all")) {
            return Completions.matching(new String[] {CONFIRM_TOKEN}, partial);
        }
        return List.of();
    }
}
