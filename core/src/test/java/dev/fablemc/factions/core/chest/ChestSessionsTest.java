package dev.fablemc.factions.core.chest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The viewer-refcount / readiness state machine of {@link ChestSessions.LiveChest} — the
 * Bukkit-free core of the shared-inventory model (one live inventory per {@code (faction, chest)}
 * with a refcount that force-commits and evicts on the last close). Headless: the inventory field
 * stays {@code null}.
 */
class ChestSessionsTest {

    private static ChestSessions.LiveChest live(int faction, String name) {
        return new ChestSessions.LiveChest(new ChestSessions.ChestKey(faction, name));
    }

    @Test
    void freshChestIsNotReadyAndHasNoViewers() {
        ChestSessions.LiveChest chest = live(2, "vault");
        assertFalse(chest.isReady());
        assertEquals(0, chest.viewers());
        assertNull(chest.owner());
        assertEquals(new ChestSessions.ChestKey(2, "vault"), chest.key());
    }

    @Test
    void readyBindsInventoryOwnerAndMarksReady() {
        ChestSessions.LiveChest chest = live(2, "vault");
        UUID owner = UUID.randomUUID();

        chest.ready(null, owner); // inventory null is fine for the refcount machine

        assertTrue(chest.isReady());
        assertSame(owner, chest.owner());
    }

    @Test
    void viewerCountRisesAndFallsAndFloorsAtZero() {
        ChestSessions.LiveChest chest = live(2, "vault");

        assertEquals(1, chest.addViewer());
        assertEquals(2, chest.addViewer());
        assertEquals(3, chest.addViewer());
        assertEquals(2, chest.removeViewer());
        assertEquals(1, chest.removeViewer());
        assertEquals(0, chest.removeViewer());
        assertEquals(0, chest.removeViewer()); // last-close floor — never goes negative
        assertEquals(0, chest.viewers());
    }

    @Test
    void nonceIsStablePerLiveChest() {
        ChestSessions.LiveChest chest = live(2, "vault");
        long nonce = chest.nonce();
        chest.addViewer();
        chest.ready(null, UUID.randomUUID());
        assertEquals(nonce, chest.nonce());
    }

    @Test
    void distinctChestsHaveDistinctKeys() {
        ChestSessions.ChestKey a = new ChestSessions.ChestKey(2, "vault");
        ChestSessions.ChestKey b = new ChestSessions.ChestKey(2, "storage");
        ChestSessions.ChestKey c = new ChestSessions.ChestKey(3, "vault");
        assertEquals(a, new ChestSessions.ChestKey(2, "vault"));
        assertFalse(a.equals(b));
        assertFalse(a.equals(c));
    }
}
