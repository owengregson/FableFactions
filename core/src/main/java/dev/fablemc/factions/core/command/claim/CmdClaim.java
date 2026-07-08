package dev.fablemc.factions.core.command.claim;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import dev.fablemc.factions.api.event.FactionChunkClaimEvent;
import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.ClaimIntent;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.vocab.ClaimMode;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * {@code /f claim [one|auto|square|circle|fill|nearby|at]} — claims the current chunk, a shaped
 * region, a named chunk, or toggles auto-claim (ref-commands-core.md §7.21). Fires a cancellable
 * {@link FactionChunkClaimEvent} per candidate chunk, drops the vetoed ones, then submits one
 * {@link ClaimIntent.ClaimChunks} (the reducer validates each chunk against {@code ClaimRules} and
 * is the authority).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread.
 * <b>Mutability:</b> holds the injected {@link Worlds} registry; stateless per invocation.
 */
final class CmdClaim extends CommandNode {

    private static final String[] MODES = {"one", "auto", "square", "circle", "fill", "nearby", "at"};
    private static final String[] ON_OFF = {"on", "off"};
    private static final MessageKey CLAIMED = MessageKey.of("claim.claimed");
    private static final MessageKey AUTO_ENABLED = MessageKey.of("custom.claim.auto-enabled");
    private static final MessageKey AUTO_DISABLED = MessageKey.of("custom.claim.auto-disabled");
    private static final MessageKey USAGE = MessageKey.of("general.invalid-args");

    private final Worlds worlds;

    CmdClaim(Worlds worlds) {
        super("claim");
        setPermission("factions.cmd.claim");
        setRequiresPlayer(true);
        setDescription("Claim land for your faction");
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
        long[] keys;
        if (mode == ClaimMode.AT) {
            keys = atKeys(ctx);
            if (keys == null) {
                return;
            }
        } else if (mode == ClaimMode.SINGLE) {
            keys = new long[] {ChunkKeys.key(centerX, centerZ)};
        } else {
            int radius = ShapeCollectors.clampRadius(ArgParsers.parseInt(ctx.arg(1), ShapeCollectors.MIN_RADIUS));
            int max = Math.max(1, snap.config().land().maxPerCommand());
            keys = ShapeCollectors.collect(mode, centerX, centerZ, radius, max);
        }

        long[] survivors = fireEvents(ctx, snap, faction, worldIdx, player.getWorld().getName(), keys, actor);
        if (survivors.length == 0) {
            return;
        }
        CommandFlow.submit(ctx, actor,
                new ClaimIntent.ClaimChunks(actor, handle, worldIdx, survivors, mode), CLAIMED, faction.name());
    }

    private void handleAuto(CommandContext ctx, KernelSnapshot snap, UUID actor) {
        if (CommandFlow.blocked(ctx, CommandGuards.requireFaction(snap, actor))) {
            return;
        }
        Boolean explicit = ArgParsers.parseOnOff(ctx.arg(1));
        boolean enable = explicit != null ? explicit : (currentAutoMode(snap, actor) != PlayerLedger.AUTO_MODE_CLAIM);
        int mode = enable ? PlayerLedger.AUTO_MODE_CLAIM : PlayerLedger.AUTO_MODE_OFF;
        CommandFlow.submit(ctx, actor, new PrefIntent.SetAutoTerritoryMode(actor, mode),
                enable ? AUTO_ENABLED : AUTO_DISABLED);
    }

    private static int currentAutoMode(KernelSnapshot snap, UUID actor) {
        int ordinal = snap.memberOrdinal(actor);
        if (ordinal < 0) {
            return PlayerLedger.AUTO_MODE_OFF;
        }
        return PlayerLedger.autoMode(snap.state().ledger().prefsBits(ordinal));
    }

    private long[] atKeys(CommandContext ctx) {
        OptionalInt x = ArgParsers.parseInt(ctx.arg(1));
        OptionalInt z = ArgParsers.parseInt(ctx.arg(2));
        if (x.isEmpty() || z.isEmpty()) {
            ctx.send(USAGE, getUsage());
            return null;
        }
        return new long[] {ChunkKeys.key(x.getAsInt(), z.getAsInt())};
    }

    private long[] fireEvents(CommandContext ctx, KernelSnapshot snap, Faction faction, int worldIdx,
                              String worldName, long[] keys, UUID actor) {
        long[] survivors = new long[keys.length];
        int n = 0;
        for (long key : keys) {
            int chunkX = ChunkKeys.x(key);
            int chunkZ = ChunkKeys.z(key);
            UUID overclaimedFrom = victimId(snap, worldIdx, key, faction.idx());
            FactionChunkClaimEvent event = new FactionChunkClaimEvent(worldName, chunkX, chunkZ,
                    faction.id(), overclaimedFrom, actor);
            if (!CommandFlow.fireCancelled(event)) {
                survivors[n++] = key;
            }
        }
        return n == keys.length ? survivors : Arrays.copyOf(survivors, n);
    }

    private static UUID victimId(KernelSnapshot snap, int worldIdx, long key, int ownFactionOrdinal) {
        int owner = snap.claimOwnerAt(worldIdx, key);
        if (owner == FactionHandle.WILDERNESS) {
            return null;
        }
        int ownerOrdinal = FactionHandle.ordinal(owner);
        if (ownerOrdinal == ownFactionOrdinal || !FactionHandle.isNormalOrdinal(ownerOrdinal)) {
            return null;
        }
        Faction victim = snap.faction(owner);
        return victim != null ? victim.id() : null;
    }

    private static ClaimMode modeOf(String token) {
        return switch (token) {
            case "square" -> ClaimMode.SQUARE;
            case "circle" -> ClaimMode.CIRCLE;
            case "fill" -> ClaimMode.FILL;
            case "nearby" -> ClaimMode.NEARBY;
            case "at" -> ClaimMode.AT;
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
        return List.of();
    }
}
