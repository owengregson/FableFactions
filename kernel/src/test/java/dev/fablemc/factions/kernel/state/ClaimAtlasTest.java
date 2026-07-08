package dev.fablemc.factions.kernel.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;

/**
 * Put/get/remove/iterate at 100k scale plus copy-one-region COW isolation for {@link ClaimAtlas}.
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b>
 * test-confined fixtures; no shared state between tests.
 */
class ClaimAtlasTest {

    private static final int WORLD = 0;

    @Test
    void bulkBuildGetIterateAndRemoveAtHundredKScale() {
        final int side = 317; // 317*317 = 100,489 claims
        ClaimAtlas.Builder b = new ClaimAtlas.Builder();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                int owner = FactionHandle.handle(0, FactionHandle.FIRST_NORMAL_ORDINAL + ((x + z) % 7));
                b.put(WORLD, ChunkKeys.key(x, z), owner);
            }
        }
        ClaimAtlas atlas = b.build();
        int expected = side * side;
        assertEquals(expected, atlas.size(), "all claims present");

        // Spot-check ownerAt across the grid.
        for (int x = 0; x < side; x += 37) {
            for (int z = 0; z < side; z += 41) {
                int owner = FactionHandle.handle(0, FactionHandle.FIRST_NORMAL_ORDINAL + ((x + z) % 7));
                assertEquals(owner, atlas.ownerAt(WORLD, ChunkKeys.key(x, z)));
            }
        }
        // Unclaimed chunk reads wilderness.
        assertEquals(FactionHandle.WILDERNESS, atlas.ownerAt(WORLD, ChunkKeys.key(side + 5, side + 5)));
        assertEquals(FactionHandle.WILDERNESS, atlas.ownerAt(99, ChunkKeys.key(0, 0)));

        // Iterate: exact count via forEachClaim.
        int[] counted = {0};
        atlas.forEachClaim((w, key, owner) -> counted[0]++);
        assertEquals(expected, counted[0], "iteration visits every claim exactly once");

        // Remove a diagonal sample and verify shrink + absence.
        ClaimAtlas shrunk = atlas;
        int removed = 0;
        for (int i = 0; i < side; i++) {
            shrunk = shrunk.withoutClaim(WORLD, ChunkKeys.key(i, i));
            removed++;
        }
        assertEquals(expected - removed, shrunk.size());
        for (int i = 0; i < side; i += 29) {
            assertEquals(FactionHandle.WILDERNESS, shrunk.ownerAt(WORLD, ChunkKeys.key(i, i)));
        }
        // The full atlas is unchanged by the removals (COW).
        assertEquals(expected, atlas.size());
        assertEquals(FactionHandle.handle(0, FactionHandle.FIRST_NORMAL_ORDINAL),
                atlas.ownerAt(WORLD, ChunkKeys.key(0, 0)));
    }

    @Test
    void iterateMatchesInsertedSetExactly() {
        Map<Long, Integer> expected = new HashMap<>();
        ClaimAtlas.Builder b = new ClaimAtlas.Builder();
        for (int i = 0; i < 200; i++) {
            long key = ChunkKeys.key(i * 3 - 300, i * 5 - 500);
            int owner = FactionHandle.handle(1, 2 + (i % 3));
            expected.put(key, owner);
            b.put(WORLD, key, owner);
        }
        ClaimAtlas atlas = b.build();
        Set<Long> seen = new HashSet<>();
        atlas.forEachClaim((w, key, owner) -> {
            assertEquals(WORLD, w);
            assertEquals(expected.get(key).intValue(), owner, "owner for key " + key);
            seen.add(key);
        });
        assertEquals(expected.keySet(), seen);
    }

    @Test
    void withClaimIsolatesTheOldSnapshotAndOtherRegions() {
        long inRegionA = ChunkKeys.key(10, 10);   // region (0,0)
        long alsoRegionA = ChunkKeys.key(11, 12);  // region (0,0)
        long inRegionB = ChunkKeys.key(100, 100);  // region (3,3)
        int fx = FactionHandle.handle(0, 2);
        int fy = FactionHandle.handle(0, 3);

        ClaimAtlas base = new ClaimAtlas.Builder().put(WORLD, alsoRegionA, fx).build();

        ClaimAtlas a1 = base.withClaim(WORLD, inRegionA, fx);
        // The old snapshot never gained the new claim.
        assertEquals(FactionHandle.WILDERNESS, base.ownerAt(WORLD, inRegionA));
        assertEquals(fx, a1.ownerAt(WORLD, inRegionA));
        // The pre-existing claim in the same region is retained by the new atlas.
        assertEquals(fx, a1.ownerAt(WORLD, alsoRegionA));

        // Mutating a different region leaves a1's first-region claim intact.
        ClaimAtlas a2 = a1.withClaim(WORLD, inRegionB, fy);
        assertEquals(FactionHandle.WILDERNESS, a1.ownerAt(WORLD, inRegionB), "a1 unchanged by a2");
        assertEquals(fy, a2.ownerAt(WORLD, inRegionB));
        assertEquals(fx, a2.ownerAt(WORLD, inRegionA));

        // Removing from a2 does not affect a1.
        ClaimAtlas a3 = a2.withoutClaim(WORLD, inRegionA);
        assertEquals(FactionHandle.WILDERNESS, a3.ownerAt(WORLD, inRegionA));
        assertEquals(fx, a2.ownerAt(WORLD, inRegionA), "a2 unaffected by a3 removal");
        assertEquals(fx, a1.ownerAt(WORLD, inRegionA), "a1 unaffected by a3 removal");
    }

    @Test
    void overwriteReplacesOwnerWithoutChangingSize() {
        long key = ChunkKeys.key(5, 6);
        int fx = FactionHandle.handle(0, 2);
        int fy = FactionHandle.handle(3, 9);
        ClaimAtlas a = ClaimAtlas.empty().withClaim(WORLD, key, fx);
        assertEquals(1, a.size());
        ClaimAtlas b = a.withClaim(WORLD, key, fy);
        assertEquals(1, b.size(), "overclaim replaces, does not grow");
        assertEquals(fy, b.ownerAt(WORLD, key));
        assertEquals(fx, a.ownerAt(WORLD, key), "old atlas keeps prior owner");
    }
}
