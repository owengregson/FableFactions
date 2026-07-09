package dev.fablemc.factions.kernel.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The COW {@link ChestTable} plumbing with its parallel per-chest session-nonce array: insert,
 * replace (ref + nonce), nonce preservation on the ref-only {@code set}, delete-with-shift, and
 * whole-faction drop keep the ref array and the nonce array aligned (the finding #26 lost-update
 * guard depends on {@link ChestTable#nonceOf} tracking the winning session).
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b> test-confined
 * fixtures; every mutation returns a fresh table.
 */
class ChestTableTest {

    private static final int FACTION = 2;
    private static final int OTHER = 5;

    private static ChestRef ref(String name) {
        return new ChestRef(name, ChestRef.EMPTY_BLOB, 0L);
    }

    @Test
    void insertStartsAtNoNonceAndReplaceRecordsNonce() {
        ChestTable t = ChestTable.empty().set(FACTION, ref("vault"));
        assertEquals(ChestTable.NO_NONCE, t.nonceOf(FACTION, "vault"));

        ChestTable committed = t.set(FACTION, new ChestRef("vault", 99L, 0L), 1000L);
        assertEquals(1000L, committed.nonceOf(FACTION, "vault"));
        assertEquals(99L, committed.get(FACTION, "vault").blobRef());
        assertEquals(1, committed.size());
    }

    @Test
    void refOnlySetPreservesRecordedNonce() {
        ChestTable t = ChestTable.empty()
                .set(FACTION, ref("vault"))
                .set(FACTION, new ChestRef("vault", 7L, 0L), 500L);
        // A ref-only set (create/baseline path) must NOT reset the recorded session nonce.
        ChestTable again = t.set(FACTION, new ChestRef("vault", 8L, 0L));
        assertEquals(500L, again.nonceOf(FACTION, "vault"));
        assertEquals(8L, again.get(FACTION, "vault").blobRef());
    }

    @Test
    void nonceIsPerChestAndPerFaction() {
        ChestTable t = ChestTable.empty()
                .set(FACTION, ref("vault")).set(FACTION, new ChestRef("vault", 1L, 0L), 10L)
                .set(FACTION, ref("store")).set(FACTION, new ChestRef("store", 2L, 0L), 20L)
                .set(OTHER, ref("vault")).set(OTHER, new ChestRef("vault", 3L, 0L), 30L);

        assertEquals(10L, t.nonceOf(FACTION, "vault"));
        assertEquals(20L, t.nonceOf(FACTION, "store"));
        assertEquals(30L, t.nonceOf(OTHER, "vault"));
        assertEquals(ChestTable.NO_NONCE, t.nonceOf(OTHER, "store"));
        assertEquals(ChestTable.NO_NONCE, t.nonceOf(99, "vault"));
    }

    @Test
    void deleteShiftsRefAndNonceInLockstep() {
        ChestTable t = ChestTable.empty()
                .set(FACTION, ref("a")).set(FACTION, new ChestRef("a", 0L, 0L), 11L)
                .set(FACTION, ref("b")).set(FACTION, new ChestRef("b", 0L, 0L), 22L)
                .set(FACTION, ref("c")).set(FACTION, new ChestRef("c", 0L, 0L), 33L);

        ChestTable afterDelete = t.delete(FACTION, "b");
        assertNull(afterDelete.get(FACTION, "b"));
        assertEquals(11L, afterDelete.nonceOf(FACTION, "a"));
        assertEquals(33L, afterDelete.nonceOf(FACTION, "c"), "surviving chest keeps its own nonce");
        assertEquals(ChestTable.NO_NONCE, afterDelete.nonceOf(FACTION, "b"));
        assertEquals(2, afterDelete.size());
    }

    @Test
    void deleteLastChestDropsFactionRow() {
        ChestTable t = ChestTable.empty()
                .set(FACTION, new ChestRef("only", 0L, 0L), 44L)
                .set(OTHER, new ChestRef("keep", 0L, 0L), 55L);
        ChestTable afterDelete = t.delete(FACTION, "only");
        assertEquals(ChestTable.NO_NONCE, afterDelete.nonceOf(FACTION, "only"));
        assertEquals(55L, afterDelete.nonceOf(OTHER, "keep"), "the other faction is untouched");
        assertEquals(1, afterDelete.size());
    }

    @Test
    void removeFactionDropsEveryChestAndNonce() {
        ChestTable t = ChestTable.empty()
                .set(FACTION, ref("a")).set(FACTION, new ChestRef("a", 0L, 0L), 1L)
                .set(FACTION, ref("b")).set(FACTION, new ChestRef("b", 0L, 0L), 2L)
                .set(OTHER, new ChestRef("z", 0L, 0L), 3L);
        ChestTable afterRemove = t.removeFaction(FACTION);
        assertEquals(0, afterRemove.countForFaction(FACTION));
        assertEquals(ChestTable.NO_NONCE, afterRemove.nonceOf(FACTION, "a"));
        assertEquals(3L, afterRemove.nonceOf(OTHER, "z"));
        assertEquals(1, afterRemove.size());
    }
}
