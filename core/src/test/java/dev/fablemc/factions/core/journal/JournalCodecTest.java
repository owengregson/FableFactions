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

import dev.fablemc.factions.kernel.vocab.FactionAuditAction;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.vocab.Relation;
import dev.fablemc.factions.kernel.vocab.PowerSource;
import dev.fablemc.factions.kernel.vocab.NotifyPredicate;
import dev.fablemc.factions.kernel.vocab.InviteRemovalReason;
import dev.fablemc.factions.kernel.vocab.BroadcastScope;
import dev.fablemc.factions.kernel.vocab.BankTxType;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.kernel.effect.TravelEffect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.effect.SessionEffect;
import dev.fablemc.factions.kernel.effect.RoleEffect;
import dev.fablemc.factions.kernel.effect.RelationEffect;
import dev.fablemc.factions.kernel.effect.PrefEffect;
import dev.fablemc.factions.kernel.effect.PowerEffect;
import dev.fablemc.factions.kernel.effect.MembershipEffect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.effect.ExternalEffect;
import dev.fablemc.factions.kernel.effect.EconomyEffect;
import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.ChestEffect;
import dev.fablemc.factions.kernel.effect.AuditEffect;

/**
 * The journal-codec contract (work order W2b §3): the tag registry is <b>complete</b> — every
 * journaled {@link Effect} record has a stable tag and every tag decodes — and encode→decode is a
 * byte-exact round trip for a representative of every effect type. The one permitted subtype that
 * is deliberately untagged is the {@link SystemEffect.ContinuationRequested} control effect (AM-5),
 * which the writer strips before the journal ever sees it.
 */
final class JournalCodecTest {

    private static final Origin ORIGIN = Origin.player(new UUID(1, 2));
    private static final UUID U1 = new UUID(10, 20);
    private static final UUID U2 = new UUID(30, 40);

    /** Reflect over the sealed hierarchy: every permitted subtype is either tagged or control. */
    @Test
    void everyEffectRecordIsTaggedOrControl() {
        // W25-REORG §P1: Effect now permits per-domain sub-interfaces, each permitting the leaf
        // records — flatten to the leaves before checking tagged-xor-control.
        Set<Class<?>> permitted = JournalCodec.leafEffectClasses();
        assertTrue(!permitted.isEmpty(), "Effect must be sealed with subtypes");

        Set<Class<? extends Effect>> registered = JournalCodec.registeredEffectClasses();
        Set<Class<? extends Effect>> control = JournalCodec.CONTROL_EFFECTS;

        for (Class<?> sub : permitted) {
            boolean isTagged = registered.contains(sub);
            boolean isControl = control.contains(sub);
            assertTrue(isTagged ^ isControl,
                    sub.getSimpleName() + " must be exactly one of {tagged, control}");
        }
        // ContinuationRequested is the sole control effect and carries an Intent back to the pipeline.
        assertEquals(Set.of(SystemEffect.ContinuationRequested.class), control);
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
        l.add(new LifecycleEffect.FactionCreated(s, ORIGIN, 3, U1, "Alpha"));
        l.add(new LifecycleEffect.FactionDisbanded(s, ORIGIN, 3, "Alpha"));
        l.add(new LifecycleEffect.FactionRenamed(s, ORIGIN, 3, "Alpha", "Beta"));
        l.add(new LifecycleEffect.DescriptionChanged(s, ORIGIN, 3, "desc"));
        l.add(new LifecycleEffect.MotdChanged(s, ORIGIN, 3, "motd"));
        l.add(new LifecycleEffect.OwnershipTransferred(s, ORIGIN, 3, U1, U2));
        l.add(new LifecycleEffect.MergeRequested(s, ORIGIN, 3, 4));
        l.add(new LifecycleEffect.MergeCompleted(s, ORIGIN, 3, 4, 5, 6, 12.5));
        l.add(new MembershipEffect.MemberJoined(s, ORIGIN, 3, U1));
        l.add(new MembershipEffect.MemberLeft(s, ORIGIN, 3, U1, true));
        l.add(new MembershipEffect.InviteCreated(s, ORIGIN, 3, U1, 99L));
        l.add(new MembershipEffect.InviteRemoved(s, ORIGIN, 3, U1, InviteRemovalReason.REVOKED));
        l.add(new RoleEffect.RankChanged(s, ORIGIN, 3, U1, 2));
        l.add(new RoleEffect.RoleCreated(s, ORIGIN, 3, "rid", "Knight", 30));
        l.add(new RoleEffect.RoleRenamed(s, ORIGIN, 3, "rid", "Knight", "Baron"));
        l.add(new RoleEffect.RoleRePrioritized(s, ORIGIN, 3, "rid", 40));
        l.add(new RoleEffect.RolePrefixSet(s, ORIGIN, 3, "rid", "[K]"));
        l.add(new RoleEffect.RoleDeleted(s, ORIGIN, 3, "rid"));
        l.add(new RoleEffect.RoleAssigned(s, ORIGIN, 3, U1, "rid"));
        l.add(new ClaimEffect.ClaimSet(s, ORIGIN, 0, 12345L, 3, -1));
        l.add(new ClaimEffect.ClaimRemoved(s, ORIGIN, 0, 12345L, 3));
        l.add(new ClaimEffect.ZoneSet(s, ORIGIN, 0, 1, 999L, -1));
        l.add(new ClaimEffect.ZoneRemoved(s, ORIGIN, 0, 1, 999L));
        l.add(new RelationEffect.RelationDeclared(s, ORIGIN, 3, 4, Relation.ALLY));
        l.add(new RelationEffect.RelationEffective(s, ORIGIN, 3, 4, Relation.ALLY, Relation.NEUTRAL));
        l.add(new PowerEffect.PowerChanged(s, ORIGIN, U1, 5.0, 7.5, PowerSource.REGEN_OFFLINE, "DEATH"));
        l.add(new PowerEffect.PowerFrozenChanged(s, ORIGIN, U1, true));
        l.add(new PowerEffect.DeathStreakAdvanced(s, ORIGIN, U1, 3));
        l.add(new PowerEffect.RaidableChanged(s, ORIGIN, 3, true));
        l.add(new EconomyEffect.BankChanged(s, ORIGIN, 3, -10.0, 90.0, BankTxType.WITHDRAW, U1, 4, "note"));
        l.add(new EconomyEffect.TaxCharged(s, ORIGIN, 3, 5.0, 85.0));
        l.add(new TravelEffect.HomeSet(s, ORIGIN, 3));
        l.add(new TravelEffect.HomeCleared(s, ORIGIN, 3));
        l.add(new TravelEffect.WarpSet(s, ORIGIN, 3, "base"));
        l.add(new TravelEffect.WarpDeleted(s, ORIGIN, 3, "base"));
        l.add(new TravelEffect.WarpPasswordSet(s, ORIGIN, 3, "base", false));
        l.add(new TravelEffect.WarpCostSet(s, ORIGIN, 3, "base", 25.0));
        l.add(new ChestEffect.ChestCreated(s, ORIGIN, 3, "vault"));
        l.add(new ChestEffect.ChestDeleted(s, ORIGIN, 3, "vault"));
        l.add(new ChestEffect.ChestContentsChanged(s, ORIGIN, 3, "vault", 777L));
        l.add(new PrefEffect.FlagChanged(s, ORIGIN, 3, 0, true));
        l.add(new PrefEffect.PrefChanged(s, ORIGIN, U1, 2, true));
        l.add(new PrefEffect.LocaleChanged(s, ORIGIN, U1, 1));
        l.add(new PrefEffect.AutoModeChanged(s, ORIGIN, U1, 1));
        l.add(new PrefEffect.FlyChanged(s, ORIGIN, U1, true));
        l.add(new PrefEffect.OverrideChanged(s, ORIGIN, U1, true));
        l.add(new PrefEffect.ShieldChanged(s, ORIGIN, 3, 8, 4));
        l.add(new SessionEffect.SessionStarted(s, ORIGIN, U1, 111L));
        l.add(new SessionEffect.SessionEnded(s, ORIGIN, U1, 222L));
        l.add(new SessionEffect.InboxQueued(s, ORIGIN, U1, key, args));
        l.add(new SessionEffect.InboxDelivered(s, ORIGIN, U1, ids));
        l.add(new AuditEffect.AuditRecorded(s, ORIGIN, 3, U1, FactionAuditAction.CLAIM, "detail"));
        l.add(new SystemEffect.ConfigSwapped(s, ORIGIN, "diff"));
        l.add(new FeedbackEffect.Notify(s, ORIGIN, U1, key, args));
        l.add(new FeedbackEffect.NotifyFaction(s, ORIGIN, 3, NotifyPredicate.MEMBERS_ALL, key, args));
        l.add(new FeedbackEffect.Broadcast(s, ORIGIN, BroadcastScope.SERVER, key, args));
        l.add(new FeedbackEffect.Rejected(s, ORIGIN, ReasonCode.BUSY, args));
        l.add(new ExternalEffect.PayoutRequested(s, ORIGIN, 5L, U1, 50.0));
        l.add(new ExternalEffect.EscrowRefund(s, ORIGIN, 5L, U1, 50.0));
        l.add(new ExternalEffect.WgRegionUpsert(s, ORIGIN, 0, 12345L, 3));
        l.add(new ExternalEffect.WgRegionRemove(s, ORIGIN, 0, 12345L));
        l.add(new ExternalEffect.LwcPurgeRequested(s, ORIGIN, 0, 12345L, 4));
        return l;
    }

    /** Guards that the control effect really is unencodable (it must never reach the journal). */
    @Test
    void continuationRequestedHasNoTag() {
        Effect control = new SystemEffect.ContinuationRequested(1L, ORIGIN, new SystemIntent.RetagPage(0));
        assertTrue(JournalCodec.CONTROL_EFFECTS.contains(control.getClass()));
        assertFalse(JournalCodec.registeredEffectClasses().contains(SystemEffect.ContinuationRequested.class));
    }
}
