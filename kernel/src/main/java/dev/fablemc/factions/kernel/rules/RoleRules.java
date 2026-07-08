package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.config.RoleConfig;
import dev.fablemc.factions.kernel.state.Rank;

/**
 * Rank / custom-role authority and validity rules (pvp-services.md §7.1/§7.8,
 * pvp-commands-admin.md §5). Pure static functions shared by the command layer and reducer.
 *
 * <p><b>Owning thread(s):</b> pure static. <b>Mutability:</b> stateless. <b>Reducer rule:</b>
 * the reducer applies role mutations after these gates pass.
 *
 * <p>Authority is a strict priority comparison ({@code canManage = actor.priority > target}).
 * Promote/demote step to the immediately adjacent rank by SORTED priority; a promote may never
 * enter the owner rank (ownership transfer is a separate intent). Custom priority validity is
 * {@code [min, max]}, falling back to the built-in {@code [11, 99]} band when misconfigured.
 */
public final class RoleRules {

    private RoleRules() {
    }

    /** Fallback lower bound for custom role priority when config is misordered. */
    public static final int MIN_CUSTOM_PRIORITY = 11;
    /** Fallback upper bound for custom role priority when config is misordered. */
    public static final int MAX_CUSTOM_PRIORITY = 99;

    /** {@code true} when {@code actor} strictly outranks {@code target} and may manage it. */
    public static boolean canManage(Rank actor, Rank target) {
        return actor != null && target != null && actor.priority() > target.priority();
    }

    /** Effective valid custom-priority range low bound (config or fallback). */
    public static int priorityLow(RoleConfig role) {
        return role.minCustomPriority() > role.maxCustomPriority()
                ? MIN_CUSTOM_PRIORITY : role.minCustomPriority();
    }

    /** Effective valid custom-priority range high bound (config or fallback). */
    public static int priorityHigh(RoleConfig role) {
        return role.minCustomPriority() > role.maxCustomPriority()
                ? MAX_CUSTOM_PRIORITY : role.maxCustomPriority();
    }

    /** {@code true} when {@code priority} is a valid custom-role priority. */
    public static boolean validCustomPriority(RoleConfig role, int priority) {
        return priority >= priorityLow(role) && priority <= priorityHigh(role);
    }

    /** Number of non-built-in (custom) ranks in {@code ranks}. */
    public static int countCustomRoles(Rank[] ranks) {
        int n = 0;
        for (Rank r : ranks) {
            if (!Rank.isProtectedBuiltin(r.name())) {
                n++;
            }
        }
        return n;
    }

    /** {@code true} when a new custom role stays within {@code maxCustomRolesPerFaction} (0=unlimited). */
    public static boolean withinCustomRoleLimit(RoleConfig role, Rank[] ranks) {
        int max = role.maxCustomRolesPerFaction();
        return max <= 0 || countCustomRoles(ranks) < max;
    }

    /** Array index of the rank named {@code name} (case-insensitive), or {@code -1}. */
    public static int indexByName(Rank[] ranks, String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < ranks.length; i++) {
            if (ranks[i].name().equalsIgnoreCase(name.trim())) {
                return i;
            }
        }
        return -1;
    }

    /** Array index of the rank with id {@code id}, or {@code -1}. */
    public static int indexById(Rank[] ranks, String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < ranks.length; i++) {
            if (id.equals(ranks[i].id())) {
                return i;
            }
        }
        return -1;
    }

    /** Array index of the owner rank (highest priority, {@code >= PRIORITY_OWNER}), or {@code -1}. */
    public static int ownerRankIndex(Rank[] ranks) {
        int best = -1;
        for (int i = 0; i < ranks.length; i++) {
            if (ranks[i].isOwner() && (best < 0 || ranks[i].priority() > ranks[best].priority())) {
                best = i;
            }
        }
        return best;
    }

    /** Array index of the lowest-priority rank (the default member rank), or {@code -1}. */
    public static int defaultRankIndex(Rank[] ranks) {
        int best = -1;
        for (int i = 0; i < ranks.length; i++) {
            if (best < 0 || ranks[i].priority() < ranks[best].priority()) {
                best = i;
            }
        }
        return best;
    }

    /** Array index of the officer rank (>= PRIORITY_OFFICER, below owner), or the owner if none. */
    public static int officerRankIndex(Rank[] ranks) {
        int best = -1;
        for (int i = 0; i < ranks.length; i++) {
            Rank r = ranks[i];
            if (r.isOfficerOrAbove() && !r.isOwner()
                    && (best < 0 || r.priority() > ranks[best].priority())) {
                best = i;
            }
        }
        return best;
    }

    /**
     * The array index of the rank one step from {@code currentRankIdx} by priority (promote =
     * next higher, demote = next lower), or {@code -1} when there is no such rank or a promote
     * would enter the owner rank.
     */
    public static int stepRank(Rank[] ranks, int currentRankIdx, boolean promote) {
        if (currentRankIdx < 0 || currentRankIdx >= ranks.length) {
            return -1;
        }
        Rank cur = ranks[currentRankIdx];
        int bestIdx = -1;
        for (int i = 0; i < ranks.length; i++) {
            Rank r = ranks[i];
            if (promote) {
                if (r.priority() > cur.priority()
                        && (bestIdx < 0 || r.priority() < ranks[bestIdx].priority())) {
                    bestIdx = i;
                }
            } else {
                if (r.priority() < cur.priority()
                        && (bestIdx < 0 || r.priority() > ranks[bestIdx].priority())) {
                    bestIdx = i;
                }
            }
        }
        if (bestIdx >= 0 && promote && ranks[bestIdx].isOwner()) {
            return -1; // cannot promote into the owner rank
        }
        return bestIdx;
    }
}
