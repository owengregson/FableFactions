package dev.fablemc.factions.kernel.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.ids.FactionHandle;

/**
 * Allocation, free-list reuse, and — the load-bearing case — generation-mismatch scrubbing.
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b>
 * test-confined fixtures; no shared state between tests.
 */
class FactionArenaTest {

    @Test
    void allocateResolveAndFreeListReuse() {
        FactionArena arena = FactionArena.empty();
        int ord = arena.nextFreeOrdinal();
        assertEquals(FactionHandle.FIRST_NORMAL_ORDINAL, ord, "first normal ordinal is 2");

        Faction f = FactionTestSupport.faction(ord, "Alpha");
        int handle = arena.handleOf(ord);
        arena = arena.withFaction(ord, f);
        assertSame(f, arena.resolve(handle));
        assertEquals(1, arena.liveCount());
    }

    @Test
    void staleHandleFailsAfterReincarnationAtSameOrdinal() {
        FactionArena arena = FactionArena.empty();

        int ord = arena.nextFreeOrdinal();
        int handleA = arena.handleOf(ord);
        Faction a = FactionTestSupport.faction(ord, "Alpha");
        arena = arena.withFaction(ord, a);
        assertSame(a, arena.resolve(handleA));

        // Free the slot: generation bumps, ordinal returns to the free-list.
        arena = arena.freed(ord);
        assertNull(arena.resolve(handleA), "freed slot no longer resolves");
        assertEquals(ord, arena.nextFreeOrdinal(), "freed ordinal is reused first");

        // Reincarnate a DIFFERENT faction at the SAME ordinal under a fresh generation.
        int handleB = arena.handleOf(ord);
        org.junit.jupiter.api.Assertions.assertNotEquals(handleA, handleB,
                "reincarnated handle differs by generation");
        Faction b = FactionTestSupport.faction(ord, "Bravo");
        arena = arena.withFaction(ord, b);

        // New handle resolves to the new faction; the stale handle must NOT reach it.
        assertSame(b, arena.resolve(handleB));
        assertNull(arena.resolve(handleA),
                "stale handle to a reincarnated slot resolves to null (generation mismatch)");
    }

    @Test
    void replaceKeepsGenerationAndSwapsRecord() {
        FactionArena arena = FactionArena.empty();
        int ord = arena.nextFreeOrdinal();
        int handle = arena.handleOf(ord);
        arena = arena.withFaction(ord, FactionTestSupport.faction(ord, "One"));

        Faction two = FactionTestSupport.faction(ord, "OneRenamed");
        FactionArena arena2 = arena.replace(ord, two);
        assertSame(two, arena2.resolve(handle), "replace swaps the record under the same handle");
        assertNotNull(arena.resolve(handle), "old arena is unchanged (COW)");
        assertEquals("One", arena.resolve(handle).name());
    }

    @Test
    void resolveRejectsWildernessAndOutOfRange() {
        FactionArena arena = FactionArena.empty();
        assertNull(arena.resolve(FactionHandle.WILDERNESS));
        assertNull(arena.resolve(FactionHandle.handle(0, 9999)));
    }
}
