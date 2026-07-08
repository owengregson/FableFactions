package dev.fablemc.factions.kernel.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Membership, iteration, and COW isolation for the {@link OnlineSet} bitset. */
class OnlineSetTest {

    @Test
    void addContainsRemoveAndSize() {
        OnlineSet set = OnlineSet.empty();
        assertEquals(0, set.size());
        assertFalse(set.contains(5));

        set = set.with(5).with(300).with(64).with(0);
        assertEquals(4, set.size());
        assertTrue(set.contains(0));
        assertTrue(set.contains(5));
        assertTrue(set.contains(64));
        assertTrue(set.contains(300));
        assertFalse(set.contains(63));

        set = set.without(64);
        assertEquals(3, set.size());
        assertFalse(set.contains(64));
    }

    @Test
    void idempotentAddAndAbsentRemoveAreNoOps() {
        OnlineSet set = OnlineSet.empty().with(10);
        assertSame(set, set.with(10), "re-adding a present ordinal returns the same set");
        assertSame(set, set.without(11), "removing an absent ordinal returns the same set");
    }

    @Test
    void iterationYieldsAscendingOrdinals() {
        int[] members = {0, 1, 63, 64, 65, 127, 128, 200, 511, 1023};
        OnlineSet set = OnlineSet.empty();
        for (int m : members) {
            set = set.with(m);
        }
        List<Integer> seen = new ArrayList<>();
        set.forEach(seen::add);
        assertEquals(members.length, seen.size());
        for (int i = 0; i < members.length; i++) {
            assertEquals(members[i], seen.get(i).intValue());
        }
    }

    @Test
    void cowIsolation() {
        OnlineSet base = OnlineSet.empty().with(1).with(2);
        OnlineSet next = base.with(3);
        assertFalse(base.contains(3), "old set unchanged by add");
        assertTrue(next.contains(3));

        OnlineSet removed = next.without(1);
        assertTrue(next.contains(1), "old set unchanged by remove");
        assertFalse(removed.contains(1));
    }
}
