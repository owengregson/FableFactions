package dev.fablemc.factions.kernel.reduce;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.ChestIntent;
import dev.fablemc.factions.kernel.reduce.Kern.Session;

/**
 * The session-nonce guard on {@code CommitChestContents} (finding #26): a stale session's late
 * commit (a lower monotonic nonce than the recorded winner) is dropped so it cannot overwrite a
 * newer session's contents, while equal/greater nonces are accepted and the winner is recorded.
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded), driving {@link Reducer#apply}
 * through {@link Session}. <b>Mutability:</b> test-confined.
 */
class ChestReducerNonceTest {

    private static final String CHEST = "vault";

    @Test
    void staleNonceCommitIsRejectedWhileNewerWins() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        int ord = FactionHandle.ordinal(fa);
        s.apply(new ChestIntent.CreateChest(fa, CHEST, Kern.player(0)));
        assertEquals(0L, s.state.chests().get(ord, CHEST).blobRef(), "fresh chest starts empty");

        // Session A commits (nonce 100) → accepted, recorded.
        s.apply(new ChestIntent.CommitChestContents(fa, CHEST, 111L, 100L, Kern.player(0)));
        assertEquals(111L, s.state.chests().get(ord, CHEST).blobRef());
        assertEquals(100L, s.state.chests().nonceOf(ord, CHEST));

        // Session B (newer, nonce 200) supersedes A → accepted, recorded advances.
        s.apply(new ChestIntent.CommitChestContents(fa, CHEST, 222L, 200L, Kern.player(0)));
        assertEquals(222L, s.state.chests().get(ord, CHEST).blobRef());
        assertEquals(200L, s.state.chests().nonceOf(ord, CHEST));

        // A's stale late commit (nonce 100 < 200) must be dropped — the #26 lost-update dupe.
        s.apply(new ChestIntent.CommitChestContents(fa, CHEST, 333L, 100L, Kern.player(0)));
        assertEquals(222L, s.state.chests().get(ord, CHEST).blobRef(), "stale commit ignored");
        assertEquals(200L, s.state.chests().nonceOf(ord, CHEST), "recorded winner unchanged");

        // A same-session recommit (equal nonce) still lands (a periodic re-commit of B).
        s.apply(new ChestIntent.CommitChestContents(fa, CHEST, 444L, 200L, Kern.player(0)));
        assertEquals(444L, s.state.chests().get(ord, CHEST).blobRef(), "equal nonce accepted");
        assertEquals(200L, s.state.chests().nonceOf(ord, CHEST));
    }
}
