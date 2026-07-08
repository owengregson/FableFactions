package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.config.RelationConfig;
import dev.fablemc.factions.kernel.state.RelationKind;

/**
 * Relation state-machine rules (ref-commands-admin.md §6.3, ref-services.md §7.1). Pure static
 * helpers shared by the command layer and the reducer.
 *
 * <p><b>Owning thread(s):</b> pure static. <b>Mutability:</b> stateless. <b>Reducer rule:</b>
 * the reducer records the outgoing wish, mirrors ENEMY/NEUTRAL instantly, and recomputes the
 * symmetric effective edge for the pair — so {@code relationBetween(a,b) == relationBetween(b,a)}
 * always holds (the symmetry property test pins it).
 *
 * <p>Limits apply only to ALLY/TRUCE outgoing wishes and only when moving to a new value; ENEMY
 * and NEUTRAL are unlimited and mirror bilaterally.
 */
public final class RelationRules {

    private RelationRules() {
    }

    /** {@code true} when {@code kind} is a valid faction↔faction relation (not MEMBER). */
    public static boolean isValidFactionRelation(int kind) {
        return kind == RelationKind.ALLY || kind == RelationKind.TRUCE
                || kind == RelationKind.NEUTRAL || kind == RelationKind.ENEMY;
    }

    /** {@code true} when {@code kind} is mirrored bilaterally and instantly (ENEMY / NEUTRAL). */
    public static boolean isBilateral(int kind) {
        return kind == RelationKind.ENEMY || kind == RelationKind.NEUTRAL;
    }

    /**
     * The symmetric effective relation for a pair from their two outgoing wishes. ENEMY when
     * either wishes ENEMY; ALLY/TRUCE only when both wish the same; NEUTRAL otherwise.
     */
    public static byte effectiveKind(byte wishAB, byte wishBA) {
        if (wishAB == RelationKind.ENEMY || wishBA == RelationKind.ENEMY) {
            return RelationKind.ENEMY;
        }
        if (wishAB == RelationKind.ALLY && wishBA == RelationKind.ALLY) {
            return RelationKind.ALLY;
        }
        if (wishAB == RelationKind.TRUCE && wishBA == RelationKind.TRUCE) {
            return RelationKind.TRUCE;
        }
        return RelationKind.NEUTRAL;
    }

    /** Count of outgoing-wish edges of {@code kind} in a faction's sorted relation arrays. */
    public static int countKind(byte[] kinds, int len, byte kind) {
        int n = 0;
        for (int i = 0; i < len; i++) {
            if (kinds[i] == kind) {
                n++;
            }
        }
        return n;
    }

    /**
     * {@code true} when setting {@code newKind} toward one target is within the ally/truce limit,
     * given the faction's current outgoing wishes and the {@code previousKind} toward that target.
     */
    public static boolean withinRelationLimit(RelationConfig config, byte[] outKinds, int len,
                                              byte previousKind, byte newKind) {
        if (newKind != RelationKind.ALLY && newKind != RelationKind.TRUCE) {
            return true;
        }
        if (previousKind == newKind) {
            return true;
        }
        int count = countKind(outKinds, len, newKind);
        int max = newKind == RelationKind.ALLY ? config.maxAllies() : config.maxTruces();
        return count < Math.max(0, max);
    }
}
