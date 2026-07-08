package dev.fablemc.factions.platform.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The {@link Worlds} AM-15 registry, exercised through its Bukkit-free UUID core: dense
 * index assignment, wait-free reverse lookup, freed-slot reuse on unload/reload.
 */
class WorldsTest {

    @Test
    void registerAssignsDenseIndicesFromZero() {
        Worlds worlds = new Worlds();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(0, worlds.register(a));
        assertEquals(1, worlds.register(b));
        assertEquals(2, worlds.size());
    }

    @Test
    void registerIsIdempotent() {
        Worlds worlds = new Worlds();
        UUID a = UUID.randomUUID();
        int first = worlds.register(a);
        assertEquals(first, worlds.register(a));
        assertEquals(1, worlds.size());
    }

    @Test
    void indexOfAndUidRoundTrip() {
        Worlds worlds = new Worlds();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        int ia = worlds.register(a);
        int ib = worlds.register(b);
        assertEquals(ia, worlds.indexOf(a));
        assertEquals(ib, worlds.indexOf(b));
        assertEquals(a, worlds.uid(ia));
        assertEquals(b, worlds.uid(ib));
    }

    @Test
    void unknownLookupsDegradeCleanly() {
        Worlds worlds = new Worlds();
        assertEquals(-1, worlds.indexOf(UUID.randomUUID()));
        assertNull(worlds.uid(0));
        assertNull(worlds.uid(-1));
        assertNull(worlds.uid(99));
    }

    @Test
    void unregisterFreesTheSlotAndReuseKeepsIndicesCompact() {
        Worlds worlds = new Worlds();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        worlds.register(a);            // index 0
        int ib = worlds.register(b);   // index 1
        worlds.register(c);            // index 2
        assertEquals(3, worlds.size());

        worlds.unregister(b);
        assertEquals(-1, worlds.indexOf(b));
        assertNull(worlds.uid(ib));
        assertEquals(2, worlds.size());

        // A newly-loaded world reuses the freed slot rather than growing the space.
        UUID d = UUID.randomUUID();
        assertEquals(ib, worlds.register(d));
        assertEquals(3, worlds.size());
    }

    @Test
    void reloadingAWorldRebindsAConsistentIndex() {
        Worlds worlds = new Worlds();
        UUID a = UUID.randomUUID();
        int firstIndex = worlds.register(a);
        worlds.unregister(a);
        int reloadIndex = worlds.register(a);
        // Same UUID, sole world → the freed slot 0 is reused: identity is preserved.
        assertEquals(firstIndex, reloadIndex);
        assertNotEquals(-1, worlds.indexOf(a));
    }

    @Test
    void unregisterUnknownIsANoOp() {
        Worlds worlds = new Worlds();
        UUID a = UUID.randomUUID();
        worlds.register(a);
        worlds.unregister(UUID.randomUUID());
        assertEquals(1, worlds.size());
        assertEquals(0, worlds.indexOf(a));
    }
}
