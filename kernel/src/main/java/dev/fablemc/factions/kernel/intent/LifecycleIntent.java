package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

import dev.fablemc.factions.kernel.vocab.PagePhase;

/**
 * Faction lifecycle intents: creation, disband (paged), rename, description/MOTD, ownership
 * transfer, and the merge request/accept handshake (accept is paged).
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. See {@link Intent} for the hierarchy contract.
 */
public sealed interface LifecycleIntent extends Intent
        permits LifecycleIntent.CreateFaction, LifecycleIntent.DisbandFaction,
        LifecycleIntent.RenameFaction, LifecycleIntent.SetDescription, LifecycleIntent.SetMotd,
        LifecycleIntent.TransferOwnership, LifecycleIntent.SendMergeRequest,
        LifecycleIntent.AcceptMergeRequest, LifecycleIntent.DisbandPage, LifecycleIntent.MergePage {

    /** Create a new faction named {@code name}, owned by {@code owner}. */
    record CreateFaction(String name, UUID owner) implements LifecycleIntent {
    }

    /** Disband {@code faction}; {@code byAdmin} bypasses ownership checks. Paged (AM-5). */
    record DisbandFaction(int faction, boolean byAdmin, UUID actor) implements LifecycleIntent {
    }

    /** Rename {@code faction} to {@code newName} (re-renders the chat tag). */
    record RenameFaction(int faction, String newName, UUID actor) implements LifecycleIntent {
    }

    /** Set {@code faction}'s description. */
    record SetDescription(int faction, String description, UUID actor) implements LifecycleIntent {
    }

    /** Set {@code faction}'s MOTD ({@code null}/blank clears). */
    record SetMotd(int faction, String motd, UUID actor) implements LifecycleIntent {
    }

    /** Transfer ownership of {@code faction} to {@code newOwner}. */
    record TransferOwnership(int faction, UUID newOwner, UUID actor) implements LifecycleIntent {
    }

    /** Send a merge request from {@code sender} into {@code target}. */
    record SendMergeRequest(int sender, int target, UUID actor) implements LifecycleIntent {
    }

    /** Accept a merge of {@code sender} into {@code target}. Paged (AM-5). */
    record AcceptMergeRequest(int sender, int target, UUID actor) implements LifecycleIntent {
    }

    /** A disband page: {@code phase} CLAIMS/MEMBERS/FINAL; {@code cursor} is progress. */
    record DisbandPage(int faction, PagePhase phase, int cursor, boolean byAdmin, UUID actor)
            implements LifecycleIntent {
    }

    /** A merge page: {@code phase} CLAIMS(=claims/warps)/MEMBERS/FINAL; {@code cursor} is progress. */
    record MergePage(int sender, int target, PagePhase phase, int cursor, UUID actor)
            implements LifecycleIntent {
    }
}
