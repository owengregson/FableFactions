package dev.fablemc.factions.core.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;

import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.Warp;
import dev.fablemc.factions.platform.resolve.Players;

/**
 * Snapshot-backed tab-completion sources (CONTRACTS §4): online player names, faction names, a
 * faction's member / warp / chest / role names — the value sets commands return from
 * {@link CommandNode#complete}. Every source is a plain prefix-filtered index scan (no streams, no
 * boxing) so completion stays cheap on the main thread.
 *
 * <p><b>Owning thread(s):</b> called from tab-completion on the sender's region/main thread (the
 * player source reads live server state; the rest read the immutable snapshot). <b>Mutability:</b>
 * stateless.
 */
public final class Completions {

    private Completions() {
    }

    /** Online player names starting with {@code prefix} (case-insensitive). */
    public static List<String> onlinePlayers(String prefix) {
        List<String> out = new ArrayList<>();
        for (Player player : Players.online()) {
            String name = player.getName();
            if (matches(name, prefix)) {
                out.add(name);
            }
        }
        return out;
    }

    /** Names of all normal factions starting with {@code prefix} (case-insensitive). */
    public static List<String> factionNames(KernelSnapshot snap, String prefix) {
        List<String> out = new ArrayList<>();
        FactionArena arena = snap.state().factions();
        int highWater = arena.highWater();
        for (int ordinal = FactionHandle.FIRST_NORMAL_ORDINAL; ordinal < highWater; ordinal++) {
            Faction faction = arena.at(ordinal);
            if (faction != null && faction.isNormal() && matches(faction.name(), prefix)) {
                out.add(faction.name());
            }
        }
        return out;
    }

    /** Last-seen names of {@code factionHandle}'s members starting with {@code prefix}. */
    public static List<String> memberNames(KernelSnapshot snap, int factionHandle, String prefix) {
        List<String> out = new ArrayList<>();
        Faction faction = snap.faction(factionHandle);
        if (faction == null) {
            return out;
        }
        int factionOrdinal = faction.idx();
        PlayerLedger ledger = snap.state().ledger();
        int highWater = ledger.highWater();
        for (int ordinal = 0; ordinal < highWater; ordinal++) {
            if (!ledger.has(ordinal)) {
                continue;
            }
            if (FactionHandle.ordinal(ledger.factionHandle(ordinal)) != factionOrdinal) {
                continue;
            }
            String name = ledger.nameLast(ordinal);
            if (name != null && matches(name, prefix)) {
                out.add(name);
            }
        }
        return out;
    }

    /** Names of {@code factionHandle}'s warps starting with {@code prefix}. */
    public static List<String> warpNames(KernelSnapshot snap, int factionHandle, String prefix) {
        List<String> out = new ArrayList<>();
        Faction faction = snap.faction(factionHandle);
        if (faction == null) {
            return out;
        }
        Warp[] warps = snap.state().warps().forFaction(faction.idx());
        for (int i = 0; i < warps.length; i++) {
            String name = warps[i].name();
            if (matches(name, prefix)) {
                out.add(name);
            }
        }
        return out;
    }

    /** Names of {@code factionHandle}'s team chests starting with {@code prefix}. */
    public static List<String> chestNames(KernelSnapshot snap, int factionHandle, String prefix) {
        List<String> out = new ArrayList<>();
        Faction faction = snap.faction(factionHandle);
        if (faction == null) {
            return out;
        }
        ChestRef[] chests = snap.state().chests().forFaction(faction.idx());
        for (int i = 0; i < chests.length; i++) {
            String name = chests[i].name();
            if (matches(name, prefix)) {
                out.add(name);
            }
        }
        return out;
    }

    /** Names of {@code factionHandle}'s ranks (roles) starting with {@code prefix}. */
    public static List<String> roleNames(KernelSnapshot snap, int factionHandle, String prefix) {
        List<String> out = new ArrayList<>();
        Faction faction = snap.faction(factionHandle);
        if (faction == null) {
            return out;
        }
        Rank[] ranks = faction.ranks();
        for (int i = 0; i < ranks.length; i++) {
            String name = ranks[i].name();
            if (matches(name, prefix)) {
                out.add(name);
            }
        }
        return out;
    }

    /** The subset of {@code candidates} starting with {@code prefix} (case-insensitive). */
    public static List<String> matching(String[] candidates, String prefix) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < candidates.length; i++) {
            if (matches(candidates[i], prefix)) {
                out.add(candidates[i]);
            }
        }
        return out;
    }

    private static boolean matches(String candidate, String prefix) {
        return candidate != null && candidate.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
