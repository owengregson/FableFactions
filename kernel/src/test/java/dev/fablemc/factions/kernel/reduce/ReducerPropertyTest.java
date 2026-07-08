package dev.fablemc.factions.kernel.reduce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.reduce.Kern.Session;
import dev.fablemc.factions.kernel.rules.ClaimRules;
import dev.fablemc.factions.kernel.rules.FactionAggregates;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.KernelState;

/**
 * jqwik property suite over random intent sequences applied through {@link Reducer#apply} and
 * drained as the writer would. Pins the W2a invariants: never-throws, replay determinism, relation
 * symmetry, member/land caps (or raidable), non-negative banks, escrow conservation, incremental
 * aggregates == recompute, ≤1024 per page, and disband-leaves-no-dangling.
 */
class ReducerPropertyTest {

    private static final UUID[] PLAYERS = players(10);

    // ── never throws & banks stay non-negative under the full intent menu ──────────────────

    @Property(tries = 120)
    void neverThrowsUnderBroadIntents(@ForAll long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        Session s = new Session(KernelState.empty(Kern.cfgMerge()));
        int[] h = mkFixture(s);
        for (int i = 0; i < 120; i++) {
            Intent it = gen(r, h, PLAYERS, true);
            try {
                s.apply(it);
            } catch (Throwable t) {
                throw new AssertionError("reducer threw on " + it + " (seed=" + seed + "): " + t, t);
            }
            assertTrue(Kern.allBanksNonNegative(s.state), "bank went negative after " + it);
            assertTrue(s.state.escrows().openTotal() >= -1e-9);
        }
    }

    // ── incremental aggregates, caps, symmetry, raidable ───────────────────────────────────

    @Property(tries = 60)
    void invariantsHoldUnderStableMutations(@ForAll long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        Session s = new Session(KernelState.empty(Kern.cfgDefault()));
        int[] h = mkFixture(s);
        int maxMembers = s.state.config().limits().maxMembers();
        for (int i = 0; i < 70; i++) {
            s.apply(gen(r, h, PLAYERS, false));
            checkStepInvariants(s.state, maxMembers);
        }
        // relation edges are symmetric for every live faction pair
        FactionArena arena = s.state.factions();
        for (int a = 0; a < arena.highWater(); a++) {
            Faction fa = arena.at(a);
            if (fa == null || !fa.isNormal()) {
                continue;
            }
            for (int b = a + 1; b < arena.highWater(); b++) {
                Faction fb = arena.at(b);
                if (fb == null || !fb.isNormal()) {
                    continue;
                }
                assertEquals(fa.relationEffective(b), fb.relationEffective(a),
                        "relation symmetry " + a + "<->" + b);
            }
        }
        // after a tick, raidable is exactly (land > maxLand); land cap otherwise holds
        s.apply(new Intent.PowerTick(0));
        arena = s.state.factions();
        for (int ord = 0; ord < arena.highWater(); ord++) {
            Faction f = arena.at(ord);
            if (f == null || !f.isNormal()) {
                continue;
            }
            double tp = FactionAggregates.totalPower(s.state, f, s.state.tick(), s.epoch);
            int maxLand = ClaimRules.computeMaxLand(s.state.config().land(), tp);
            assertEquals(f.landCount() > maxLand, f.raidable(), "raidable@" + ord);
        }
        // no single page ever exceeded the AM-5 bound
        assertTrue(s.maxPageEntities <= Reducer.PAGE_SIZE,
                "page touched " + s.maxPageEntities + " entities");
    }

    // ── escrow / bank money conservation (closed system: no deposits inside the loop) ──────

    @Property(tries = 80)
    void escrowConservation(@ForAll long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        Session s = new Session(KernelState.empty(Kern.cfgDefault()));
        int[] h = new int[3];
        for (int i = 0; i < 3; i++) {
            h[i] = s.createFaction("Bank" + i, Kern.player(i));
            s.apply(new Intent.CreditBank(h[i], 100.0, Kern.player(i), i + 1));
        }
        double initial = Kern.custody(s.state);
        double walletOut = 0.0;
        for (int i = 0; i < 90; i++) {
            switch (r.nextInt(3)) {
                case 0 -> s.apply(new Intent.RequestBankWithdrawal(h[r.nextInt(3)],
                        1 + r.nextInt(20), Kern.player(0)));
                case 1 -> {
                    long[] open = Kern.openEscrowIds(s.state);
                    if (open.length > 0) {
                        long id = open[r.nextInt(open.length)];
                        int outcome = r.nextInt(2);
                        if (outcome == Intent.ESCROW_OK) {
                            walletOut += s.state.escrows().byId(id).amount(); // delivered to wallet
                        }
                        s.apply(new Intent.SettleEscrow(id, outcome));
                    }
                }
                default -> {
                    int from = r.nextInt(3);
                    int to = r.nextInt(3);
                    if (from != to) {
                        s.apply(new Intent.TransferBank(h[from], h[to], 1 + r.nextInt(20),
                                Kern.player(0)));
                    }
                }
            }
            assertEquals(initial, Kern.custody(s.state) + walletOut, 1e-6,
                    "money conservation broken at step " + i);
            assertTrue(Kern.allBanksNonNegative(s.state));
        }
    }

    // ── replay determinism ─────────────────────────────────────────────────────────────────

    @Property(tries = 40)
    void replayDeterminismRandom(@ForAll long seed) {
        assertEquals(foldOnce(seed, 150), foldOnce(seed, 150));
    }

    @Example
    void replayDeterminismLargeSequence() {
        assertEquals(foldOnce(0xC0FFEEL, 2000), foldOnce(0xC0FFEEL, 2000));
    }

    private static String foldOnce(long seed, int steps) {
        SplittableRandom r = new SplittableRandom(seed);
        Session s = new Session(KernelState.empty(Kern.cfgMerge()));
        int[] h = mkFixture(s);
        for (int i = 0; i < steps; i++) {
            s.apply(gen(r, h, PLAYERS, true));
        }
        return Kern.digest(s.state);
    }

    // ── disband leaves no dangling references ──────────────────────────────────────────────

    @Property(tries = 50)
    void disbandLeavesNoDangling(@ForAll long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        Session s = new Session(KernelState.empty(Kern.cfgDefault()));
        int[] h = mkFixture(s);
        for (int i = 0; i < 45; i++) {
            s.apply(gen(r, h, PLAYERS, false));
        }
        int target = h[r.nextInt(h.length)];
        Faction tf = s.state.factions().resolve(target);
        assertTrue(tf != null); // stable mutations never disband
        int ord = FactionHandle.ordinal(target);
        String folded = tf.nameFolded();
        s.apply(new Intent.DisbandFaction(target, true, Kern.player(0)));
        assertNull(s.state.factions().resolve(target), "faction not freed");
        String dangling = Kern.danglingRefs(s.state, ord, folded);
        assertEquals("", dangling, "dangling after disband (seed=" + seed + "): " + dangling);
    }

    // ── shared fixture, generator, invariant checks ─────────────────────────────────────────

    private static int[] mkFixture(Session s) {
        int[] h = new int[4];
        for (int i = 0; i < 4; i++) {
            h[i] = s.createFaction("Fac" + i, Kern.player(i));
        }
        for (int i = 0; i < 6; i++) {
            s.connect(Kern.player(i));
            s.setPower(Kern.player(i), 8.0);
        }
        // P4 -> F0, P5 -> F1 (open join)
        s.apply(new Intent.SetFactionFlag(h[0], Faction.FLAG_OPEN, true, false, Kern.player(0)));
        s.apply(new Intent.JoinFaction(h[0], Kern.player(4), Intent.OPEN_JOIN));
        s.apply(new Intent.SetFactionFlag(h[1], Faction.FLAG_OPEN, true, false, Kern.player(1)));
        s.apply(new Intent.JoinFaction(h[1], Kern.player(5), Intent.OPEN_JOIN));
        s.apply(new Intent.ClaimChunks(Kern.player(0), h[0], 0,
                new long[] {ChunkKeys.key(0, 0)}, 0));
        s.apply(new Intent.ClaimChunks(Kern.player(1), h[1], 0,
                new long[] {ChunkKeys.key(-2, -2)}, 0));
        return h;
    }

    private static void checkStepInvariants(KernelState st, int maxMembers) {
        Map<Integer, Integer> atlas = Kern.atlasLandByOrdinal(st);
        FactionArena arena = st.factions();
        for (int ord = 0; ord < arena.highWater(); ord++) {
            Faction f = arena.at(ord);
            if (f == null || !f.isNormal()) {
                continue;
            }
            assertEquals(atlas.getOrDefault(ord, 0).intValue(), f.landCount(),
                    "incremental landCount != atlas recompute @" + ord);
            if (maxMembers > 0) {
                assertTrue(FactionAggregates.memberCount(st, arena.handleOf(ord)) <= maxMembers,
                        "member cap exceeded @" + ord);
            }
        }
        assertEquals(Kern.atlasCountForZone(st, FactionHandle.SAFEZONE_ORDINAL),
                st.zones().safezoneChunks(), "safezone stat");
        assertEquals(Kern.atlasCountForZone(st, FactionHandle.WARZONE_ORDINAL),
                st.zones().warzoneChunks(), "warzone stat");
        assertTrue(Kern.allBanksNonNegative(st), "bank negative");
    }

    private static UUID[] players(int n) {
        UUID[] p = new UUID[n];
        for (int i = 0; i < n; i++) {
            p[i] = Kern.player(i);
        }
        return p;
    }

    private static UUID pickP(SplittableRandom r, UUID[] p) {
        return r.nextInt(100) < 85 ? p[r.nextInt(p.length)] : Kern.player(30 + r.nextInt(5));
    }

    private static int pickH(SplittableRandom r, int[] h) {
        return r.nextInt(100) < 85 ? h[r.nextInt(h.length)]
                : FactionHandle.handle(0, 40 + r.nextInt(5));
    }

    private static long key(SplittableRandom r) {
        return ChunkKeys.key(r.nextInt(9) - 4, r.nextInt(9) - 4);
    }

    private static long[] smallKeys(SplittableRandom r) {
        long[] k = new long[1 + r.nextInt(3)];
        for (int i = 0; i < k.length; i++) {
            k[i] = key(r);
        }
        return k;
    }

    private static long[] zoneKeys(SplittableRandom r) {
        if (r.nextInt(20) == 0) { // occasional bulk to exercise AM-5 zone paging
            long[] k = new long[1100];
            for (int i = 0; i < k.length; i++) {
                k[i] = ChunkKeys.key(i, 20);
            }
            return k;
        }
        return smallKeys(r);
    }

    /** A syntactically-valid random intent over the fixture handles/players (non-null fields). */
    private static Intent gen(SplittableRandom r, int[] h, UUID[] p, boolean life) {
        int n = life ? 36 : 31;
        switch (r.nextInt(n)) {
            case 0: return new Intent.ClaimChunks(pickP(r, p), pickH(r, h), 0, smallKeys(r), 0);
            case 1: return new Intent.UnclaimChunks(pickP(r, p), pickH(r, h), 0, smallKeys(r));
            case 2: return new Intent.AdminClaimChunks(pickH(r, h), 0, smallKeys(r), pickP(r, p));
            case 3: return new Intent.AdminUnclaimChunks(pickH(r, h), 0, smallKeys(r), pickP(r, p));
            case 4: return new Intent.SetZoneChunks(r.nextInt(2), 0, zoneKeys(r), pickP(r, p));
            case 5: return new Intent.RemoveZoneChunk(r.nextInt(2), 0, key(r), pickP(r, p));
            case 6: return new Intent.DeclareRelation(pickH(r, h), pickH(r, h), 1 + r.nextInt(4),
                    pickP(r, p));
            case 7: return new Intent.RecordDeath(pickP(r, p), r.nextBoolean() ? pickP(r, p) : null,
                    0, key(r));
            case 8: return new Intent.PowerTick(r.nextInt(5));
            case 9: return new Intent.AdminPowerSet(pickP(r, p), r.nextInt(15), pickP(r, p), "x");
            case 10: return new Intent.AdminPowerAdd(pickP(r, p), r.nextInt(8), pickP(r, p), "x");
            case 11: return new Intent.AdminPowerRemove(pickP(r, p), r.nextInt(8), pickP(r, p), "x");
            case 12: return new Intent.SetPowerFrozen(pickP(r, p), r.nextBoolean(), pickP(r, p), "x");
            case 13: return new Intent.CreditBank(pickH(r, h), r.nextInt(50), pickP(r, p), r.nextLong());
            case 14: return new Intent.RequestBankWithdrawal(pickH(r, h), r.nextInt(30) - 5,
                    pickP(r, p));
            case 15: return new Intent.SettleEscrow(1 + r.nextInt(60), r.nextInt(2));
            case 16: return new Intent.TransferBank(pickH(r, h), pickH(r, h), r.nextInt(30) - 5,
                    pickP(r, p));
            case 17: return new Intent.TaxSweep(r.nextInt(5));
            case 18: return new Intent.SendInvite(pickH(r, h), pickP(r, p), pickP(r, p));
            case 19: return new Intent.RevokeInvite(pickH(r, h), pickP(r, p), pickP(r, p));
            case 20: return new Intent.JoinFaction(pickH(r, h), pickP(r, p),
                    r.nextBoolean() ? Intent.OPEN_JOIN : 1 + r.nextInt(40));
            case 21: return new Intent.LeaveFaction(pickH(r, h), pickP(r, p));
            case 22: return new Intent.KickMember(pickH(r, h), pickP(r, p), pickP(r, p));
            case 23: return new Intent.PromoteMember(pickH(r, h), pickP(r, p), pickP(r, p));
            case 24: return new Intent.DemoteMember(pickH(r, h), pickP(r, p), pickP(r, p));
            case 25: return new Intent.SetFactionFlag(pickH(r, h), r.nextInt(6), r.nextBoolean(),
                    r.nextBoolean(), pickP(r, p));
            case 26: return new Intent.SetHome(pickH(r, h), 0, 1, 2, 3, 0, 0, pickP(r, p));
            case 27: return new Intent.SetWarp(pickH(r, h), "w" + r.nextInt(3), 0, 1, 2, 3, 0, 0,
                    pickP(r, p));
            case 28: return new Intent.CreateChest(pickH(r, h), "c" + r.nextInt(3), pickP(r, p));
            case 29: return r.nextBoolean()
                    ? new Intent.PlayerConnected(pickP(r, p), "N", "en")
                    : new Intent.PlayerDisconnected(pickP(r, p));
            case 30: return new Intent.UnclaimAll(pickP(r, p), pickH(r, h));
            // ── lifecycle (broad menu only) ──
            case 31: return new Intent.CreateFaction("Rnd" + r.nextInt(1_000_000), pickP(r, p));
            case 32: return new Intent.DisbandFaction(pickH(r, h), true, pickP(r, p));
            case 33: return new Intent.RenameFaction(pickH(r, h), "Ren" + r.nextInt(1_000_000),
                    pickP(r, p));
            case 34: return new Intent.SendMergeRequest(pickH(r, h), pickH(r, h), pickP(r, p));
            default: return new Intent.AcceptMergeRequest(pickH(r, h), pickH(r, h), pickP(r, p));
        }
    }
}
