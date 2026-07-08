package dev.fablemc.factions.api.event;

import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import dev.fablemc.factions.api.BankTransactionType;

/**
 * Fired before a faction bank movement (proposal-C §10.2), during pre-flight on the caller
 * thread. The {@code amount} is <b>mutable</b> so a listener may adjust a deposit/withdrawal
 * (e.g. apply a tax or bonus) before it is applied; cancelling aborts the transaction.
 *
 * <p><b>Owning thread(s):</b> the caller's region/main thread. <b>Mutability:</b> the
 * {@code amount} and {@code cancelled} fields. Identity is {@link UUID} plus the API-native
 * {@link BankTransactionType} enum (CONTRACTS §5).
 */
public final class FactionBankTransactionEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID factionId;
    private final BankTransactionType type;
    private final UUID actor;
    private final UUID counterparty;
    private double amount;
    private boolean cancelled;

    public FactionBankTransactionEvent(UUID factionId, BankTransactionType type, double amount,
                                       UUID actor, UUID counterparty) {
        this.factionId = factionId;
        this.type = type;
        this.amount = amount;
        this.actor = actor;
        this.counterparty = counterparty;
    }

    /** The faction whose bank is affected. */
    public UUID getFactionId() {
        return factionId;
    }

    /** The kind of movement. */
    public BankTransactionType getType() {
        return type;
    }

    /** The (mutable) signed amount that will be applied. */
    public double getAmount() {
        return amount;
    }

    /** Adjusts the amount that will be applied. */
    public void setAmount(double amount) {
        this.amount = amount;
    }

    /** The player performing the transaction ({@code null} for a system/tax movement). */
    public UUID getActor() {
        return actor;
    }

    /** The counterparty faction for a transfer, or {@code null}. */
    public UUID getCounterparty() {
        return counterparty;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
