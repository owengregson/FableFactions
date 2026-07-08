package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

/**
 * Membership and invite intents: join/leave/kick and the invite send/revoke/decline vocabulary.
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. See {@link Intent} for the hierarchy contract.
 */
public sealed interface MembershipIntent extends Intent
        permits MembershipIntent.JoinFaction, MembershipIntent.LeaveFaction,
        MembershipIntent.KickMember, MembershipIntent.SendInvite, MembershipIntent.RevokeInvite,
        MembershipIntent.DeclineInvite, MembershipIntent.DeclineAllInvites {

    /** {@link JoinFaction#viaInviteId()} value meaning "joined via the OPEN flag, no invite". */
    long OPEN_JOIN = -1L;

    /** {@code player} joins {@code faction} via {@code viaInviteId}, or {@link #OPEN_JOIN}. */
    record JoinFaction(int faction, UUID player, long viaInviteId) implements MembershipIntent {
    }

    /** {@code player} leaves {@code faction}. */
    record LeaveFaction(int faction, UUID player) implements MembershipIntent {
    }

    /** {@code actor} kicks {@code target} from {@code faction}. */
    record KickMember(int faction, UUID actor, UUID target) implements MembershipIntent {
    }

    /** {@code inviter} invites {@code invitee} to {@code faction}. */
    record SendInvite(int faction, UUID inviter, UUID invitee) implements MembershipIntent {
    }

    /** {@code inviter} revokes {@code invitee}'s invite to {@code faction}. */
    record RevokeInvite(int faction, UUID inviter, UUID invitee) implements MembershipIntent {
    }

    /** {@code invitee} declines a specific faction's invite. */
    record DeclineInvite(int faction, UUID invitee) implements MembershipIntent {
    }

    /** {@code invitee} declines all pending invites. */
    record DeclineAllInvites(UUID invitee) implements MembershipIntent {
    }
}
