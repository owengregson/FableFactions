package dev.fablemc.factions.core.command;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;
import dev.fablemc.factions.kernel.state.Rank;

/**
 * Snapshot-based membership / rank guards shared by every command (CONTRACTS §4,
 * ref-commands-core.md §4.8). Each guard returns the {@link ReasonCode} the failure maps to, or
 * {@code null} on success — the command then calls {@link CommandContext#sendReason} so the text
 * comes from the catalog, never the command (CONTRACTS §6.4). Reads are pure over the snapshot the
 * command took at dispatch; the reducer re-checks the same rules, so a lost TOCTOU race reads
 * identically.
 *
 * <p><b>Owning thread(s):</b> pure static, called from a command {@code perform} on the sender's
 * region/main thread. <b>Mutability:</b> stateless.
 */
public final class CommandGuards {

    private CommandGuards() {
    }

    /** {@code null} when {@code player} belongs to a normal faction, else {@link ReasonCode#NOT_IN_FACTION}. */
    public static @Nullable ReasonCode requireFaction(KernelSnapshot snap, UUID player) {
        return factionOf(snap, player) == null ? ReasonCode.NOT_IN_FACTION : null;
    }

    /**
     * {@code null} when {@code player} is the owner of their faction; {@link ReasonCode#NOT_IN_FACTION}
     * when factionless, else {@link ReasonCode#MUST_BE_LEADER}.
     */
    public static @Nullable ReasonCode requireOwner(KernelSnapshot snap, UUID player) {
        if (factionOf(snap, player) == null) {
            return ReasonCode.NOT_IN_FACTION;
        }
        Rank rank = rankOf(snap, player);
        return rank != null && rank.isOwner() ? null : ReasonCode.MUST_BE_LEADER;
    }

    /**
     * {@code null} when {@code player} is officer-or-above; {@link ReasonCode#NOT_IN_FACTION} when
     * factionless, else {@link ReasonCode#MUST_BE_OFFICER}.
     */
    public static @Nullable ReasonCode requireOfficerOrAbove(KernelSnapshot snap, UUID player) {
        if (factionOf(snap, player) == null) {
            return ReasonCode.NOT_IN_FACTION;
        }
        Rank rank = rankOf(snap, player);
        return rank != null && rank.isOfficerOrAbove() ? null : ReasonCode.MUST_BE_OFFICER;
    }

    /**
     * {@code null} when {@code player}'s rank priority is at least {@code minPriority};
     * {@link ReasonCode#NOT_IN_FACTION} when factionless, else the rank shortfall reason
     * ({@link ReasonCode#MUST_BE_LEADER} for an owner-level threshold, otherwise
     * {@link ReasonCode#MUST_BE_OFFICER}).
     */
    public static @Nullable ReasonCode requireRankAtLeast(KernelSnapshot snap, UUID player, int minPriority) {
        if (factionOf(snap, player) == null) {
            return ReasonCode.NOT_IN_FACTION;
        }
        Rank rank = rankOf(snap, player);
        if (rank != null && rank.priority() >= minPriority) {
            return null;
        }
        return minPriority >= Rank.PRIORITY_OWNER ? ReasonCode.MUST_BE_LEADER : ReasonCode.MUST_BE_OFFICER;
    }

    // ── resolution helpers (shared by commands so the same lookups aren't re-derived) ──────

    /** {@code player}'s normal faction, or {@code null} when factionless / stale / a system zone. */
    public static @Nullable Faction factionOf(KernelSnapshot snap, UUID player) {
        int ordinal = snap.memberOrdinal(player);
        if (ordinal < 0) {
            return null;
        }
        MemberView member = snap.member(ordinal);
        if (member == null || member.factionHandle() == FactionHandle.WILDERNESS) {
            return null;
        }
        Faction faction = snap.faction(member.factionHandle());
        return (faction != null && faction.isNormal()) ? faction : null;
    }

    /** {@code player}'s rank within their faction, or {@code null} when factionless / unresolved. */
    public static @Nullable Rank rankOf(KernelSnapshot snap, UUID player) {
        int ordinal = snap.memberOrdinal(player);
        if (ordinal < 0) {
            return null;
        }
        MemberView member = snap.member(ordinal);
        if (member == null) {
            return null;
        }
        Faction faction = snap.faction(member.factionHandle());
        if (faction == null) {
            return null;
        }
        Rank[] ranks = faction.ranks();
        int rankIdx = member.rankIdx();
        return (rankIdx >= 0 && rankIdx < ranks.length) ? ranks[rankIdx] : null;
    }
}
