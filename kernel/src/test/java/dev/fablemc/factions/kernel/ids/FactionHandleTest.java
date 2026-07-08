package dev.fablemc.factions.kernel.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pack/unpack invariants for the generation-tagged {@link FactionHandle} codec. */
class FactionHandleTest {

    @Test
    void packUnpackRoundTrip() {
        int[] gens = {0, 1, 5, 100, FactionHandle.MAX_GENERATION};
        int[] ords = {0, 1, 2, 500, 100_000, FactionHandle.MAX_ORDINAL};
        for (int g : gens) {
            for (int o : ords) {
                int h = FactionHandle.handle(g, o);
                assertEquals(o, FactionHandle.ordinal(h), "ordinal for gen=" + g + " ord=" + o);
                assertEquals(g, FactionHandle.generation(h), "generation for gen=" + g + " ord=" + o);
            }
        }
    }

    @Test
    void differentGenerationSameOrdinalYieldsDifferentHandle() {
        int a = FactionHandle.handle(0, 42);
        int b = FactionHandle.handle(1, 42);
        assertNotEquals(a, b);
        assertEquals(42, FactionHandle.ordinal(a));
        assertEquals(42, FactionHandle.ordinal(b));
    }

    @Test
    void sentinelAndNormalOrdinals() {
        assertTrue(FactionHandle.isNormalOrdinal(2));
        assertTrue(FactionHandle.isNormalOrdinal(100));
        org.junit.jupiter.api.Assertions.assertFalse(
                FactionHandle.isNormalOrdinal(FactionHandle.SAFEZONE_ORDINAL));
        org.junit.jupiter.api.Assertions.assertFalse(
                FactionHandle.isNormalOrdinal(FactionHandle.WARZONE_ORDINAL));
        assertEquals(-1, FactionHandle.WILDERNESS);
    }
}
