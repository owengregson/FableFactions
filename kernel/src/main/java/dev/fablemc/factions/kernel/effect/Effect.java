package dev.fablemc.factions.kernel.effect;

import java.util.UUID;

import dev.fablemc.factions.kernel.audit.FactionAuditAction;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;

/**
 * The complete effect vocabulary — the ordered, seq-numbered output of the reducer
 * (proposal-C §4.3). Effects drive derived indexes, storage projection, feedback, integrations
 * and the API event bridge; the journal of effects is the complete replayable history.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out to journal/storage/subscribers.
 * <b>Mutability:</b> immutable value hierarchy. <b>Reducer rule:</b> only the reducer emits
 * effects, and it never emits text — feedback effects carry a {@link MessageKey} plus args.
 *
 * <p>Every effect carries its {@code long seq} (the writer's total order) and the causing
 * intent's {@link Origin} for traceability. Records are nested so the sealed hierarchy is
 * self-contained and switch-exhaustive (the storage projector and API bridge each switch over
 * these).
 */
public sealed interface Effect {

    /** The writer-assigned total-order sequence number every effect carries. */
    long seq();

    /** The origin of the intent that produced this effect. */
    Origin origin();

    // ── Domain deltas ────────────────────────────────────────────────────────────────────

    record FactionCreated(long seq, Origin origin, int faction, UUID id, String name)
            implements Effect {
    }

    record FactionDisbanded(long seq, Origin origin, int faction, String name) implements Effect {
    }

    record FactionRenamed(long seq, Origin origin, int faction, String oldName, String newName)
            implements Effect {
    }

    record DescriptionChanged(long seq, Origin origin, int faction, String description)
            implements Effect {
    }

    record MotdChanged(long seq, Origin origin, int faction, String motd) implements Effect {
    }

    record OwnershipTransferred(long seq, Origin origin, int faction, UUID oldOwner, UUID newOwner)
            implements Effect {
    }

    record MergeRequested(long seq, Origin origin, int sender, int target) implements Effect {
    }

    record MergeCompleted(long seq, Origin origin, int sender, int target, int memberMoves,
                          int claimMoves, double bankMoved) implements Effect {
    }

    record MemberJoined(long seq, Origin origin, int faction, UUID player) implements Effect {
    }

    record MemberLeft(long seq, Origin origin, int faction, UUID player, boolean kicked)
            implements Effect {
    }

    record InviteCreated(long seq, Origin origin, int faction, UUID invitee, long inviteId)
            implements Effect {
    }

    record InviteRemoved(long seq, Origin origin, int faction, UUID invitee, int reason)
            implements Effect {
    }

    record RankChanged(long seq, Origin origin, int faction, UUID player, int newRankIdx)
            implements Effect {
    }

    record RoleCreated(long seq, Origin origin, int faction, String roleId, String name,
                       int priority) implements Effect {
    }

    record RoleRenamed(long seq, Origin origin, int faction, String roleId, String oldName,
                       String newName) implements Effect {
    }

    record RoleRePrioritized(long seq, Origin origin, int faction, String roleId, int priority)
            implements Effect {
    }

    record RolePrefixSet(long seq, Origin origin, int faction, String roleId, String prefix)
            implements Effect {
    }

    record RoleDeleted(long seq, Origin origin, int faction, String roleId) implements Effect {
    }

    record RoleAssigned(long seq, Origin origin, int faction, UUID player, String roleId)
            implements Effect {
    }

    /** {@code prevOwner} is {@code -1} (wilderness) or the overclaimed victim's handle. */
    record ClaimSet(long seq, Origin origin, int worldIdx, long key, int faction, int prevOwner)
            implements Effect {
    }

    record ClaimRemoved(long seq, Origin origin, int worldIdx, long key, int prevOwner)
            implements Effect {
    }

    record ZoneSet(long seq, Origin origin, int zoneOrdinal, int worldIdx, long key, int prevOwner)
            implements Effect {
    }

    record ZoneRemoved(long seq, Origin origin, int zoneOrdinal, int worldIdx, long key)
            implements Effect {
    }

    record RelationDeclared(long seq, Origin origin, int a, int b, int kind) implements Effect {
    }

    record RelationEffective(long seq, Origin origin, int a, int b, int kind, int prevKind)
            implements Effect {
    }

    record PowerChanged(long seq, Origin origin, UUID player, double before, double after,
                        int source, String reasonCode) implements Effect {
    }

    record PowerFrozenChanged(long seq, Origin origin, UUID player, boolean frozen)
            implements Effect {
    }

    record DeathStreakAdvanced(long seq, Origin origin, UUID player, int streak)
            implements Effect {
    }

    record RaidableChanged(long seq, Origin origin, int faction, boolean nowRaidable)
            implements Effect {
    }

    record BankChanged(long seq, Origin origin, int faction, double delta, double balance,
                       int txType, UUID actor, int counterparty, String note) implements Effect {
    }

    record TaxCharged(long seq, Origin origin, int faction, double amount, double balance)
            implements Effect {
    }

    record HomeSet(long seq, Origin origin, int faction) implements Effect {
    }

    record HomeCleared(long seq, Origin origin, int faction) implements Effect {
    }

    record WarpSet(long seq, Origin origin, int faction, String name) implements Effect {
    }

    record WarpDeleted(long seq, Origin origin, int faction, String name) implements Effect {
    }

    record WarpPasswordSet(long seq, Origin origin, int faction, String name, boolean cleared)
            implements Effect {
    }

    record WarpCostSet(long seq, Origin origin, int faction, String name, double cost)
            implements Effect {
    }

    record ChestCreated(long seq, Origin origin, int faction, String name) implements Effect {
    }

    record ChestDeleted(long seq, Origin origin, int faction, String name) implements Effect {
    }

    record ChestContentsChanged(long seq, Origin origin, int faction, String name, long blobRef)
            implements Effect {
    }

    record FlagChanged(long seq, Origin origin, int faction, int flag, boolean value)
            implements Effect {
    }

    record PrefChanged(long seq, Origin origin, UUID player, int prefBit, boolean value)
            implements Effect {
    }

    record LocaleChanged(long seq, Origin origin, UUID player, int localeIdx) implements Effect {
    }

    record AutoModeChanged(long seq, Origin origin, UUID player, int mode) implements Effect {
    }

    record FlyChanged(long seq, Origin origin, UUID player, boolean on) implements Effect {
    }

    record OverrideChanged(long seq, Origin origin, UUID player, boolean on) implements Effect {
    }

    record ShieldChanged(long seq, Origin origin, int faction, int startHour, int durationHours)
            implements Effect {
    }

    record SessionStarted(long seq, Origin origin, UUID player, long lastActivity)
            implements Effect {
    }

    record SessionEnded(long seq, Origin origin, UUID player, long lastActivity)
            implements Effect {
    }

    record InboxQueued(long seq, Origin origin, UUID player, MessageKey key, String[] args)
            implements Effect {
    }

    record InboxDelivered(long seq, Origin origin, UUID player, long[] ids) implements Effect {
    }

    record AuditRecorded(long seq, Origin origin, int faction, UUID actor,
                         FactionAuditAction action, String detail) implements Effect {
    }

    record ConfigSwapped(long seq, Origin origin, String diffSummary) implements Effect {
    }

    // ── Feedback ─────────────────────────────────────────────────────────────────────────

    /** Deliver a message to one player. */
    record Notify(long seq, Origin origin, UUID target, MessageKey key, String[] args)
            implements Effect {
    }

    /** Deliver a message to a faction's members ({@code predicate} selects which); offline → inbox. */
    record NotifyFaction(long seq, Origin origin, int faction, int predicate, MessageKey key,
                         String[] args) implements Effect {
    }

    /** Broadcast a message ({@code scope} = server/staff/etc.). */
    record Broadcast(long seq, Origin origin, int scope, MessageKey key, String[] args)
            implements Effect {
    }

    /** A rejection with its 1:1-mapped {@link ReasonCode} and message args. */
    record Rejected(long seq, Origin origin, ReasonCode reason, String[] args) implements Effect {
    }

    // ── External requests (executed by adapters; may re-enter as intents) ─────────────────

    record PayoutRequested(long seq, Origin origin, long escrowId, UUID player, double amount)
            implements Effect {
    }

    record EscrowRefund(long seq, Origin origin, long escrowId, UUID player, double amount)
            implements Effect {
    }

    record WgRegionUpsert(long seq, Origin origin, int worldIdx, long key, int faction)
            implements Effect {
    }

    record WgRegionRemove(long seq, Origin origin, int worldIdx, long key) implements Effect {
    }

    record LwcPurgeRequested(long seq, Origin origin, int worldIdx, long key, int newOwner)
            implements Effect {
    }

    // ── Enumerated byte codes carried by the records above ────────────────────────────────

    /** {@link InviteRemoved#reason()}: the invitee accepted the invite. */
    byte INVITE_ACCEPTED = 0;
    /** {@link InviteRemoved#reason()}: the invite was revoked by the faction. */
    byte INVITE_REVOKED = 1;
    /** {@link InviteRemoved#reason()}: the invitee declined. */
    byte INVITE_DECLINED = 2;
    /** {@link InviteRemoved#reason()}: the invite expired (TTL). */
    byte INVITE_EXPIRED = 3;

    /** {@link BankChanged#txType()}: a member deposit. */
    byte TX_DEPOSIT = 0;
    /** {@link BankChanged#txType()}: a member withdrawal. */
    byte TX_WITHDRAW = 1;
    /** {@link BankChanged#txType()}: a transfer between factions. */
    byte TX_TRANSFER = 2;
    /** {@link BankChanged#txType()}: a tax charge. */
    byte TX_TAX = 3;

    /** {@link NotifyFaction#predicate()}: every member. */
    byte MEMBERS_ALL = 0;
    /** {@link NotifyFaction#predicate()}: officers and above. */
    byte MEMBERS_OFFICERS = 1;

    /** {@link Broadcast#scope()}: all online players. */
    byte SCOPE_SERVER = 0;
    /** {@link Broadcast#scope()}: staff (permission-gated) + console. */
    byte SCOPE_STAFF = 1;
}
