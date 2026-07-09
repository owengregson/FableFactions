package dev.fablemc.factions.core.listen;

import java.util.UUID;

import org.bukkit.entity.Player;

import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.rules.Action;
import dev.fablemc.factions.kernel.rules.ActorBits;
import dev.fablemc.factions.kernel.rules.Verdict;
import dev.fablemc.factions.kernel.rules.Verdicts;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.PlayerLedger;

/**
 * The one place a protection verdict's actor facts are resolved from the snapshot (proposal-C
 * §8.2, work order: "ActorBits pre-resolved ONCE per event"). Every listener packs its actor
 * exactly once through {@link #playerActorBits} / {@link #environmentActorBits}, then routes the
 * decision through {@link Verdicts#decide} — the single protection entry point.
 *
 * <p><b>Owning thread(s):</b> called on whatever region/main thread the event fires (reads an
 * immutable snapshot). <b>Mutability:</b> static-only, stateless, zero-allocation on the hot path
 * (primitive {@code long}/{@code int} throughout; the snapshot reads never box).
 */
final class ProtectionSupport {

    /** The permission node that bypasses build and combat protection (ref-engines.md §6). */
    static final String BYPASS_PERMISSION = "factions.bypass";

    private ProtectionSupport() {
    }

    /**
     * Packs {@code player}'s protection facts once: member ordinal, faction handle, the cached
     * {@code factions.bypass} permission bit, the admin {@code overriding} preference, and the
     * player flag. A non-member resolves to a factionless, wilderness-handle actor.
     */
    static long playerActorBits(KernelSnapshot snap, Player player) {
        UUID id = player.getUniqueId();
        boolean bypass = player.hasPermission(BYPASS_PERMISSION);
        int ord = snap.memberOrdinal(id);
        if (ord < 0) {
            return ActorBits.of(-1, FactionHandle.WILDERNESS, bypass, false, true);
        }
        PlayerLedger ledger = snap.state().ledger();
        int handle = ledger.factionHandle(ord);
        boolean overriding = PlayerLedger.pref(ledger.prefsBits(ord), PlayerLedger.PREF_OVERRIDING);
        return ActorBits.of(ord, handle, bypass, overriding, true);
    }

    /**
     * Packs a non-player environmental "actor" as the SOURCE faction of a cross-chunk grief (liquid
     * flow, piston push, entity block-change): no bypass, no override, not a player, faction handle
     * = the source chunk's owner. {@link Verdicts#decide} then allows flow within one owner or
     * between allies and denies it into a foreign claim — modelling border grief through the single
     * verdict engine (work order: "ALL verdicts through kernel Verdicts.decide").
     */
    static long environmentActorBits(int sourceOwnerHandle) {
        return ActorBits.of(-1, sourceOwnerHandle, false, false, false);
    }

    /**
     * Whether an UNATTRIBUTABLE explosion may harm a player standing at {@code (worldIdx, chunkKey)}
     * — the pure location PvP verdict with no attacker: {@link Verdict#ALLOW} in wilderness, a
     * warzone and pvp-enabled land; a denial in a safezone or a pvp-disabled claim. Environmental
     * blast sources (end crystal, bed / respawn-anchor, dispenser-ignited TNT) resolve to no player
     * attacker, so {@link Verdicts#decide} for {@link Action#PVP} — which is location-only save for
     * the actor's bypass bit — gates the victim's protection on their location alone (ref-engines.md
     * §3.1.3: safezones and pvp-off land are safe from environmental explosions too).
     */
    static boolean environmentalPvpAllowed(KernelSnapshot snap, int worldIdx, long chunkKey) {
        long actor = environmentActorBits(FactionHandle.WILDERNESS);
        return Verdict.allowed(Verdicts.decide(snap, actor, worldIdx, chunkKey, Action.PVP));
    }

    /**
     * The combined combat verdict for {@code attacker} striking {@code victim}: the bypass short
     * circuit, the everywhere-applies friendly-fire flag check (returns {@link Verdict#DENY_FRIENDLY_FIRE}),
     * then the territory PvP decision from {@link Verdicts#decide} at the victim's chunk
     * (ref-engines.md §3.1.3). {@code attackerBits} must be {@link #playerActorBits} for the attacker.
     */
    static int combatVerdict(KernelSnapshot snap, long attackerBits, Player attacker, Player victim,
                             int victimWorldIdx, long victimChunkKey) {
        if (ActorBits.bypass(attackerBits)) {
            return Verdict.ALLOW;
        }
        int friendly = friendlyFireVerdict(snap, attacker, victim);
        if (friendly != Verdict.ALLOW) {
            return friendly;
        }
        return Verdicts.decide(snap, attackerBits, victimWorldIdx, victimChunkKey, Action.PVP);
    }

    /**
     * {@link Verdict#DENY_FRIENDLY_FIRE} when {@code attacker} and {@code victim} share a faction
     * whose {@code FRIENDLY_FIRE} flag is off (applies everywhere, even wilderness); otherwise
     * {@link Verdict#ALLOW}.
     */
    private static int friendlyFireVerdict(KernelSnapshot snap, Player attacker, Player victim) {
        int attOrd = snap.memberOrdinal(attacker.getUniqueId());
        int vicOrd = snap.memberOrdinal(victim.getUniqueId());
        if (attOrd < 0 || vicOrd < 0) {
            return Verdict.ALLOW;
        }
        PlayerLedger ledger = snap.state().ledger();
        int attHandle = ledger.factionHandle(attOrd);
        int vicHandle = ledger.factionHandle(vicOrd);
        if (attHandle == FactionHandle.WILDERNESS
                || FactionHandle.ordinal(attHandle) != FactionHandle.ordinal(vicHandle)) {
            return Verdict.ALLOW;
        }
        Faction faction = snap.faction(attHandle);
        if (faction == null) {
            return Verdict.ALLOW;
        }
        boolean allowed = faction.flag(Faction.FLAG_FRIENDLY_FIRE,
                snap.config().flagDefaults().defaultOf(Faction.FLAG_FRIENDLY_FIRE));
        return allowed ? Verdict.ALLOW : Verdict.DENY_FRIENDLY_FIRE;
    }
}
