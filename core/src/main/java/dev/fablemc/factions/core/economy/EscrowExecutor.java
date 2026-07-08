package dev.fablemc.factions.core.economy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.entity.Player;

import dev.fablemc.factions.core.pipeline.EffectSink;
import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.ExternalEffect;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.kernel.vocab.EscrowOutcome;
import dev.fablemc.factions.platform.resolve.Players;
import dev.fablemc.factions.platform.sched.Scheduling;

/**
 * The Vault-payout leg of the durable escrow saga (AM-7, proposal-C §4.6). As an {@link EffectSink}
 * subscriber it consumes the writer's {@link ExternalEffect.PayoutRequested} (bank withdrawal → the
 * player's wallet) and {@link ExternalEffect.EscrowRefund} (compensation → the player's wallet),
 * performs the wallet {@code deposit} on the <b>player's own region thread</b> via {@link
 * Scheduling#runOn}, and enqueues {@link EconomyIntent.SettleEscrow} carrying the outcome so the
 * reducer closes (or, on Vault failure, compensates) the escrow. This bounds the unavoidable crash
 * window to a single operation and never duplicates money.
 *
 * <p><b>Owning thread(s):</b> {@link #accept} runs on the single writer thread — it only schedules
 * the wallet work and returns promptly (the writer never blocks on JDBC/Vault). The deposit itself
 * runs on the target player's region thread (or, when the player has left, off-thread by UUID).
 * <b>Mutability:</b> immutable (holds only injected collaborators).
 */
public final class EscrowExecutor implements EffectSink {

    private static final Runnable NO_RETIRED = () -> { };

    private final VaultAdapter vault;
    private final Scheduling scheduling;
    private final IntentBus bus;

    /** Constructor injection (CONTRACTS §4): the Vault bridge, the scheduler and the intent bus. */
    public EscrowExecutor(VaultAdapter vault, Scheduling scheduling, IntentBus bus) {
        this.vault = Objects.requireNonNull(vault, "vault");
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    @Override
    public void accept(List<Effect> batch, long lastSeq) {
        for (int i = 0; i < batch.size(); i++) {
            Effect effect = batch.get(i);
            if (effect instanceof ExternalEffect.PayoutRequested payout) {
                settle(payout.escrowId(), payout.player(), payout.amount());
            } else if (effect instanceof ExternalEffect.EscrowRefund refund) {
                settle(refund.escrowId(), refund.player(), refund.amount());
            }
        }
    }

    /**
     * Deposits {@code amount} into {@code playerId}'s wallet on their region thread, then settles the
     * escrow with the transaction outcome. If the player is offline (or leaves before the hop runs)
     * the deposit falls back to the offline UUID overload so the money is never dropped.
     */
    private void settle(long escrowId, UUID playerId, double amount) {
        Player online = Players.get(playerId);
        if (online != null) {
            scheduling.runOn(online,
                    () -> completeOnline(escrowId, online, amount),
                    () -> completeOffline(escrowId, playerId, amount));
        } else {
            scheduling.runAsync(() -> completeOffline(escrowId, playerId, amount));
        }
    }

    private void completeOnline(long escrowId, Player player, double amount) {
        settleWith(escrowId, vault.deposit(player, amount));
    }

    private void completeOffline(long escrowId, UUID playerId, double amount) {
        settleWith(escrowId, vault.depositOffline(playerId, amount));
    }

    private void settleWith(long escrowId, boolean ok) {
        bus.submitSystem(new EconomyIntent.SettleEscrow(escrowId, ok ? EscrowOutcome.OK : EscrowOutcome.FAILED));
    }
}
