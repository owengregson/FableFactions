package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

/**
 * Rank / role intents: promote/demote plus the custom-role create/rename/priority/prefix/delete/
 * assign vocabulary.
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. See {@link Intent} for the hierarchy contract.
 */
public sealed interface RoleIntent extends Intent
        permits RoleIntent.PromoteMember, RoleIntent.DemoteMember, RoleIntent.CreateRole,
        RoleIntent.RenameRole, RoleIntent.SetRolePriority, RoleIntent.SetRolePrefix,
        RoleIntent.DeleteRole, RoleIntent.AssignRole {

    /** {@code actor} promotes {@code target} one rank in {@code faction}. */
    record PromoteMember(int faction, UUID actor, UUID target) implements RoleIntent {
    }

    /** {@code actor} demotes {@code target} one rank in {@code faction}. */
    record DemoteMember(int faction, UUID actor, UUID target) implements RoleIntent {
    }

    /** Create a custom role; {@code prefix} may be {@code null}. */
    record CreateRole(int faction, UUID actor, String name, int priority, String prefix)
            implements RoleIntent {
    }

    /** Rename role {@code oldName} to {@code newName}. */
    record RenameRole(int faction, UUID actor, String oldName, String newName)
            implements RoleIntent {
    }

    /** Set role {@code roleName}'s priority. */
    record SetRolePriority(int faction, UUID actor, String roleName, int priority)
            implements RoleIntent {
    }

    /** Set role {@code roleName}'s prefix ({@code null} clears). */
    record SetRolePrefix(int faction, UUID actor, String roleName, String prefix)
            implements RoleIntent {
    }

    /** Delete custom role {@code roleName}. */
    record DeleteRole(int faction, UUID actor, String roleName) implements RoleIntent {
    }

    /** Assign role {@code roleName} to {@code target}. */
    record AssignRole(int faction, UUID actor, UUID target, String roleName)
            implements RoleIntent {
    }
}
