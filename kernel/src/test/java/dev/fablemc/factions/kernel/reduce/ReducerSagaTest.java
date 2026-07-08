package dev.fablemc.factions.kernel.reduce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.reduce.Kern.Session;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.RelationKind;
import dev.fablemc.factions.kernel.vocab.Relation;
import dev.fablemc.factions.kernel.vocab.EscrowOutcome;
import dev.fablemc.factions.kernel.vocab.ClaimMode;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.intent.RelationIntent;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.kernel.intent.ClaimIntent;
import dev.fablemc.factions.kernel.intent.ChestIntent;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.effect.ExternalEffect;
import dev.fablemc.factions.kernel.effect.EconomyEffect;
import dev.fablemc.factions.kernel.effect.ClaimEffect;

/** Saga-level pins: economy/escrow (AM-7), tax, zones, disband scrub (AM-6), merge, AM-5 paging. */
class ReducerSagaTest {

    // ── economy: deposit / withdraw / escrow settle (AM-7) ─────────────────────────────────

    @Test
    void depositWithdrawAndEscrowLifecycle() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        // deposit (phase-2 credit)
        s.apply(new EconomyIntent.CreditBank(fa, 100.0, Kern.player(0), 1L));
        assertEquals(100.0, s.state.factions().resolve(fa).bank(), 1e-9);

        // withdraw opens an escrow and requests payout
        List<Effect> w = s.apply(new EconomyIntent.RequestBankWithdrawal(fa, 40.0, Kern.player(0)));
        assertEquals(60.0, s.state.factions().resolve(fa).bank(), 1e-9);
        assertEquals(40.0, s.state.escrows().openTotal(), 1e-9);
        long escrowId = payoutId(w);
        assertTrue(escrowId >= 0);
        assertEquals(1, Kern.countType(w, ExternalEffect.PayoutRequested.class));

        // settle OK: escrow removed, no bank change (money delivered to the wallet)
        s.apply(new EconomyIntent.SettleEscrow(escrowId, EscrowOutcome.OK));
        assertEquals(0.0, s.state.escrows().openTotal(), 1e-9);
        assertEquals(60.0, s.state.factions().resolve(fa).bank(), 1e-9);

        // withdraw again, then FAIL: bank re-credited, escrow closed (conservation)
        long id2 = payoutId(s.apply(new EconomyIntent.RequestBankWithdrawal(fa, 30.0, Kern.player(0))));
        assertEquals(30.0, s.state.factions().resolve(fa).bank(), 1e-9);
        s.apply(new EconomyIntent.SettleEscrow(id2, EscrowOutcome.FAILED));
        assertEquals(60.0, s.state.factions().resolve(fa).bank(), 1e-9);
        assertEquals(0.0, s.state.escrows().openTotal(), 1e-9);

        // insufficient funds
        assertEquals(ReasonCode.INSUFFICIENT_FUNDS, Kern.firstReason(
                s.apply(new EconomyIntent.RequestBankWithdrawal(fa, 1000.0, Kern.player(0)))));
    }

    @Test
    void transferConservesTotalAndRejectsSelfTransfer() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        int fb = s.createFaction("Beta", Kern.player(1));
        s.apply(new EconomyIntent.CreditBank(fa, 100.0, Kern.player(0), 1L));
        double before = s.state.factions().resolve(fa).bank() + s.state.factions().resolve(fb).bank();
        s.apply(new EconomyIntent.TransferBank(fa, fb, 30.0, Kern.player(0)));
        assertEquals(70.0, s.state.factions().resolve(fa).bank(), 1e-9);
        assertEquals(30.0, s.state.factions().resolve(fb).bank(), 1e-9);
        double after = s.state.factions().resolve(fa).bank() + s.state.factions().resolve(fb).bank();
        assertEquals(before, after, 1e-9);

        // Self-transfer must NOT fabricate money (regression: the credit was computed from the
        // pre-debit balance and overwrote the debit). Now rejected, bank untouched.
        List<Effect> self = s.apply(new EconomyIntent.TransferBank(fa, fa, 10.0, Kern.player(0)));
        assertEquals(ReasonCode.INVALID_AMOUNT, Kern.firstReason(self));
        assertEquals(70.0, s.state.factions().resolve(fa).bank(), 1e-9);
    }

    @Test
    void periodicTaxChargesRoundedAmount() {
        Session s = new Session(KernelState.empty(Kern.cfgTax(0.05, 0.0, 0.01)));
        int fa = s.createFaction("Alpha", Kern.player(0));
        s.apply(new EconomyIntent.CreditBank(fa, 1000.0, Kern.player(0), 1L));
        List<Effect> sweep = s.apply(new EconomyIntent.TaxSweep(1));
        assertEquals(1, Kern.countType(sweep, EconomyEffect.TaxCharged.class));
        assertEquals(950.0, s.state.factions().resolve(fa).bank(), 1e-9);
        // empty-bank faction pays no tax
        int fb = s.createFaction("Beta", Kern.player(1));
        List<Effect> sweep2 = s.apply(new EconomyIntent.TaxSweep(2));
        // only Alpha (now 950) is taxed again; Beta (bank 0) is skipped
        assertEquals(1, Kern.countType(sweep2, EconomyEffect.TaxCharged.class));
        assertEquals(0.0, s.state.factions().resolve(fb).bank(), 1e-9);
    }

    // ── zones (+ the RemoveZoneChunk ordinal-guard fix) ────────────────────────────────────

    @Test
    void zoneAssignmentOverFactionAndRemoval() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        s.apply(new ClaimIntent.AdminClaimChunks(fa, 0, new long[] {ChunkKeys.key(0, 0)}, Kern.player(999)));
        assertEquals(1, s.state.factions().resolve(fa).landCount());

        // Convert the faction chunk to SAFEZONE: faction land is decremented, atlas re-owned.
        List<Effect> z = s.apply(new ClaimIntent.SetZoneChunks(FactionHandle.SAFEZONE_ORDINAL, 0,
                new long[] {ChunkKeys.key(0, 0)}, Kern.player(999)));
        assertEquals(1, Kern.countType(z, ClaimEffect.ZoneSet.class));
        assertEquals(0, s.state.factions().resolve(fa).landCount());
        assertEquals(FactionHandle.SAFEZONE_ORDINAL,
                FactionHandle.ordinal(s.state.claims().ownerAt(0, ChunkKeys.key(0, 0))));
        assertEquals(1, s.state.zones().safezoneChunks());

        // Remove it from the zone.
        List<Effect> r = s.apply(new ClaimIntent.RemoveZoneChunk(FactionHandle.SAFEZONE_ORDINAL, 0,
                ChunkKeys.key(0, 0), Kern.player(999)));
        assertEquals(1, Kern.countType(r, ClaimEffect.ZoneRemoved.class));
        assertEquals(0, s.state.zones().safezoneChunks());

        // Invalid zone ordinals are rejected, not applied.
        assertEquals(ReasonCode.FLAG_INVALID, Kern.firstReason(
                s.apply(new ClaimIntent.SetZoneChunks(7, 0, new long[] {ChunkKeys.key(1, 0)}, Kern.player(999)))));
    }

    @Test
    void removeZoneChunkRejectsNonZoneOrdinal() {
        // Regression: a normal-faction ordinal here used to strip an atlas claim WITHOUT
        // decrementing the faction's landCount (aggregate desync). Now guarded.
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        s.apply(new ClaimIntent.AdminClaimChunks(fa, 0, new long[] {ChunkKeys.key(5, 5)}, Kern.player(999)));
        int ord = FactionHandle.ordinal(fa);
        List<Effect> r = s.apply(new ClaimIntent.RemoveZoneChunk(ord, 0, ChunkKeys.key(5, 5), Kern.player(999)));
        assertEquals(ReasonCode.FLAG_INVALID, Kern.firstReason(r));
        assertEquals(1, s.state.factions().resolve(fa).landCount());
        assertEquals(ord, FactionHandle.ordinal(s.state.claims().ownerAt(0, ChunkKeys.key(5, 5))));
    }

    @Test
    void zoneAssignmentIsPagedUnder1024() {
        Session s = Session.empty(Kern.cfgDefault());
        int n = 1100;
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            keys[i] = ChunkKeys.key(i, 0);
        }
        // First page only: at most PAGE_SIZE zone sets + a continuation for the rest.
        List<Effect> first = s.step(new ClaimIntent.SetZoneChunks(FactionHandle.WARZONE_ORDINAL, 0, keys, Kern.player(999)));
        assertTrue(Kern.countType(first, ClaimEffect.ZoneSet.class) <= Reducer.PAGE_SIZE);
        assertEquals(1, Kern.countType(first, SystemEffect.ContinuationRequested.class));

        // Full drive from scratch sets every chunk.
        Session s2 = Session.empty(Kern.cfgDefault());
        s2.apply(new ClaimIntent.SetZoneChunks(FactionHandle.WARZONE_ORDINAL, 0, keys, Kern.player(999)));
        assertEquals(n, s2.state.zones().warzoneChunks());
        assertEquals(n, Kern.atlasCountForZone(s2.state, FactionHandle.WARZONE_ORDINAL));
    }

    // ── disband scrub (AM-6) ────────────────────────────────────────────────────────────────

    @Test
    void disbandScrubsEveryInboundReference() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        int fb = s.createFaction("Beta", Kern.player(1));
        int ordA = FactionHandle.ordinal(fa);
        s.connect(Kern.player(0));
        s.setPower(Kern.player(0), 10.0);
        s.apply(new PrefIntent.SetFactionFlag(fa, dev.fablemc.factions.kernel.state.Faction.FLAG_OPEN,
                true, false, Kern.player(0)));
        s.apply(new MembershipIntent.JoinFaction(fa, Kern.player(2), MembershipIntent.OPEN_JOIN));
        s.apply(new ClaimIntent.ClaimChunks(Kern.player(0), fa, 0,
                new long[] {ChunkKeys.key(0, 0), ChunkKeys.key(1, 0)}, ClaimMode.SINGLE));
        s.apply(new TravelIntent.SetWarp(fa, "base", 0, 1, 2, 3, 0, 0, Kern.player(0)));
        s.apply(new ChestIntent.CreateChest(fa, "vault", Kern.player(0)));
        s.apply(new MembershipIntent.SendInvite(fa, Kern.player(0), Kern.player(3)));
        s.apply(new RelationIntent.DeclareRelation(fa, fb, Relation.ENEMY, Kern.player(0)));
        s.apply(new EconomyIntent.CreditBank(fa, 50.0, Kern.player(0), 1L));
        s.apply(new EconomyIntent.RequestBankWithdrawal(fa, 20.0, Kern.player(0)));

        List<Effect> d = s.apply(new LifecycleIntent.DisbandFaction(fa, true, Kern.player(0)));
        assertEquals(1, Kern.countType(d, LifecycleEffect.FactionDisbanded.class));
        assertTrue(Kern.countType(d, ExternalEffect.EscrowRefund.class) >= 1, "open escrow refunded");
        assertNull(s.state.factions().resolve(fa), "faction slot freed");
        String dangling = Kern.danglingRefs(s.state, ordA, "alpha");
        assertEquals("", dangling, "no dangling references: " + dangling);
    }

    // ── merge (AM-5 paged) ──────────────────────────────────────────────────────────────────

    @Test
    void mergeAbsorbsSenderIntoTarget() {
        Session s = new Session(KernelState.empty(Kern.cfgMerge()));
        int fa = s.createFaction("Alpha", Kern.player(0)); // sender
        int fb = s.createFaction("Beta", Kern.player(1));  // target
        int ordA = FactionHandle.ordinal(fa);
        int ordB = FactionHandle.ordinal(fb);
        s.connect(Kern.player(0));
        s.connect(Kern.player(1));
        s.setPower(Kern.player(0), 10.0);
        s.setPower(Kern.player(1), 10.0);
        s.apply(new ClaimIntent.ClaimChunks(Kern.player(0), fa, 0,
                new long[] {ChunkKeys.key(0, 0), ChunkKeys.key(1, 0)}, ClaimMode.SINGLE));
        s.apply(new ClaimIntent.ClaimChunks(Kern.player(1), fb, 0,
                new long[] {ChunkKeys.key(10, 10), ChunkKeys.key(11, 10)}, ClaimMode.SINGLE));
        s.apply(new PrefIntent.SetFactionFlag(fa, dev.fablemc.factions.kernel.state.Faction.FLAG_OPEN,
                true, false, Kern.player(0)));
        s.apply(new MembershipIntent.JoinFaction(fa, Kern.player(2), MembershipIntent.OPEN_JOIN));
        s.apply(new TravelIntent.SetWarp(fa, "base", 0, 1, 2, 3, 0, 0, Kern.player(0)));
        s.apply(new EconomyIntent.CreditBank(fa, 50.0, Kern.player(0), 1L));
        s.apply(new EconomyIntent.CreditBank(fb, 20.0, Kern.player(1), 2L));

        s.apply(new LifecycleIntent.SendMergeRequest(fa, fb, Kern.player(1)));
        List<Effect> m = s.apply(new LifecycleIntent.AcceptMergeRequest(fa, fb, Kern.player(1)));

        assertEquals(1, Kern.countType(m, LifecycleEffect.MergeCompleted.class));
        assertNull(s.state.factions().resolve(fa), "sender freed");
        assertEquals(4, s.state.factions().resolve(fb).landCount(), "claims absorbed");
        assertEquals(70.0, s.state.factions().resolve(fb).bank(), 1e-9);
        // sender's members now belong to the target
        assertEquals(ordB, FactionHandle.ordinal(memberFaction(s.state, Kern.player(0))));
        assertEquals(ordB, FactionHandle.ordinal(memberFaction(s.state, Kern.player(2))));
        // sender's warp reassigned
        assertTrue(s.state.warps().get(ordB, "base") != null);
        // aggregate + scrub
        assertEquals(4, Kern.atlasLandByOrdinal(s.state).getOrDefault(ordB, 0).intValue());
        assertEquals("", Kern.danglingRefs(s.state, ordA, "alpha"));
    }

    // ── AM-5 page bound (≤1024 entities per page) ───────────────────────────────────────────

    @Test
    void disbandPageTouchesAtMost1024() {
        Session s = new Session(Kern.bigClaimState(1100));
        int handle = Kern.handle(FactionHandle.FIRST_NORMAL_ORDINAL);
        // The DisbandFaction step only enqueues the first page.
        List<Effect> kick = s.step(new LifecycleIntent.DisbandFaction(handle, true, Kern.player(0)));
        Intent page = continuationOf(kick);
        assertTrue(page instanceof LifecycleIntent.DisbandPage);
        // One claim page removes at most PAGE_SIZE chunks and asks for more.
        List<Effect> p = s.step(page);
        int removed = Kern.countType(p, ClaimEffect.ClaimRemoved.class);
        assertTrue(removed <= Reducer.PAGE_SIZE, "page removed " + removed);
        assertEquals(Reducer.PAGE_SIZE, removed); // 1100 > 1024 => first page is full
        assertEquals(1, Kern.countType(p, SystemEffect.ContinuationRequested.class));

        // A full drive removes everything and frees the ordinal.
        Session full = new Session(Kern.bigClaimState(1100));
        List<Effect> all = full.apply(new LifecycleIntent.DisbandFaction(handle, true, Kern.player(0)));
        assertEquals(1100, Kern.countType(all, ClaimEffect.ClaimRemoved.class));
        assertEquals(1, Kern.countType(all, LifecycleEffect.FactionDisbanded.class));
        assertNull(full.state.factions().resolve(handle));
        assertEquals(0, full.state.claims().size());
    }

    @Test
    void unclaimAllIsPagedAndKeepsFaction() {
        Session s = new Session(Kern.bigClaimState(1100));
        int handle = Kern.handle(FactionHandle.FIRST_NORMAL_ORDINAL);
        List<Effect> all = s.apply(new ClaimIntent.UnclaimAll(Kern.player(0), handle));
        assertEquals(1100, Kern.countType(all, ClaimEffect.ClaimRemoved.class));
        assertEquals(0, s.state.factions().resolve(handle).landCount());
        assertEquals(0, s.state.claims().size());
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private static long payoutId(List<Effect> effects) {
        for (Effect e : effects) {
            if (e instanceof ExternalEffect.PayoutRequested p) {
                return p.escrowId();
            }
        }
        return -1;
    }

    private static Intent continuationOf(List<Effect> effects) {
        for (Effect e : effects) {
            if (e instanceof SystemEffect.ContinuationRequested cr) {
                return cr.next();
            }
        }
        return null;
    }

    private static int memberFaction(KernelState st, java.util.UUID p) {
        int ord = st.members().get(p);
        return ord < 0 ? FactionHandle.WILDERNESS : st.ledger().factionHandle(ord);
    }
}
