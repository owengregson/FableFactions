package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.RelationKind;

/**
 * The protection decision engine — one static, pure, zero-allocation entry point
 * (proposal-C §5c, pvp-engines.md §3.1). Every build / interact / pvp / explosion / fire decision
 * routes through {@link #decide}.
 *
 * <p><b>Owning thread(s):</b> any reader thread, on whatever region/main thread the event fires.
 * <b>Mutability:</b> stateless — reads an immutable snapshot. <b>Reducer rule:</b> n/a — this is
 * the read side; it never mutates state.
 *
 * <p>Short-circuit order (first match wins): bypass bit → overriding bit → atlas owner lookup
 * (wilderness allows) → zone tables (safezone/warzone toggles) → owner faction flag bits
 * (PVP/EXPLOSIONS/FIRE_SPREAD) → own-faction → effective relation vs. action. Every input is an
 * {@code int}/{@code long}/{@code byte}; the single concrete {@link KernelSnapshot} class keeps
 * the whole method monomorphic and inlinable, allocating nothing.
 */
public final class Verdicts {

    private Verdicts() {
    }

    /** Decides whether {@code action} by the packed actor is allowed at {@code (worldIdx, chunkKey)}. */
    public static int decide(KernelSnapshot snap, long actorBits, int worldIdx, long chunkKey,
                             int action) {
        if (ActorBits.bypass(actorBits) || ActorBits.overriding(actorBits)) {
            return Verdict.ALLOW;
        }
        int owner = snap.claimOwnerAt(worldIdx, chunkKey);
        if (owner == FactionHandle.WILDERNESS) {
            return Verdict.ALLOW;
        }
        ConfigImage cfg = snap.config();
        int ownerOrd = FactionHandle.ordinal(owner);

        if (ownerOrd == FactionHandle.SAFEZONE_ORDINAL) {
            if (!cfg.zones().safeZoneEnabled()) {
                return Verdict.ALLOW;
            }
            return zoneVerdict(action, Verdict.DENY_SAFEZONE);
        }
        if (ownerOrd == FactionHandle.WARZONE_ORDINAL) {
            if (!cfg.zones().warZoneEnabled()) {
                return Verdict.ALLOW;
            }
            return warzoneVerdict(action);
        }

        Faction ownerF = snap.faction(owner);
        if (ownerF == null) {
            // Stale handle / disbanded (scrub should have cleared it): treat as wilderness.
            return Verdict.ALLOW;
        }

        switch (action) {
            case Action.EXPLOSION:
                return ownerF.flag(Faction.FLAG_EXPLOSIONS,
                        cfg.flagDefaults().defaultOf(Faction.FLAG_EXPLOSIONS))
                        ? Verdict.ALLOW : Verdict.DENY_EXPLOSIONS;
            case Action.FIRE_SPREAD:
                return ownerF.flag(Faction.FLAG_FIRE_SPREAD,
                        cfg.flagDefaults().defaultOf(Faction.FLAG_FIRE_SPREAD))
                        ? Verdict.ALLOW : Verdict.DENY_FIRE;
            case Action.PVP:
                return ownerF.flag(Faction.FLAG_PVP,
                        cfg.flagDefaults().defaultOf(Faction.FLAG_PVP))
                        ? Verdict.ALLOW : Verdict.DENY_PVP_FLAG;
            default:
                return buildLikeVerdict(snap, actorBits, owner, ownerOrd);
        }
    }

    private static int buildLikeVerdict(KernelSnapshot snap, long actorBits, int owner,
                                        int ownerOrd) {
        int actorHandle = ActorBits.factionHandle(actorBits);
        if (actorHandle != FactionHandle.WILDERNESS
                && FactionHandle.ordinal(actorHandle) == ownerOrd) {
            return Verdict.ALLOW; // own territory
        }
        byte rel = snap.relationBetween(actorHandle, owner);
        if (rel == RelationKind.ALLY || rel == RelationKind.MEMBER) {
            return Verdict.ALLOW; // allies may build (reference)
        }
        switch (rel) {
            case RelationKind.ENEMY:
                return Verdict.DENY_ENEMY;
            case RelationKind.TRUCE:
                return Verdict.DENY_TRUCE;
            case RelationKind.NEUTRAL:
            default:
                return Verdict.DENY_NEUTRAL;
        }
    }

    /** Safezone verdict: build-like and pvp denied ({@code denyBuild}); explosions protected; fire allowed. */
    private static int zoneVerdict(int action, int denyBuild) {
        switch (action) {
            case Action.PVP:
                return denyBuild;
            case Action.EXPLOSION:
                return Verdict.DENY_EXPLOSIONS;
            case Action.FIRE_SPREAD:
                return Verdict.ALLOW;
            default:
                return denyBuild;
        }
    }

    /** Warzone verdict: build-like denied; pvp ALWAYS allowed; explosions protected; fire allowed. */
    private static int warzoneVerdict(int action) {
        switch (action) {
            case Action.PVP:
                return Verdict.ALLOW;
            case Action.EXPLOSION:
                return Verdict.DENY_EXPLOSIONS;
            case Action.FIRE_SPREAD:
                return Verdict.ALLOW;
            default:
                return Verdict.DENY_WARZONE;
        }
    }
}
