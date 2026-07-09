package dev.fablemc.factions.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Combat-tag carry across a disconnect (finding #28): {@link SessionRegistry#close} captures a live
 * combat window before the session is zeroed, and {@link SessionRegistry#restoreCombatTag} re-applies
 * it on the next join so a fast relog cannot shed the tag. Headless — sessions open with {@code null}
 * player handles and time is passed explicitly (as {@link CombatTags} does).
 */
class SessionRegistryCombatCarryTest {

    @Test
    void combatTagSurvivesRelogWithinTheWindow() {
        SessionRegistry registry = new SessionRegistry();
        UUID id = UUID.randomUUID();
        registry.open(id, null).tagCombat(10_000L);   // window until 10s

        registry.close(id);                            // logout carries the expiry
        PlayerSession rejoined = registry.open(id, null);
        long applied = registry.restoreCombatTag(id, 5_000L);   // relog at 5s — inside the window

        assertEquals(10_000L, applied);
        assertEquals(10_000L, rejoined.combatTagUntil());
        assertTrue(rejoined.inCombat(5_000L));
    }

    @Test
    void theCarriedTagIsSingleUse() {
        SessionRegistry registry = new SessionRegistry();
        UUID id = UUID.randomUUID();
        registry.open(id, null).tagCombat(10_000L);
        registry.close(id);
        registry.open(id, null);

        assertEquals(10_000L, registry.restoreCombatTag(id, 1_000L));
        assertEquals(0L, registry.restoreCombatTag(id, 1_000L), "carry is consumed on first restore");
    }

    @Test
    void anAlreadyLapsedTagIsNotReapplied() {
        SessionRegistry registry = new SessionRegistry();
        UUID id = UUID.randomUUID();
        registry.open(id, null).tagCombat(10_000L);
        registry.close(id);
        PlayerSession rejoined = registry.open(id, null);

        long applied = registry.restoreCombatTag(id, 10_000L);   // window is [.., 10_000) — lapsed

        assertEquals(0L, applied);
        assertFalse(rejoined.inCombat(10_000L));
    }

    @Test
    void anUntaggedLogoutCarriesNothing() {
        SessionRegistry registry = new SessionRegistry();
        UUID id = UUID.randomUUID();
        registry.open(id, null);   // never tagged
        registry.close(id);
        registry.open(id, null);

        assertEquals(0L, registry.restoreCombatTag(id, 0L));
    }

    @Test
    void restoreIsANoOpWhenNoSessionIsOpen() {
        SessionRegistry registry = new SessionRegistry();
        UUID id = UUID.randomUUID();
        registry.open(id, null).tagCombat(10_000L);
        registry.close(id);   // carried, but the player never rejoins

        assertEquals(0L, registry.restoreCombatTag(id, 1_000L));
    }
}
