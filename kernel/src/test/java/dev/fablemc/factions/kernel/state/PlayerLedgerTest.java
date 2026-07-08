package dev.fablemc.factions.kernel.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * COW shard isolation and field round-trips for the sharded {@link PlayerLedger}.
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b>
 * test-confined fixtures; no shared state between tests.
 */
class PlayerLedgerTest {

    /** Allocates ordinals 0..count-1 with deterministic UUIDs. */
    private static PlayerLedger fill(int count) {
        PlayerLedger ledger = PlayerLedger.empty();
        for (int i = 0; i < count; i++) {
            int ord = ledger.nextOrdinal();
            assertEquals(i, ord);
            ledger = ledger.withNewMember(ord, new UUID(0, i), "P" + i);
        }
        return ledger;
    }

    @Test
    void mutationCopiesOnlyTheTouchedShardAndSharesTheRest() {
        // 257 members span shard 0 (ords 0..255) and shard 1 (ord 256).
        PlayerLedger ledger = fill(257);
        ledger = ledger.withPower(0, 5.0, 10L);
        ledger = ledger.withPower(256, 7.0, 20L);

        Object oldShard0 = ledger.shardRef(0);
        Object oldShard1 = ledger.shardRef(1);

        // Mutate a member in shard 0 only.
        PlayerLedger next = ledger.withPower(0, 99.0, 30L);

        assertNotSame(oldShard0, next.shardRef(0), "touched shard is a fresh copy");
        assertSame(oldShard1, next.shardRef(1), "untouched shard is shared by identity");

        // Old ledger keeps the old value; new ledger sees the new one.
        assertEquals(5.0, ledger.powerBase(0));
        assertEquals(10L, ledger.powerAsOfTick(0));
        assertEquals(99.0, next.powerBase(0));
        assertEquals(30L, next.powerAsOfTick(0));

        // The other shard's data is intact in both.
        assertEquals(7.0, ledger.powerBase(256));
        assertEquals(7.0, next.powerBase(256));
    }

    @Test
    void newMemberDefaultsAndFieldRoundTrips() {
        PlayerLedger ledger = PlayerLedger.empty();
        UUID id = UUID.randomUUID();
        ledger = ledger.withNewMember(0, id, "Zed");
        assertTrue(ledger.has(0));
        assertEquals(id, ledger.uuid(0));
        assertEquals("Zed", ledger.nameLast(0));
        assertEquals(dev.fablemc.factions.kernel.ids.FactionHandle.WILDERNESS, ledger.factionHandle(0));
        assertEquals(PlayerLedger.DEFAULT_PREFS, ledger.prefsBits(0));
        assertEquals(1, ledger.size());

        ledger = ledger.withFactionHandle(0, 12345)
                .withRankIdx(0, 2)
                .withPowerFrozen(0, true)
                .withDeath(0, 3, 999L)
                .withLastActivity(0, 42L)
                .withLocaleIdx(0, 4)
                .withPowerBoost(0, 1.5)
                .withJoinedAt(0, 77L);
        assertEquals(12345, ledger.factionHandle(0));
        assertEquals(2, ledger.rankIdx(0));
        assertTrue(ledger.powerFrozen(0));
        assertEquals(3, ledger.deathStreak(0));
        assertEquals(999L, ledger.lastDeathAt(0));
        assertEquals(42L, ledger.lastActivity(0));
        assertEquals((byte) 4, ledger.localeIdx(0));
        assertEquals(1.5, ledger.powerBoost(0));
        assertEquals(77L, ledger.joinedAt(0));
    }

    @Test
    void evictionReclaimsOrdinal() {
        PlayerLedger ledger = fill(3);
        assertEquals(3, ledger.size());
        ledger = ledger.without(1);
        assertEquals(2, ledger.size());
        org.junit.jupiter.api.Assertions.assertFalse(ledger.has(1));
        assertEquals(1, ledger.nextOrdinal(), "evicted ordinal is reused first");

        ledger = ledger.withNewMember(1, new UUID(9, 9), "Reborn");
        assertTrue(ledger.has(1));
        assertEquals("Reborn", ledger.nameLast(1));
        assertEquals(3, ledger.size());
    }

    @Test
    void prefBitHelpers() {
        int bits = PlayerLedger.DEFAULT_PREFS;
        assertTrue(PlayerLedger.pref(bits, PlayerLedger.PREF_NOTIFY_INVITES));
        bits = PlayerLedger.withPref(bits, PlayerLedger.PREF_NOTIFY_INVITES, false);
        org.junit.jupiter.api.Assertions.assertFalse(PlayerLedger.pref(bits, PlayerLedger.PREF_NOTIFY_INVITES));

        bits = PlayerLedger.withAutoMode(bits, PlayerLedger.AUTO_MODE_CLAIM);
        assertEquals(PlayerLedger.AUTO_MODE_CLAIM, PlayerLedger.autoMode(bits));
        bits = PlayerLedger.withAutoMode(bits, PlayerLedger.AUTO_MODE_UNCLAIM);
        assertEquals(PlayerLedger.AUTO_MODE_UNCLAIM, PlayerLedger.autoMode(bits));
        // Auto-mode change must not disturb other pref bits.
        assertTrue(PlayerLedger.pref(bits, PlayerLedger.PREF_NOTIFY_TERRITORY));
    }
}
