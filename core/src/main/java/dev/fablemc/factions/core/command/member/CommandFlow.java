package dev.fablemc.factions.core.command.member;

import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.pipeline.SubmitResult;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * Shared plumbing for the player command trees (member / claim / travel / bank / power): the
 * snapshot pre-validation guard, the {@code IntentBus.submit} → result → feedback shape, the
 * cancellable-API-event fire, and the {@code Locale.ROOT} money/number formatters. Centralizing
 * these keeps every command's {@code perform} to the CONTRACTS §4 shape (snapshot reads +
 * {@code IntentBus.submit} + {@code Messages}) with one guard idiom and one emission funnel
 * (house style §8a/§8b).
 *
 * <p><b>Owning thread(s):</b> pure static, called from a command {@code perform} on the sender's
 * region/main thread. <b>Mutability:</b> stateless.
 */
public final class CommandFlow {

    private CommandFlow() {
    }

    /**
     * The single early-return guard shape (house style §8a): when {@code reason} is non-null the
     * matching catalog message is sent and {@code true} is returned so the caller {@code return}s.
     */
    public static boolean blocked(CommandContext ctx, @Nullable ReasonCode reason) {
        if (reason != null) {
            ctx.sendReason(reason);
            return true;
        }
        return false;
    }

    /**
     * Submits {@code intent} as a player action by {@code actor} and reports the lane outcome: on
     * {@link SubmitResult#ACCEPTED} the optimistic {@code onSuccess} key is sent (skipped when
     * {@code null} — the reducer's own feedback covers it); a full lane / shutdown answers
     * {@code general.busy} / {@code general.shutting-down} (AM-9). The reducer stays the authority:
     * a race it loses surfaces as a {@code Rejected} effect routed back to {@code actor}.
     */
    public static void submit(CommandContext ctx, UUID actor, Intent intent,
                              @Nullable MessageKey onSuccess, String... successArgs) {
        submitReporting(ctx, actor, intent, onSuccess, successArgs);
    }

    /**
     * As {@link #submit}, but RETURNS the lane outcome so a caller that performed an external
     * mutation before submitting (a Vault wallet debit on {@code /f bank deposit} / {@code /f power
     * buy}) can compensate it when the lane rejects the intent — otherwise the money is debited but
     * the {@code CreditBank}/{@code BuyPower} never reduces, destroying it (findings #3/#18). The
     * reducer stays the authority for a race it accepts-then-rejects (its own refund/{@code Rejected}
     * effect covers that); this only closes the never-submitted gap.
     */
    public static SubmitResult submitReporting(CommandContext ctx, UUID actor, Intent intent,
                                               @Nullable MessageKey onSuccess, String... successArgs) {
        SubmitResult result = ctx.services().bus().submit(intent, Origin.player(actor));
        switch (result) {
            case ACCEPTED -> {
                if (onSuccess != null) {
                    ctx.send(onSuccess, successArgs);
                }
            }
            case REJECTED_BUSY -> ctx.sendReason(ReasonCode.BUSY);
            case REJECTED_SHUTDOWN -> ctx.sendReason(ReasonCode.SHUTTING_DOWN);
        }
        return result;
    }

    /**
     * Fires a cancellable API {@code event} on the caller thread (proposal-C §10.2 pre-flight
     * timing) and returns {@code true} when a listener cancelled it. Non-cancellable events pass
     * {@code false}.
     */
    public static boolean fireCancelled(Event event) {
        Bukkit.getPluginManager().callEvent(event);
        return event instanceof org.bukkit.event.Cancellable c && c.isCancelled();
    }

    /** The generation-tagged handle of {@code faction} within {@code snap}'s arena. */
    public static int handleOf(KernelSnapshot snap, Faction faction) {
        return snap.state().factions().handleOf(faction.idx());
    }

    /** The {@code World}-name (never a hot-path key) for an API event, or {@code ""} when unknown. */
    public static String worldName(org.bukkit.World world) {
        return world == null ? "" : world.getName();
    }

    /**
     * Removes the legacy section sign (U+00A7) from free-text player input (faction description /
     * MOTD) so raw colour/format codes cannot be smuggled into another player's view (finding #44).
     * The following code letter is left as ordinary text; {@code &amp;} is intentionally preserved.
     */
    public static String stripLegacyCodes(String text) {
        return text.indexOf('§') < 0 ? text : text.replace("§", "");
    }

    /** {@code Locale.ROOT} two-decimal money format (parity with the reference bank lines). */
    public static String money(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** {@code Locale.ROOT} one-decimal format (power / cap lines). */
    public static String fmt1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** {@code true} when {@code handle} resolves to a live normal faction in {@code snap}. */
    public static boolean isNormal(KernelSnapshot snap, int handle) {
        if (handle == FactionHandle.WILDERNESS) {
            return false;
        }
        Faction faction = snap.faction(handle);
        return faction != null && faction.isNormal();
    }
}
