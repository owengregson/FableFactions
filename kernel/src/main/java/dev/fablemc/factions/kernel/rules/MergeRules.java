package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.MergeTable;

/**
 * Faction merge validation (pvp-services.md §7.5, pvp-commands-misc.md merge). Pure static
 * functions shared by the command layer and reducer.
 *
 * <p><b>Owning thread(s):</b> pure static. <b>Mutability:</b> stateless. <b>Reducer rule:</b>
 * accept executes the merge in AM-5 pages (claims/warps → members → registry final) and scrubs
 * the request; disband scrub also drops any request naming a vanished faction.
 */
public final class MergeRules {

    private MergeRules() {
    }

    /** {@code null} when a merge request from {@code sender} into {@code target} is valid to send. */
    public static ReasonCode validateSend(KernelState state, int senderHandle, int targetHandle) {
        if (!state.config().mergeEnabled()) {
            return ReasonCode.MERGE_DISABLED;
        }
        Faction sender = state.factions().resolve(senderHandle);
        Faction target = state.factions().resolve(targetHandle);
        if (sender == null || target == null) {
            return ReasonCode.FACTION_NOT_FOUND;
        }
        if (sender.idx() == target.idx()) {
            return ReasonCode.MERGE_SELF;
        }
        if (!sender.isNormal() || !target.isNormal()) {
            return ReasonCode.MERGE_SELF;
        }
        if (state.mergeRequests().has(sender.idx(), target.idx())) {
            return ReasonCode.MERGE_ALREADY_REQUESTED;
        }
        return null;
    }

    /** {@code null} when a merge of {@code sender} into {@code target} may be accepted. */
    public static ReasonCode validateAccept(KernelState state, int senderHandle, int targetHandle) {
        if (!state.config().mergeEnabled()) {
            return ReasonCode.MERGE_DISABLED;
        }
        Faction sender = state.factions().resolve(senderHandle);
        Faction target = state.factions().resolve(targetHandle);
        if (sender == null || target == null) {
            return ReasonCode.FACTION_NOT_FOUND;
        }
        int senderOrd = FactionHandle.ordinal(senderHandle);
        int targetOrd = FactionHandle.ordinal(targetHandle);
        MergeTable.MergeRequest req = state.mergeRequests().find(senderOrd, targetOrd);
        if (req == null) {
            return ReasonCode.MERGE_NO_REQUEST;
        }
        return null;
    }
}
