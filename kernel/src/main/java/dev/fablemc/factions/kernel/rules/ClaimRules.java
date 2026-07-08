package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.config.LandConfig;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.RelationKind;

/**
 * Claiming / overclaim / max-land / adjacency rules, transcribed from {@code EngineChunkChange}
 * (ref-services.md §6, ref-engines.md §3.2). Pure static functions shared by command pre-validation
 * and the reducer.
 *
 * <p><b>Owning thread(s):</b> pure static. <b>Mutability:</b> stateless. <b>Reducer rule:</b>
 * the reducer applies the claim only when {@link #validateClaim} returns a {@code null} reason,
 * and updates the atlas + reverse index + {@code landCount} together.
 *
 * <p>Max land = {@code min(maxLand, floor(totalPower/perPower))}, or {@code maxLand} when
 * {@code perPower <= 0}. Border = the 4-neighbor rule (valid iff any cardinal neighbor is
 * wilderness, own, or a non-enemy faction). Overclaim gate order: enabled → zone → own →
 * enemy-required → victim raidable ({@code land > maxLand}) → F5 offline protection → F6 shield.
 */
public final class ClaimRules {

    private ClaimRules() {
    }

    private static final int[] NEIGHBOR_DX = {1, -1, 0, 0};
    private static final int[] NEIGHBOR_DZ = {0, 0, 1, -1};

    /** Max land for a faction with {@code totalPower} (integer truncation, hard ceiling maxLand). */
    public static int computeMaxLand(LandConfig land, double totalPower) {
        double perPower = land.perPower();
        if (perPower <= 0.0) {
            return land.maxLand();
        }
        return Math.min(land.maxLand(), (int) (totalPower / perPower));
    }

    /**
     * The 4-neighbor adjacency rule. Always valid for the first claim ({@code currentLand == 0});
     * otherwise valid iff at least one cardinal neighbor is wilderness, own territory, or a
     * non-enemy faction's territory.
     */
    public static boolean isValidBorder(KernelState state, int factionHandle, int worldIdx,
                                        long chunkKey, int currentLand) {
        if (currentLand == 0) {
            return true;
        }
        int ownOrd = FactionHandle.ordinal(factionHandle);
        Faction own = state.factions().resolve(factionHandle);
        int x = ChunkKeys.x(chunkKey);
        int z = ChunkKeys.z(chunkKey);
        for (int i = 0; i < 4; i++) {
            long nk = ChunkKeys.key(x + NEIGHBOR_DX[i], z + NEIGHBOR_DZ[i]);
            int owner = state.claims().ownerAt(worldIdx, nk);
            if (owner == FactionHandle.WILDERNESS) {
                return true;
            }
            int oOrd = FactionHandle.ordinal(owner);
            if (oOrd == ownOrd) {
                return true;
            }
            byte rel = own == null ? RelationKind.NEUTRAL : own.relationEffective(oOrd);
            if (rel != RelationKind.ENEMY) {
                return true;
            }
        }
        return false;
    }

    /**
     * Buffer-zone spacing (D-1, wired but default 0 = disabled). When {@code bufferZone > 0}, a new
     * claim is rejected if any other faction owns a chunk within Chebyshev distance {@code bufferZone}.
     */
    public static boolean bufferZoneOk(KernelState state, int factionHandle, int worldIdx,
                                       long chunkKey, int bufferZone) {
        if (bufferZone <= 0) {
            return true;
        }
        int ownOrd = FactionHandle.ordinal(factionHandle);
        int x = ChunkKeys.x(chunkKey);
        int z = ChunkKeys.z(chunkKey);
        for (int dx = -bufferZone; dx <= bufferZone; dx++) {
            for (int dz = -bufferZone; dz <= bufferZone; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int owner = state.claims().ownerAt(worldIdx, ChunkKeys.key(x + dx, z + dz));
                if (owner != FactionHandle.WILDERNESS
                        && FactionHandle.ordinal(owner) != ownOrd) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The outcome of {@link #validateClaim}: a null {@code reason} means OK. */
    public record ClaimDecision(ReasonCode reason, boolean overclaim, int victimHandle) {
        /** {@code true} when the claim is permitted. */
        public boolean ok() {
            return reason == null;
        }
    }

    private static ClaimDecision reject(ReasonCode reason) {
        return new ClaimDecision(reason, false, FactionHandle.WILDERNESS);
    }

    /**
     * Validates a single-chunk claim by {@code factionHandle}, running the full overclaim gate and
     * the land / border checks. {@code currentLand} is the claiming faction's current land count.
     */
    public static ClaimDecision validateClaim(KernelState state, int factionHandle, int worldIdx,
                                              long chunkKey, int currentLand, int tick,
                                              long epochMillis) {
        LandConfig land = state.config().land();
        int ownOrd = FactionHandle.ordinal(factionHandle);
        int maxLand = computeMaxLand(land, factionTotalPower(state, factionHandle, tick, epochMillis));

        int existing = state.claims().ownerAt(worldIdx, chunkKey);
        boolean overclaim = false;
        int victim = FactionHandle.WILDERNESS;

        if (existing != FactionHandle.WILDERNESS) {
            int exOrd = FactionHandle.ordinal(existing);
            if (exOrd == FactionHandle.SAFEZONE_ORDINAL || exOrd == FactionHandle.WARZONE_ORDINAL) {
                return reject(ReasonCode.CANNOT_CLAIM_SAFEZONE);
            }
            if (exOrd == ownOrd) {
                return reject(ReasonCode.ALREADY_CLAIMED);
            }
            if (!land.overclaimingEnabled()) {
                return reject(ReasonCode.ALREADY_CLAIMED);
            }
            Faction own = state.factions().resolve(factionHandle);
            byte rel = own == null ? RelationKind.NEUTRAL : own.relationEffective(exOrd);
            if (land.overclaimRequireEnemyRelation() && rel != RelationKind.ENEMY) {
                // No dedicated "must be at war" reason exists; the closest is "already claimed".
                return reject(ReasonCode.ALREADY_CLAIMED);
            }
            Faction victimF = state.factions().resolve(existing);
            if (victimF != null) {
                double vPower = FactionAggregates.totalPower(state, victimF, tick, epochMillis);
                int vMax = computeMaxLand(land, vPower);
                if (victimF.landCount() <= vMax) {
                    return reject(ReasonCode.ENEMY_NOT_RAIDABLE);
                }
                if (land.offlineProtectionEnabled()
                        && !FactionAggregates.anyMemberOnline(state, existing)) {
                    return reject(ReasonCode.ENEMY_OFFLINE_PROTECTED);
                }
                if (land.warShieldEnabled() && ShieldWindow.isActive(victimF, epochMillis)) {
                    return reject(ReasonCode.SHIELD_ACTIVE);
                }
            }
            overclaim = true;
            victim = existing;
        }

        if (currentLand >= maxLand) {
            return reject(ReasonCode.NOT_ENOUGH_POWER);
        }
        if (!overclaim) {
            if (!isValidBorder(state, factionHandle, worldIdx, chunkKey, currentLand)) {
                return reject(ReasonCode.NO_BORDER);
            }
            if (!bufferZoneOk(state, factionHandle, worldIdx, chunkKey, land.bufferZone())) {
                return reject(ReasonCode.NO_BORDER);
            }
        }
        return new ClaimDecision(null, overclaim, victim);
    }

    /** Total power for a faction handle (resolving the faction first). */
    public static double factionTotalPower(KernelState state, int factionHandle, int tick,
                                           long epochMillis) {
        Faction f = state.factions().resolve(factionHandle);
        return FactionAggregates.totalPower(state, f, tick, epochMillis);
    }

    /** Validates an unclaim: the chunk must be owned by {@code factionHandle}. */
    public static ReasonCode validateUnclaim(KernelState state, int factionHandle, int worldIdx,
                                             long chunkKey) {
        int owner = state.claims().ownerAt(worldIdx, chunkKey);
        if (owner == FactionHandle.WILDERNESS) {
            return ReasonCode.NOT_YOUR_LAND;
        }
        if (FactionHandle.ordinal(owner) != FactionHandle.ordinal(factionHandle)) {
            return ReasonCode.NOT_YOUR_LAND;
        }
        return null;
    }
}
