package dev.fablemc.factions.kernel.effect;

import java.util.UUID;

import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.vocab.InviteRemovalReason;

/**
 * Membership effects: member join/leave and the invite create/remove pair.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * See {@link Effect} for the hierarchy contract.
 */
public sealed interface MembershipEffect extends Effect
        permits MembershipEffect.MemberJoined, MembershipEffect.MemberLeft,
        MembershipEffect.InviteCreated, MembershipEffect.InviteRemoved {

    record MemberJoined(long seq, Origin origin, int faction, UUID player)
            implements MembershipEffect {
    }

    record MemberLeft(long seq, Origin origin, int faction, UUID player, boolean kicked)
            implements MembershipEffect {
    }

    record InviteCreated(long seq, Origin origin, int faction, UUID invitee, long inviteId)
            implements MembershipEffect {
    }

    record InviteRemoved(long seq, Origin origin, int faction, UUID invitee,
                         InviteRemovalReason reason) implements MembershipEffect {
    }
}
