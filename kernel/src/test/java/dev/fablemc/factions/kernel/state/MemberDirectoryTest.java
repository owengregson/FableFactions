package dev.fablemc.factions.kernel.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Collision churn (insert/remove/re-insert) and COW isolation for {@link MemberDirectory}.
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b>
 * test-confined fixtures; no shared state between tests.
 */
class MemberDirectoryTest {

    @Test
    void insertRemoveReinsertChurn() {
        final int n = 5000;
        Random rng = new Random(0xF00DL);
        List<UUID> ids = new ArrayList<>(n);
        MemberDirectory dir = MemberDirectory.empty();
        for (int i = 0; i < n; i++) {
            UUID id = new UUID(rng.nextLong(), rng.nextLong());
            ids.add(id);
            dir = dir.withMapping(id, i);
        }
        assertEquals(n, dir.size());
        for (int i = 0; i < n; i++) {
            assertEquals(i, dir.get(ids.get(i)), "mapping " + i + " survives churn");
        }

        // Remove even ordinals.
        for (int i = 0; i < n; i += 2) {
            dir = dir.withoutMapping(ids.get(i));
        }
        assertEquals(n / 2, dir.size());
        for (int i = 0; i < n; i++) {
            if (i % 2 == 0) {
                assertEquals(MemberDirectory.ABSENT, dir.get(ids.get(i)), "removed " + i);
            } else {
                assertEquals(i, dir.get(ids.get(i)), "kept " + i);
            }
        }

        // Re-insert the removed ids under new ordinals (probe-slot reuse after rebuild).
        for (int i = 0; i < n; i += 2) {
            dir = dir.withMapping(ids.get(i), i + 1_000_000);
        }
        assertEquals(n, dir.size());
        for (int i = 0; i < n; i++) {
            int expected = (i % 2 == 0) ? i + 1_000_000 : i;
            assertEquals(expected, dir.get(ids.get(i)));
        }
    }

    @Test
    void overwriteKeepsSizeStable() {
        UUID id = UUID.randomUUID();
        MemberDirectory dir = MemberDirectory.empty().withMapping(id, 7);
        assertEquals(1, dir.size());
        dir = dir.withMapping(id, 9);
        assertEquals(1, dir.size(), "overwrite does not grow");
        assertEquals(9, dir.get(id));
    }

    @Test
    void cowIsolationOldDirectoryUnchanged() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        MemberDirectory base = MemberDirectory.empty().withMapping(a, 1);
        MemberDirectory next = base.withMapping(b, 2);
        assertTrue(base.contains(a));
        assertFalse(base.contains(b), "old directory never gained the new mapping");
        assertTrue(next.contains(a));
        assertTrue(next.contains(b));

        MemberDirectory removed = next.withoutMapping(a);
        assertFalse(removed.contains(a));
        assertTrue(next.contains(a), "removal does not mutate the prior directory");
    }

    @Test
    void unknownReturnsAbsent() {
        assertEquals(MemberDirectory.ABSENT, MemberDirectory.empty().get(UUID.randomUUID()));
    }
}
