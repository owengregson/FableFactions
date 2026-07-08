package dev.fablemc.factions.core.integration.placeholderapi;

import java.util.UUID;

import dev.fablemc.factions.kernel.rules.FactionAggregates;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;
import dev.fablemc.factions.kernel.state.Rank;

/**
 * Resolves the FableFactions placeholder set as pure, zero-IO snapshot reads (ref-integrations §3.2,
 * CONTRACTS §7.7). FableFactions ships these under the {@code %fable_<param>%} identifier ONLY — no
 * compatibility aliases. This class is the provider half of the PlaceholderAPI hook; the reflective
 * expansion registration is deferred (see {@link PlaceholderHook}), but the data path is complete
 * and self-contained (Bukkit-free, unit-testable).
 *
 * <p><b>Owning thread(s):</b> PlaceholderAPI resolves on whatever thread requests a placeholder;
 * every read here is a wait-free snapshot read (proposal-C §3.3). <b>Mutability:</b> stateless.
 */
public final class PlaceholderData {

    private PlaceholderData() {
    }

    /**
     * Resolves {@code param} (the text after {@code fable_}) for {@code playerId} against
     * {@code snapshot}, mirroring the reference table; returns {@code null} for an unhandled param
     * (PlaceholderAPI treats {@code null} as "not mine").
     */
    public static String resolve(KernelSnapshot snapshot, UUID playerId, String param) {
        int ordinal = snapshot.memberOrdinal(playerId);
        MemberView member = ordinal >= 0 ? snapshot.member(ordinal) : null;
        Faction faction = member != null ? snapshot.faction(member.factionHandle()) : null;
        return switch (param) {
            case "faction_name" -> faction != null ? faction.name() : "None";
            case "faction_power" -> faction != null ? Integer.toString((int) faction.powerCacheSum()) : "0";
            case "faction_members" -> faction != null
                    ? Integer.toString(FactionAggregates.memberCount(snapshot.state(), faction.idx())) : "0";
            case "faction_land" -> faction != null ? Integer.toString(faction.landCount()) : "0";
            case "faction_bank" -> faction != null ? Double.toString(faction.bank()) : "0.0";
            case "player_power" -> member != null ? Double.toString(member.powerBase()) : "0";
            case "player_role" -> role(faction, member) != null ? role(faction, member).name() : "None";
            case "player_role_prefix" -> prefix(faction, member);
            default -> null;
        };
    }

    private static Rank role(Faction faction, MemberView member) {
        if (faction == null || member == null) {
            return null;
        }
        Rank[] ranks = faction.ranks();
        int idx = member.rankIdx();
        return idx >= 0 && idx < ranks.length ? ranks[idx] : null;
    }

    private static String prefix(Faction faction, MemberView member) {
        Rank rank = role(faction, member);
        if (rank == null || rank.prefix() == null) {
            return "";
        }
        return rank.prefix();
    }
}
