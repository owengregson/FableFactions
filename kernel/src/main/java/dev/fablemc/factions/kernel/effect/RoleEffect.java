package dev.fablemc.factions.kernel.effect;

import java.util.UUID;

import dev.fablemc.factions.kernel.intent.Origin;

/**
 * Rank / role effects: rank change plus the custom-role create/rename/reprioritize/prefix/delete/
 * assign vocabulary.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * See {@link Effect} for the hierarchy contract.
 */
public sealed interface RoleEffect extends Effect
        permits RoleEffect.RankChanged, RoleEffect.RoleCreated, RoleEffect.RoleRenamed,
        RoleEffect.RoleRePrioritized, RoleEffect.RolePrefixSet, RoleEffect.RoleDeleted,
        RoleEffect.RoleAssigned {

    record RankChanged(long seq, Origin origin, int faction, UUID player, int newRankIdx)
            implements RoleEffect {
    }

    record RoleCreated(long seq, Origin origin, int faction, String roleId, String name,
                       int priority) implements RoleEffect {
    }

    record RoleRenamed(long seq, Origin origin, int faction, String roleId, String oldName,
                       String newName) implements RoleEffect {
    }

    record RoleRePrioritized(long seq, Origin origin, int faction, String roleId, int priority)
            implements RoleEffect {
    }

    record RolePrefixSet(long seq, Origin origin, int faction, String roleId, String prefix)
            implements RoleEffect {
    }

    record RoleDeleted(long seq, Origin origin, int faction, String roleId) implements RoleEffect {
    }

    record RoleAssigned(long seq, Origin origin, int faction, UUID player, String roleId)
            implements RoleEffect {
    }
}
