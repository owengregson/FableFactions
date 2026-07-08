package dev.fablemc.factions.kernel.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Fold-casing and uniqueness authority for {@link NameIndex}. */
class NameIndexTest {

    @Test
    void foldIsCaseInsensitive() {
        assertEquals(NameIndex.fold("hello"), NameIndex.fold("HeLLo"));
        assertEquals(NameIndex.fold("MYFACTION"), NameIndex.fold("myfaction"));
    }

    @Test
    void lookupIsCaseInsensitiveViaFold() {
        NameIndex idx = NameIndex.empty().with(NameIndex.fold("MyFaction"), 5);
        assertEquals(5, idx.ordinalOf(NameIndex.fold("MYFACTION")));
        assertEquals(5, idx.ordinalOf(NameIndex.fold("myfaction")));
        assertEquals(5, idx.ordinalOf(NameIndex.fold("MyFaction")));
        assertTrue(idx.contains(NameIndex.fold("myFACTION")));
        assertEquals(NameIndex.ABSENT, idx.ordinalOf(NameIndex.fold("Other")));
    }

    @Test
    void registerReleaseAndUniqueness() {
        NameIndex idx = NameIndex.empty();
        for (int i = 0; i < 300; i++) {
            idx = idx.with(NameIndex.fold("Faction_" + i), i);
        }
        assertEquals(300, idx.size());
        for (int i = 0; i < 300; i++) {
            assertEquals(i, idx.ordinalOf(NameIndex.fold("FACTION_" + i)));
        }

        // Uniqueness: registering a taken (folded) name reports it present.
        assertTrue(idx.contains(NameIndex.fold("faction_42")));

        // Release frees the name for reuse.
        NameIndex after = idx.without(NameIndex.fold("Faction_42"));
        assertFalse(after.contains(NameIndex.fold("faction_42")));
        assertEquals(299, after.size());
        // Old index is unchanged (COW).
        assertTrue(idx.contains(NameIndex.fold("Faction_42")));

        NameIndex reused = after.with(NameIndex.fold("faction_42"), 9999);
        assertEquals(9999, reused.ordinalOf(NameIndex.fold("FACTION_42")));
    }

    @Test
    void overwriteKeepsSize() {
        NameIndex idx = NameIndex.empty().with(NameIndex.fold("Solo"), 1);
        assertEquals(1, idx.size());
        idx = idx.with(NameIndex.fold("solo"), 2);
        assertEquals(1, idx.size());
        assertEquals(2, idx.ordinalOf(NameIndex.fold("SOLO")));
    }
}
