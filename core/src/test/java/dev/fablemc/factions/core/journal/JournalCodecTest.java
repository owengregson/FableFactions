package dev.fablemc.factions.core.journal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.audit.FactionAuditAction;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;

/**
 * The journal-codec contract (work order W2b §3): the tag registry is <b>complete</b> — every
 * journaled {@link Effect} record has a stable tag and every tag decodes — and encode→decode is a
 * byte-exact round trip for a representative of every effect type. The one permitted subtype that
 * is deliberately untagged is the {@link Effect.ContinuationRequested} control effect (AM-5),
 * which the writer strips before the journal ever sees it.
 */
final class JournalCodecTest {

    private static final Origin ORIGIN = Origin.player(new UUID(1, 2));
    private static final UUID U1 = new UUID(10, 20);
    private static final UUID U2 = new UUID(30, 40);

    /** Reflect over the sealed hierarchy: every permitted subtype is either tagged or control. */
    @Test
    void everyEffectRecordIsTaggedOrControl() {
        Class<?>[] permitted = Effect.class.getPermittedSubclasses();
        assertTrue(permitted != null && permitted.length > 0, "Effect must be sealed with subtypes");

        Set<Class<? extends Effect>> registered = JournalCodec.registeredEffectClasses();
        Set<Class<? extends Effect>> control = JournalCodec.CONTROL_EFFECTS;

        for (Class<?> sub : permitted) {
            boolean isTagged = registered.contains(sub);
            boolean isControl = control.contains(sub);
            assertTrue(isTagged ^ isControl,
                    sub.getSimpleName() + " must be exactly one of {tagged, control}");
        }
        // ContinuationRequested is the sole control effect and carries an Intent back to the pipeline.
        assertEquals(Set.of(Effect.ContinuationRequested.class), control);
        // The boot completeness gate agrees (no missing/duplicate tags).
        assertDoesNotThrow(JournalCodec::verifyComplete);
    }

    /** Byte-exact round trip for one representative of EVERY tagged effect record. */
    @Test
    void roundTripsEveryEffectType() {
        List<Effect> samples = representatives();

        // The sample set must cover exactly the tagged effect classes — so this test can never
        // silently miss a newly-added effect (it would fail here AND in the completeness test).
        Set<Class<?>> sampled = new HashSet<>();
        for (Effect e : samples) {
            sampled.add(e.getClass());
        }
        Set<Class<?>> expected = new HashSet<>(JournalCodec.registeredEffectClasses());
        assertEquals(expected, sampled, "one representative per tagged effect record is required");

        for (Effect e : samples) {
            int tag = JournalCodec.tagOf(e);
            byte[] payload1 = JournalCodec.encode(e);
            Effect decoded = JournalCodec.decode(tag, e.seq(), payload1);

            assertEquals(e.getClass(), decoded.getClass(), "class survives round trip");
            assertEquals(e.seq(), decoded.seq(), "seq is re-supplied at decode");
            assertEquals(e.origin(), decoded.origin(), "origin survives round trip");
            assertEquals(tag, JournalCodec.tagOf(decoded), "tag is stable");
            // Re-encoding the decoded value must reproduce the exact bytes (handles array/MessageKey
            // components that record.equals would compare only by reference).
            assertArrayEquals(payload1, JournalCodec.encode(decoded),
                    e.getClass().getSimpleName() + " is not a byte-exact round trip");
        }
    }

    /** One populated instance of each of the 62 tagged effect records (arrays non-empty). */
    private static List<Effect> representatives() {
        long s = 42L;
        MessageKey key = MessageKey.of("general.busy");
        String[] args = {"a", "b"};
        long[] ids = {1L, 2L, 3L};
        List<Effect> l = new ArrayList<>();
        l.add(new Effect.FactionCreated(s, ORIGIN, 3, U1, "Alpha"));
        l.add(new Effect.FactionDisbanded(s, ORIGIN, 3, "Alpha"));
        l.add(new Effect.FactionRenamed(s, ORIGIN, 3, "Alpha", "Beta"));
        l.add(new Effect.DescriptionChanged(s, ORIGIN, 3, "desc"));
        l.add(new Effect.MotdChanged(s, ORIGIN, 3, "motd"));
        l.add(new Effect.OwnershipTransferred(s, ORIGIN, 3, U1, U2));
        l.add(new Effect.MergeRequested(s, ORIGIN, 3, 4));
        l.add(new Effect.MergeCompleted(s, ORIGIN, 3, 4, 5, 6, 12.5));
        l.add(new Effect.MemberJoined(s, ORIGIN, 3, U1));
        l.add(new Effect.MemberLeft(s, ORIGIN, 3, U1, true));
        l.add(new Effect.InviteCreated(s, ORIGIN, 3, U1, 99L));
        l.add(new Effect.InviteRemoved(s, ORIGIN, 3, U1, Effect.INVITE_REVOKED));
        l.add(new Effect.RankChanged(s, ORIGIN, 3, U1, 2));
        l.add(new Effect.RoleCreated(s, ORIGIN, 3, "rid", "Knight", 30));
        l.add(new Effect.RoleRenamed(s, ORIGIN, 3, "rid", "Knight", "Baron"));
        l.add(new Effect.RoleRePrioritized(s, ORIGIN, 3, "rid", 40));
        l.add(new Effect.RolePrefixSet(s, ORIGIN, 3, "rid", "[K]"));
        l.add(new Effect.RoleDeleted(s, ORIGIN, 3, "rid"));
        l.add(new Effect.RoleAssigned(s, ORIGIN, 3, U1, "rid"));
        l.add(new Effect.ClaimSet(s, ORIGIN, 0, 12345L, 3, -1));
        l.add(new Effect.ClaimRemoved(s, ORIGIN, 0, 12345L, 3));
        l.add(new Effect.ZoneSet(s, ORIGIN, 0, 1, 999L, -1));
        l.add(new Effect.ZoneRemoved(s, ORIGIN, 0, 1, 999L));
        l.add(new Effect.RelationDeclared(s, ORIGIN, 3, 4, 1));
        l.add(new Effect.RelationEffective(s, ORIGIN, 3, 4, 1, 3));
        l.add(new Effect.PowerChanged(s, ORIGIN, U1, 5.0, 7.5, 1, "DEATH"));
        l.add(new Effect.PowerFrozenChanged(s, ORIGIN, U1, true));
        l.add(new Effect.DeathStreakAdvanced(s, ORIGIN, U1, 3));
        l.add(new Effect.RaidableChanged(s, ORIGIN, 3, true));
        l.add(new Effect.BankChanged(s, ORIGIN, 3, -10.0, 90.0, Effect.TX_WITHDRAW, U1, 4, "note"));
        l.add(new Effect.TaxCharged(s, ORIGIN, 3, 5.0, 85.0));
        l.add(new Effect.HomeSet(s, ORIGIN, 3));
        l.add(new Effect.HomeCleared(s, ORIGIN, 3));
        l.add(new Effect.WarpSet(s, ORIGIN, 3, "base"));
        l.add(new Effect.WarpDeleted(s, ORIGIN, 3, "base"));
        l.add(new Effect.WarpPasswordSet(s, ORIGIN, 3, "base", false));
        l.add(new Effect.WarpCostSet(s, ORIGIN, 3, "base", 25.0));
        l.add(new Effect.ChestCreated(s, ORIGIN, 3, "vault"));
        l.add(new Effect.ChestDeleted(s, ORIGIN, 3, "vault"));
        l.add(new Effect.ChestContentsChanged(s, ORIGIN, 3, "vault", 777L));
        l.add(new Effect.FlagChanged(s, ORIGIN, 3, 0, true));
        l.add(new Effect.PrefChanged(s, ORIGIN, U1, 2, true));
        l.add(new Effect.LocaleChanged(s, ORIGIN, U1, 1));
        l.add(new Effect.AutoModeChanged(s, ORIGIN, U1, 1));
        l.add(new Effect.FlyChanged(s, ORIGIN, U1, true));
        l.add(new Effect.OverrideChanged(s, ORIGIN, U1, true));
        l.add(new Effect.ShieldChanged(s, ORIGIN, 3, 8, 4));
        l.add(new Effect.SessionStarted(s, ORIGIN, U1, 111L));
        l.add(new Effect.SessionEnded(s, ORIGIN, U1, 222L));
        l.add(new Effect.InboxQueued(s, ORIGIN, U1, key, args));
        l.add(new Effect.InboxDelivered(s, ORIGIN, U1, ids));
        l.add(new Effect.AuditRecorded(s, ORIGIN, 3, U1, FactionAuditAction.CLAIM, "detail"));
        l.add(new Effect.ConfigSwapped(s, ORIGIN, "diff"));
        l.add(new Effect.Notify(s, ORIGIN, U1, key, args));
        l.add(new Effect.NotifyFaction(s, ORIGIN, 3, Effect.MEMBERS_ALL, key, args));
        l.add(new Effect.Broadcast(s, ORIGIN, Effect.SCOPE_SERVER, key, args));
        l.add(new Effect.Rejected(s, ORIGIN, ReasonCode.BUSY, args));
        l.add(new Effect.PayoutRequested(s, ORIGIN, 5L, U1, 50.0));
        l.add(new Effect.EscrowRefund(s, ORIGIN, 5L, U1, 50.0));
        l.add(new Effect.WgRegionUpsert(s, ORIGIN, 0, 12345L, 3));
        l.add(new Effect.WgRegionRemove(s, ORIGIN, 0, 12345L));
        l.add(new Effect.LwcPurgeRequested(s, ORIGIN, 0, 12345L, 4));
        return l;
    }

    /** Guards that the control effect really is unencodable (it must never reach the journal). */
    @Test
    void continuationRequestedHasNoTag() {
        Effect control = new Effect.ContinuationRequested(1L, ORIGIN, new Intent.RetagPage(0));
        assertTrue(JournalCodec.CONTROL_EFFECTS.contains(control.getClass()));
        assertFalse(JournalCodec.registeredEffectClasses().contains(Effect.ContinuationRequested.class));
    }
}
