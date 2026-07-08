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

/** Saga-level pins: economy/escrow (AM-7), tax, zones, disband scrub (AM-6), merge, AM-5 paging. */
class ReducerSagaTest {

    // ── economy: deposit / withdraw / escrow settle (AM-7) ─────────────────────────────────

    @Test
    void depositWithdrawAndEscrowLifecycle() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        // deposit (phase-2 credit)
        s.apply(new Intent.CreditBank(fa, 100.0, Kern.player(0), 1L));
        assertEquals(100.0, s.state.factions().resolve(fa).bank(), 1e-9);

        // withdraw opens an escrow and requests payout
        List<Effect> w = s.apply(new Intent.RequestBankWithdrawal(fa, 40.0, Kern.player(0)));
        assertEquals(60.0, s.state.factions().resolve(fa).bank(), 1e-9);
        assertEquals(40.0, s.state.escrows().openTotal(), 1e-9);
        long escrowId = payoutId(w);
        assertTrue(escrowId >= 0);
        assertEquals(1, Kern.countType(w, Effect.PayoutRequested.class));

        // settle OK: escrow removed, no bank change (money delivered to the wallet)
        s.apply(new Intent.SettleEscrow(escrowId, Intent.ESCROW_OK));
        assertEquals(0.0, s.state.escrows().openTotal(), 1e-9);
        assertEquals(60.0, s.state.factions().resolve(fa).bank(), 1e-9);

        // withdraw again, then FAIL: bank re-credited, escrow closed (conservation)
        long id2 = payoutId(s.apply(new Intent.RequestBankWithdrawal(fa, 30.0, Kern.player(0))));
        assertEquals(30.0, s.state.factions().resolve(fa).bank(), 1e-9);
        s.apply(new Intent.SettleEscrow(id2, Intent.ESCROW_FAILED));
        assertEquals(60.0, s.state.factions().resolve(fa).bank(), 1e-9);
        assertEquals(0.0, s.state.escrows().openTotal(), 1e-9);

        // insufficient funds
        assertEquals(ReasonCode.INSUFFICIENT_FUNDS, Kern.firstReason(
                s.apply(new Intent.RequestBankWithdrawal(fa, 1000.0, Kern.player(0)))));
    }

    @Test
    void transferConservesTotalAndRejectsSelfTransfer() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        int fb = s.createFaction("Beta", Kern.player(1));
        s.apply(new Intent.CreditBank(fa, 100.0, Kern.player(0), 1L));
        double before = s.state.factions().resolve(fa).bank() + s.state.factions().resolve(fb).bank();
        s.apply(new Intent.TransferBank(fa, fb, 30.0, Kern.player(0)));
        assertEquals(70.0, s.state.factions().resolve(fa).bank(), 1e-9);
        assertEquals(30.0, s.state.factions().resolve(fb).bank(), 1e-9);
        double after = s.state.factions().resolve(fa).bank() + s.state.factions().resolve(fb).bank();
        assertEquals(before, after, 1e-9);

        // Self-transfer must NOT fabricate money (regression: the credit was computed from the
        // pre-debit balance and overwrote the debit). Now rejected, bank untouched.
        List<Effect> self = s.apply(new Intent.TransferBank(fa, fa, 10.0, Kern.player(0)));
        assertEquals(ReasonCode.INVALID_AMOUNT, Kern.firstReason(self));
        assertEquals(70.0, s.state.factions().resolve(fa).bank(), 1e-9);
    }

    @Test
    void periodicTaxChargesRoundedAmount() {
        Session s = new Session(KernelState.empty(Kern.cfgTax(0.05, 0.0, 0.01)));
        int fa = s.createFaction("Alpha", Kern.player(0));
        s.apply(new Intent.CreditBank(fa, 1000.0, Kern.player(0), 1L));
        List<Effect> sweep = s.apply(new Intent.TaxSweep(1));
        assertEquals(1, Kern.countType(sweep, Effect.TaxCharged.class));
        assertEquals(950.0, s.state.factions().resolve(fa).bank(), 1e-9);
        // empty-bank faction pays no tax
        int fb = s.createFaction("Beta", Kern.player(1));
        List<Effect> sweep2 = s.apply(new Intent.TaxSweep(2));
        // only Alpha (now 950) is taxed again; Beta (bank 0) is skipped
        assertEquals(1, Kern.countType(sweep2, Effect.TaxCharged.class));
        assertEquals(0.0, s.state.factions().resolve(fb).bank(), 1e-9);
    }

    // ── zones (+ the RemoveZoneChunk ordinal-guard fix) ────────────────────────────────────

    @Test
    void zoneAssignmentOverFactionAndRemoval() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        s.apply(new Intent.AdminClaimChunks(fa, 0, new long[] {ChunkKeys.key(0, 0)}, Kern.player(999)));
        assertEquals(1, s.state.factions().resolve(fa).landCount());

        // Convert the faction chunk to SAFEZONE: faction land is decremented, atlas re-owned.
        List<Effect> z = s.apply(new Intent.SetZoneChunks(FactionHandle.SAFEZONE_ORDINAL, 0,
                new long[] {ChunkKeys.key(0, 0)}, Kern.player(999)));
        assertEquals(1, Kern.countType(z, Effect.ZoneSet.class));
        assertEquals(0, s.state.factions().resolve(fa).landCount());
        assertEquals(FactionHandle.SAFEZONE_ORDINAL,
                FactionHandle.ordinal(s.state.claims().ownerAt(0, ChunkKeys.key(0, 0))));
        assertEquals(1, s.state.zones().safezoneChunks());

        // Remove it from the zone.
        List<Effect> r = s.apply(new Intent.RemoveZoneChunk(FactionHandle.SAFEZONE_ORDINAL, 0,
                ChunkKeys.key(0, 0), Kern.player(999)));
        assertEquals(1, Kern.countType(r, Effect.ZoneRemoved.class));
        assertEquals(0, s.state.zones().safezoneChunks());

        // Invalid zone ordinals are rejected, not applied.
        assertEquals(ReasonCode.FLAG_INVALID, Kern.firstReason(
                s.apply(new Intent.SetZoneChunks(7, 0, new long[] {ChunkKeys.key(1, 0)}, Kern.player(999)))));
    }

    @Test
    void removeZoneChunkRejectsNonZoneOrdinal() {
        // Regression: a normal-faction ordinal here used to strip an atlas claim WITHOUT
        // decrementing the faction's landCount (aggregate desync). Now guarded.
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        s.apply(new Intent.AdminClaimChunks(fa, 0, new long[] {ChunkKeys.key(5, 5)}, Kern.player(999)));
        int ord = FactionHandle.ordinal(fa);
        List<Effect> r = s.apply(new Intent.RemoveZoneChunk(ord, 0, ChunkKeys.key(5, 5), Kern.player(999)));
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
        List<Effect> first = s.step(new Intent.SetZoneChunks(FactionHandle.WARZONE_ORDINAL, 0, keys, Kern.player(999)));
        assertTrue(Kern.countType(first, Effect.ZoneSet.class) <= Reducer.PAGE_SIZE);
        assertEquals(1, Kern.countType(first, Effect.ContinuationRequested.class));

        // Full drive from scratch sets every chunk.
        Session s2 = Session.empty(Kern.cfgDefault());
        s2.apply(new Intent.SetZoneChunks(FactionHandle.WARZONE_ORDINAL, 0, keys, Kern.player(999)));
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
        s.apply(new Intent.SetFactionFlag(fa, dev.fablemc.factions.kernel.state.Faction.FLAG_OPEN,
                true, false, Kern.player(0)));
        s.apply(new Intent.JoinFaction(fa, Kern.player(2), Intent.OPEN_JOIN));
        s.apply(new Intent.ClaimChunks(Kern.player(0), fa, 0,
                new long[] {ChunkKeys.key(0, 0), ChunkKeys.key(1, 0)}, 0));
        s.apply(new Intent.SetWarp(fa, "base", 0, 1, 2, 3, 0, 0, Kern.player(0)));
        s.apply(new Intent.CreateChest(fa, "vault", Kern.player(0)));
        s.apply(new Intent.SendInvite(fa, Kern.player(0), Kern.player(3)));
        s.apply(new Intent.DeclareRelation(fa, fb, RelationKind.ENEMY, Kern.player(0)));
        s.apply(new Intent.CreditBank(fa, 50.0, Kern.player(0), 1L));
        s.apply(new Intent.RequestBankWithdrawal(fa, 20.0, Kern.player(0)));

        List<Effect> d = s.apply(new Intent.DisbandFaction(fa, true, Kern.player(0)));
        assertEquals(1, Kern.countType(d, Effect.FactionDisbanded.class));
        assertTrue(Kern.countType(d, Effect.EscrowRefund.class) >= 1, "open escrow refunded");
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
        s.apply(new Intent.ClaimChunks(Kern.player(0), fa, 0,
                new long[] {ChunkKeys.key(0, 0), ChunkKeys.key(1, 0)}, 0));
        s.apply(new Intent.ClaimChunks(Kern.player(1), fb, 0,
                new long[] {ChunkKeys.key(10, 10), ChunkKeys.key(11, 10)}, 0));
        s.apply(new Intent.SetFactionFlag(fa, dev.fablemc.factions.kernel.state.Faction.FLAG_OPEN,
                true, false, Kern.player(0)));
        s.apply(new Intent.JoinFaction(fa, Kern.player(2), Intent.OPEN_JOIN));
        s.apply(new Intent.SetWarp(fa, "base", 0, 1, 2, 3, 0, 0, Kern.player(0)));
        s.apply(new Intent.CreditBank(fa, 50.0, Kern.player(0), 1L));
        s.apply(new Intent.CreditBank(fb, 20.0, Kern.player(1), 2L));

        s.apply(new Intent.SendMergeRequest(fa, fb, Kern.player(1)));
        List<Effect> m = s.apply(new Intent.AcceptMergeRequest(fa, fb, Kern.player(1)));

        assertEquals(1, Kern.countType(m, Effect.MergeCompleted.class));
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
        List<Effect> kick = s.step(new Intent.DisbandFaction(handle, true, Kern.player(0)));
        Intent page = continuationOf(kick);
        assertTrue(page instanceof Intent.DisbandPage);
        // One claim page removes at most PAGE_SIZE chunks and asks for more.
        List<Effect> p = s.step(page);
        int removed = Kern.countType(p, Effect.ClaimRemoved.class);
        assertTrue(removed <= Reducer.PAGE_SIZE, "page removed " + removed);
        assertEquals(Reducer.PAGE_SIZE, removed); // 1100 > 1024 => first page is full
        assertEquals(1, Kern.countType(p, Effect.ContinuationRequested.class));

        // A full drive removes everything and frees the ordinal.
        Session full = new Session(Kern.bigClaimState(1100));
        List<Effect> all = full.apply(new Intent.DisbandFaction(handle, true, Kern.player(0)));
        assertEquals(1100, Kern.countType(all, Effect.ClaimRemoved.class));
        assertEquals(1, Kern.countType(all, Effect.FactionDisbanded.class));
        assertNull(full.state.factions().resolve(handle));
        assertEquals(0, full.state.claims().size());
    }

    @Test
    void unclaimAllIsPagedAndKeepsFaction() {
        Session s = new Session(Kern.bigClaimState(1100));
        int handle = Kern.handle(FactionHandle.FIRST_NORMAL_ORDINAL);
        List<Effect> all = s.apply(new Intent.UnclaimAll(Kern.player(0), handle));
        assertEquals(1100, Kern.countType(all, Effect.ClaimRemoved.class));
        assertEquals(0, s.state.factions().resolve(handle).landCount());
        assertEquals(0, s.state.claims().size());
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private static long payoutId(List<Effect> effects) {
        for (Effect e : effects) {
            if (e instanceof Effect.PayoutRequested p) {
                return p.escrowId();
            }
        }
        return -1;
    }

    private static Intent continuationOf(List<Effect> effects) {
        for (Effect e : effects) {
            if (e instanceof Effect.ContinuationRequested cr) {
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
