package dev.fablemc.factions.core.text;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.entity.Player;

import dev.fablemc.factions.core.pipeline.FeedbackRouter;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.vocab.BroadcastScope;
import dev.fablemc.factions.kernel.vocab.NotifyPredicate;
import dev.fablemc.factions.platform.resolve.Players;
import dev.fablemc.factions.platform.sched.Scheduling;

/**
 * The {@link FeedbackRouter} implementation: routes each committed {@link FeedbackEffect} to text
 * delivery through {@link Messages} (ARCHITECTURE §3.4/§10.2). Runs on the writer thread after the
 * batch's snapshot has been published, so member iteration reads the current snapshot and every
 * player-facing send hops to the right region thread via {@link Messages#toPlayer}.
 *
 * <p><b>Owning thread(s):</b> {@link #route} is invoked by the effect fan-out on the single writer
 * thread; it never blocks — all Bukkit-touching work is marshalled through {@link Scheduling}.
 * <b>Mutability:</b> immutable (holds only injected collaborators).
 *
 * <p>Offline members are NOT handled here: the reducer already emitted {@code InboxQueued} effects
 * for them, so {@code NotifyFaction} fans out to ONLINE members only (no double delivery).
 */
public final class EffectFeedback implements FeedbackRouter {

    /** Permission gate for {@link BroadcastScope#STAFF} broadcasts. */
    private static final String STAFF_PERMISSION = "factions.admin";

    private final Messages messages;
    private final SnapshotHub snapshots;
    private final Scheduling scheduling;

    /** Constructor injection: the message layer, the snapshot source, and the scheduler. */
    public EffectFeedback(Messages messages, SnapshotHub snapshots, Scheduling scheduling) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
    }

    @Override
    public void route(List<Effect> batch, long lastSeq) {
        for (int i = 0; i < batch.size(); i++) {
            Effect effect = batch.get(i);
            if (effect instanceof FeedbackEffect feedback) {
                dispatch(feedback);
            }
        }
    }

    // Sealed-type dispatch as an instanceof chain with the AM-9 fail-safe throw (house style §8d).
    private void dispatch(FeedbackEffect feedback) {
        if (feedback instanceof FeedbackEffect.Notify notify) {
            messages.toPlayer(notify.target(), notify.key(), notify.args());
        } else if (feedback instanceof FeedbackEffect.Rejected rejected) {
            UUID actor = rejected.origin().actor();
            if (actor != null) {
                messages.toPlayer(actor, rejected.reason().messageKey(), rejected.args());
            }
        } else if (feedback instanceof FeedbackEffect.NotifyFaction notifyFaction) {
            notifyFaction(notifyFaction);
        } else if (feedback instanceof FeedbackEffect.Broadcast broadcast) {
            broadcast(broadcast);
        } else {
            throw new IllegalStateException("unhandled FeedbackEffect: " + feedback);
        }
    }

    private void notifyFaction(FeedbackEffect.NotifyFaction effect) {
        KernelSnapshot snapshot = snapshots.current();
        Faction faction = snapshot.faction(effect.faction());
        if (faction == null) {
            return;
        }
        int factionOrdinal = faction.idx();
        PlayerLedger ledger = snapshot.state().ledger();
        Rank[] ranks = faction.ranks();
        boolean officersOnly = effect.predicate() == NotifyPredicate.MEMBERS_OFFICERS;
        MessageKey key = effect.key();
        String[] args = effect.args();
        snapshot.state().online().forEach(ordinal -> {
            if (FactionHandle.ordinal(ledger.factionHandle(ordinal)) != factionOrdinal) {
                return;
            }
            if (officersOnly && !isOfficerOrAbove(ranks, ledger.rankIdx(ordinal))) {
                return;
            }
            messages.toPlayer(ledger.uuid(ordinal), key, args);
        });
    }

    private void broadcast(FeedbackEffect.Broadcast effect) {
        boolean staffOnly = switch (effect.scope()) {
            case SERVER -> false;
            case STAFF -> true;
        };
        MessageKey key = effect.key();
        String[] args = effect.args();
        // Enumerating online players is a Bukkit touch — hop to the global tick first, then each
        // per-player send re-hops to that player's region via Messages#toPlayer.
        scheduling.runGlobal(() -> {
            for (Player player : Players.online()) {
                if (staffOnly && !player.hasPermission(STAFF_PERMISSION)) {
                    continue;
                }
                messages.toPlayer(player.getUniqueId(), key, args);
            }
        });
    }

    private static boolean isOfficerOrAbove(Rank[] ranks, int rankIdx) {
        return rankIdx >= 0 && rankIdx < ranks.length && ranks[rankIdx].isOfficerOrAbove();
    }
}
