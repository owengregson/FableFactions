package dev.fablemc.factions.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Windows behaviour of {@link CombatTags}: the config-gated combat-tag window over a confined
 * {@link PlayerSession}. Headless — a session is opened with a {@code null} player handle.
 */
class CombatTagsTest {

    private static PlayerSession session() {
        return new SessionRegistry().open(UUID.randomUUID(), null);
    }

    @Test
    void defaultsAreEnabledWithFifteenSecondWindow() {
        CombatTags tags = CombatTags.defaults();
        assertTrue(tags.enabled());
        assertEquals(CombatTags.DEFAULT_WINDOW_MILLIS, tags.windowMillis());
    }

    @Test
    void tagOpensWindowThatExpiresAfterTheConfiguredLength() {
        CombatTags tags = new CombatTags(true, 1_000L);
        PlayerSession session = session();
        long now = 10_000L;

        tags.tag(session, now);

        assertTrue(tags.inCombat(session, now));
        assertTrue(tags.inCombat(session, now + 999L));
        assertFalse(tags.inCombat(session, now + 1_000L)); // window is [now, now+window)
        assertFalse(tags.inCombat(session, now + 5_000L));
    }

    @Test
    void remainingCountsDownAndNeverGoesNegative() {
        CombatTags tags = new CombatTags(true, 1_000L);
        PlayerSession session = session();
        long now = 0L;

        tags.tag(session, now);

        assertEquals(1_000L, tags.remaining(session, now));
        assertEquals(400L, tags.remaining(session, now + 600L));
        assertEquals(0L, tags.remaining(session, now + 1_000L));
        assertEquals(0L, tags.remaining(session, now + 9_999L));
    }

    @Test
    void tagOnlyEverExtendsTheWindow() {
        CombatTags tags = new CombatTags(true, 1_000L);
        PlayerSession session = session();

        tags.tag(session, 5_000L);   // window until 6000
        tags.tag(session, 1_000L);   // earlier — must not shrink the window

        assertTrue(tags.inCombat(session, 5_500L));
    }

    @Test
    void clearEndsTheWindowImmediately() {
        CombatTags tags = CombatTags.defaults();
        PlayerSession session = session();

        tags.tag(session, 0L);
        tags.clear(session);

        assertFalse(tags.inCombat(session, 0L));
        assertEquals(0L, tags.remaining(session, 0L));
    }

    @Test
    void disabledPolicyNeverTagsAndReportsNoCombat() {
        CombatTags tags = new CombatTags(false, 1_000L);
        PlayerSession session = session();

        tags.tag(session, 0L);

        assertFalse(tags.enabled());
        assertFalse(tags.inCombat(session, 0L));
        assertEquals(0L, tags.remaining(session, 0L));
        assertFalse(session.inCombat(0L)); // the underlying session field was never written
    }

    @Test
    void everyQueryIsNullSafe() {
        CombatTags tags = CombatTags.defaults();
        tags.tag(null, 0L);
        tags.clear(null);
        assertFalse(tags.inCombat(null, 0L));
        assertEquals(0L, tags.remaining(null, 0L));
    }

    @Test
    void nonPositiveWindowIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CombatTags(true, 0L));
        assertThrows(IllegalArgumentException.class, () -> new CombatTags(true, -5L));
    }
}
