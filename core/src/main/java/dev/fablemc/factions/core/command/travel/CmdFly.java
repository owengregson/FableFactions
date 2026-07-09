package dev.fablemc.factions.core.command.travel;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.member.CommandFlow;
import dev.fablemc.factions.core.pipeline.SubmitResult;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * {@code /f fly} — toggles faction flight (ref-commands-core.md §7.15). Requires faction membership,
 * that flight is globally enabled, and — when configured — that the caller stands in their own
 * territory. Applies {@code setAllowFlight} locally and submits {@link PrefIntent.SetFly} for
 * persistence.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the player's region/main thread (the
 * {@code setAllowFlight} touch is on the player's own region). <b>Mutability:</b> holds the injected
 * {@link Worlds} registry; stateless per invocation.
 */
final class CmdFly extends CommandNode {

    private static final MessageKey DISABLED_GLOBAL = MessageKey.of("custom.fly.disabled-global");
    private static final MessageKey OWN_TERRITORY = MessageKey.of("custom.fly.own-territory-required");
    private static final MessageKey COMBAT_BLOCKED = MessageKey.of("custom.travel.combat-blocked");
    private static final MessageKey ENABLED = MessageKey.of("fly.enabled");
    private static final MessageKey DISABLED = MessageKey.of("fly.disabled");

    private final Worlds worlds;

    CmdFly(Worlds worlds) {
        super("fly");
        setPermission("factions.cmd.fly");
        setRequiresPlayer(true);
        setDescription("Toggle faction flight");
        this.worlds = worlds;
    }

    @Override
    protected void perform(CommandContext ctx) {
        UUID actor = ctx.player().getUniqueId();
        KernelSnapshot snap = ctx.snap();
        if (CommandFlow.blocked(ctx, CommandGuards.requireFaction(snap, actor))) {
            return;
        }
        if (!snap.config().fly().enabled()) {
            ctx.send(DISABLED_GLOBAL);
            return;
        }
        Faction faction = CommandGuards.factionOf(snap, actor);
        if (snap.config().fly().requireOwnTerritory() && !inOwnTerritory(ctx.player(), snap, faction.idx())) {
            ctx.send(OWN_TERRITORY);
            return;
        }
        Player player = ctx.player();
        boolean newState = !currentFly(snap, actor);
        // (#30a) Deny ENABLING flight while combat-tagged — flight would be a free escape from a fight.
        // Read the tag via the Travel seam (it owns combat-tag gating). Disabling stays allowed.
        if (newState && ctx.services().travel().inCombat(player)) {
            ctx.send(COMBAT_BLOCKED);
            return;
        }
        // (#30b) Touch setAllowFlight ONLY after the SetFly pref is ACCEPTED. Granting it before submit
        // left flight enabled with the pref unset on a REJECTED_BUSY — a permanent-revocation desync.
        // Mirror CommandFlow.submit's lane handling so the client-side grant lands only on ACCEPTED.
        SubmitResult result = ctx.services().bus().submit(new PrefIntent.SetFly(actor, newState), Origin.player(actor));
        switch (result) {
            case ACCEPTED -> {
                player.setAllowFlight(newState);
                if (!newState && player.isFlying()) {
                    player.setFlying(false);
                }
                ctx.send(newState ? ENABLED : DISABLED);
            }
            case REJECTED_BUSY -> ctx.sendReason(ReasonCode.BUSY);
            case REJECTED_SHUTDOWN -> ctx.sendReason(ReasonCode.SHUTTING_DOWN);
        }
    }

    private boolean inOwnTerritory(Player player, KernelSnapshot snap, int ownOrdinal) {
        Location loc = player.getLocation();
        int worldIdx = worlds.register(player.getWorld().getUID());
        long key = ChunkKeys.key(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        int owner = snap.claimOwnerAt(worldIdx, key);
        return owner != FactionHandle.WILDERNESS && FactionHandle.ordinal(owner) == ownOrdinal;
    }

    private static boolean currentFly(KernelSnapshot snap, UUID actor) {
        int ordinal = snap.memberOrdinal(actor);
        if (ordinal < 0) {
            return false;
        }
        MemberView member = snap.member(ordinal);
        return member != null && PlayerLedger.pref(member.prefsBits(), PlayerLedger.PREF_FLY);
    }
}
