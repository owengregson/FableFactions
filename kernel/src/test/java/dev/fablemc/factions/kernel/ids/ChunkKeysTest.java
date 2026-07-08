package dev.fablemc.factions.kernel.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Round-trip and region/block derivation for {@link ChunkKeys}, including negative coordinates.
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b>
 * test-confined fixtures; no shared state between tests.
 */
class ChunkKeysTest {

    @Test
    void keyRoundTripsIncludingNegatives() {
        int[] coords = {0, 1, -1, 7, -7, 31, -31, 32, -32, 100, -100,
                Integer.MAX_VALUE, Integer.MIN_VALUE, 1234567, -1234567};
        for (int x : coords) {
            for (int z : coords) {
                long key = ChunkKeys.key(x, z);
                assertEquals(x, ChunkKeys.x(key), "x round-trip for (" + x + "," + z + ")");
                assertEquals(z, ChunkKeys.z(key), "z round-trip for (" + x + "," + z + ")");
            }
        }
    }

    @Test
    void distinctCoordsProduceDistinctKeys() {
        assertEquals(ChunkKeys.key(-1, -1), ChunkKeys.key(-1, -1));
        // (-1,-1) must not collide with (-1, anything-else) or (other, -1).
        long a = ChunkKeys.key(-1, -1);
        long b = ChunkKeys.key(-1, 0);
        long c = ChunkKeys.key(0, -1);
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
        org.junit.jupiter.api.Assertions.assertNotEquals(a, c);
        org.junit.jupiter.api.Assertions.assertNotEquals(b, c);
    }

    @Test
    void regionKeyUsesArithmeticShiftFloor() {
        // Chunk (-1,-1) lives in region (-1,-1) because -1 >> 5 == -1 (floor division).
        long region = ChunkKeys.regionKey(ChunkKeys.key(-1, -1));
        assertEquals(-1, ChunkKeys.x(region));
        assertEquals(-1, ChunkKeys.z(region));

        // Chunks (0..31) map to region 0; (32..63) to region 1; (-32..-1) to region -1.
        assertEquals(0, ChunkKeys.x(ChunkKeys.regionKey(ChunkKeys.key(31, 0))));
        assertEquals(1, ChunkKeys.x(ChunkKeys.regionKey(ChunkKeys.key(32, 0))));
        assertEquals(-1, ChunkKeys.x(ChunkKeys.regionKey(ChunkKeys.key(-32, 0))));
        assertEquals(-2, ChunkKeys.x(ChunkKeys.regionKey(ChunkKeys.key(-33, 0))));
    }

    @Test
    void fromBlockDividesBySixteen() {
        assertEquals(ChunkKeys.key(0, 0), ChunkKeys.fromBlock(15, 15));
        assertEquals(ChunkKeys.key(1, 1), ChunkKeys.fromBlock(16, 16));
        assertEquals(ChunkKeys.key(-1, -1), ChunkKeys.fromBlock(-1, -1));
        assertEquals(ChunkKeys.key(-1, -1), ChunkKeys.fromBlock(-16, -16));
        assertEquals(ChunkKeys.key(-2, -2), ChunkKeys.fromBlock(-17, -17));
    }
}
