package dev.fablemc.factions.core.command.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.vocab.ClaimMode;

/** Geometry pins for {@link ShapeCollectors} — square / circle counts, membership, cap and clamp. */
final class ShapeCollectorsTest {

    @Test
    void squareCountsAreTheOddSquare() {
        assertEquals(9, ShapeCollectors.square(0, 0, 1, 999).length);
        assertEquals(25, ShapeCollectors.square(0, 0, 2, 999).length);
        assertEquals(49, ShapeCollectors.square(0, 0, 3, 999).length);
    }

    @Test
    void squareContainsCornersAndCentre() {
        Set<Long> keys = toSet(ShapeCollectors.square(5, -3, 1, 999));
        assertTrue(keys.contains(ChunkKeys.key(5, -3)));   // centre
        assertTrue(keys.contains(ChunkKeys.key(6, -2)));   // corner
        assertTrue(keys.contains(ChunkKeys.key(4, -4)));   // opposite corner
        assertEquals(9, keys.size());
    }

    @Test
    void circleIsTheEuclideanDisc() {
        assertEquals(5, ShapeCollectors.circle(0, 0, 1, 999).length);   // centre + 4 orthogonal
        assertEquals(13, ShapeCollectors.circle(0, 0, 2, 999).length);  // no (±2,±1) corners
    }

    @Test
    void circleExcludesDiagonalOutsideRadius() {
        Set<Long> keys = toSet(ShapeCollectors.circle(0, 0, 1, 999));
        assertTrue(keys.contains(ChunkKeys.key(1, 0)));
        assertFalse(keys.contains(ChunkKeys.key(1, 1)));   // dx²+dz² = 2 > 1²
    }

    @Test
    void maxCapsTheOutput() {
        assertEquals(4, ShapeCollectors.square(0, 0, 5, 4).length);
        assertEquals(3, ShapeCollectors.circle(0, 0, 5, 3).length);
        assertEquals(0, ShapeCollectors.square(0, 0, 3, 0).length);
    }

    @Test
    void radiusClampsIntoRange() {
        assertEquals(ShapeCollectors.MIN_RADIUS, ShapeCollectors.clampRadius(0));
        assertEquals(ShapeCollectors.MIN_RADIUS, ShapeCollectors.clampRadius(-7));
        assertEquals(ShapeCollectors.MAX_RADIUS, ShapeCollectors.clampRadius(500));
        assertEquals(50, ShapeCollectors.clampRadius(50));
        // A radius of 0 collapses the circle to the radius-1 disc (5 keys).
        assertEquals(5, ShapeCollectors.circle(0, 0, 0, 999).length);
    }

    @Test
    void collectDispatchesByMode() {
        assertEquals(1, ShapeCollectors.collect(ClaimMode.SINGLE, 0, 0, 3, 999).length);
        assertEquals(1, ShapeCollectors.collect(ClaimMode.AT, 0, 0, 3, 999).length);
        assertEquals(9, ShapeCollectors.collect(ClaimMode.FILL, 0, 0, 3, 999).length);   // radius-1 square
        assertEquals(25, ShapeCollectors.collect(ClaimMode.SQUARE, 0, 0, 2, 999).length);
        assertEquals(9, ShapeCollectors.collect(ClaimMode.NEARBY, 0, 0, 1, 999).length);
        assertEquals(13, ShapeCollectors.collect(ClaimMode.CIRCLE, 0, 0, 2, 999).length);
    }

    private static Set<Long> toSet(long[] keys) {
        Set<Long> out = new HashSet<>();
        for (long key : keys) {
            out.add(key);
        }
        return out;
    }
}
