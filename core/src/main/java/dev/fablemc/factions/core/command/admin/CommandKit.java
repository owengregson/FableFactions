package dev.fablemc.factions.core.command.admin;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.pipeline.SubmitResult;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * Shared submit/origin/handle plumbing for the W3c command trees (CONTRACTS §4, house style §8b).
 * A command {@code perform} pre-validates, then hands its {@link Intent} here — the single funnel
 * that stamps the {@link Origin}, submits on the bounded player lane, and answers the lane-full /
 * shutting-down {@link ReasonCode}s. Success and authoritative-failure feedback are never sent from
 * a command: the reducer emits them as effects and the feedback fan-out delivers them, so the
 * command layer never lies about a write before it commits.
 *
 * <p><b>Owning thread(s):</b> pure static, called from a command {@code perform} on the sender's
 * region/main thread. <b>Mutability:</b> stateless.
 */
public final class CommandKit {

    private static final Runnable NO_RETIRED = () -> { };

    private CommandKit() {
    }

    /**
     * Runs {@code render} on the sender's region/main thread — the delivery hop for the async read /
     * reload commands whose query stage completes off the server thread. A player sender hops via
     * {@code Scheduling.runOn}; a console sender via {@code runGlobal}.
     */
    public static void deliver(CommandContext ctx, Runnable render) {
        if (ctx.sender() instanceof Player player) {
            ctx.services().scheduling().runOn(player, render, NO_RETIRED);
        } else {
            ctx.services().scheduling().runGlobal(render);
        }
    }

    /** The admin ({@code /fa}) origin for a sender: the player's uuid, or an actor-less admin origin. */
    public static Origin adminOrigin(CommandSender sender) {
        return sender instanceof Player player
                ? Origin.admin(player.getUniqueId())
                : new Origin(null, Origin.ADMIN);
    }

    /** The player ({@code /f}) origin for {@code player}. */
    public static Origin playerOrigin(Player player) {
        return Origin.player(player.getUniqueId());
    }

    /** The generation-tagged handle of {@code faction} for use as an intent target. */
    public static int handleOf(KernelSnapshot snap, Faction faction) {
        return snap.state().factions().handleOf(faction.idx());
    }

    /**
     * Submits {@code intent} with {@code origin} on the bounded player lane and answers the busy /
     * shutting-down reasons; a clean {@code ACCEPTED} is silent (the reducer's effects speak).
     */
    public static void submit(CommandContext ctx, Intent intent, Origin origin) {
        SubmitResult result = ctx.services().bus().submit(intent, origin);
        if (result == SubmitResult.REJECTED_BUSY) {
            ctx.send(ReasonCode.BUSY.messageKey());
        } else if (result == SubmitResult.REJECTED_SHUTDOWN) {
            ctx.send(ReasonCode.SHUTTING_DOWN.messageKey());
        }
    }
}
