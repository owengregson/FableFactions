package dev.fablemc.factions.kernel.reduce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.ClaimIntent;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.reduce.Kern.Session;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.vocab.ClaimMode;

/**
 * Pins for the two W25 kernel-correctness fixes: the AM-4 {@code ReconcileSweep} (aggregate
 * drift detection + self-heal + WARN) and the unclaim / unclaim-all reduce-time actor
 * re-validation and zero-progress paging termination (finding #42).
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b>
 * test-confined fixtures; no shared state between tests.
 */
class ReconcileAndUnclaimGuardTest {

    // ── AM-4 ReconcileSweep (#40 & #45) ─────────────────────────────────────────────────────

    @Test
    void reconcileHealsLandDriftAndEmitsWarn() {
        // Faction stores landCount=7 with an empty reverse index, but the atlas — the ground
        // truth — holds only 3 of its chunks.
        int ord = FactionHandle.FIRST_NORMAL_ORDINAL;
        int handle = FactionHandle.handle(0, ord);
        KernelState st = driftState(3, 7);
        Session s = new Session(st);

        List<Effect> eff = s.apply(new SystemIntent.ReconcileSweep(0));

        Faction healed = s.state.factions().resolve(handle);
        assertEquals(3, healed.landCount(), "landCount healed to atlas ground truth");
        assertEquals(3, healed.claims().count(), "reverse claim index rebuilt to ground truth");

        assertEquals(1, Kern.countType(eff, SystemEffect.AggregateDriftDetected.class));
        SystemEffect.AggregateDriftDetected drift = firstDrift(eff);
        assertNotNull(drift);
        assertEquals(handle, drift.faction());
        assertEquals("landCount", drift.aggregate());
        assertEquals(7, drift.stored());
        assertEquals(3, drift.recomputed());
    }

    @Test
    void reconcileIsSilentWhenAggregatesAgree() {
        // A fully-consistent faction: landCount, reverse index, and atlas all show 3 claims.
        int ord = FactionHandle.FIRST_NORMAL_ORDINAL;
        int handle = FactionHandle.handle(0, ord);
        KernelState st = consistentState(3);
        Session s = new Session(st);

        List<Effect> eff = s.apply(new SystemIntent.ReconcileSweep(0));

        assertEquals(0, Kern.countType(eff, SystemEffect.AggregateDriftDetected.class),
                "no drift => no WARN");
        assertEquals(3, s.state.factions().resolve(handle).landCount());
    }

    @Test
    void reconcileRotatesAcrossPagesAndHealsLater() {
        // More factions than one page: the drifted faction sits in the SECOND window, so only a
        // paged continuation reaches it.
        int base = FactionHandle.FIRST_NORMAL_ORDINAL;
        int count = Reducer.PAGE_SIZE + 4;            // ordinals base..base+count-1
        int driftOrd = base + count - 1;              // last ordinal => second page
        int driftHandle = FactionHandle.handle(0, driftOrd);
        KernelState st = manyFactionsWithLateDrift(base, count, driftOrd);
        int highWater = st.factions().highWater();

        // One step reconciles the first window [base, base+PAGE_SIZE) (all consistent) and asks
        // for a continuation at the next window.
        Reducer.Outcome page1 = step(st, new SystemIntent.ReconcileSweep(0), Origin.SYSTEM_ORIGIN);
        assertEquals(0, Kern.countType(page1.effects(), SystemEffect.AggregateDriftDetected.class),
                "first page is all-consistent");
        SystemIntent.ReconcileSweep next = continuationSweep(page1.effects());
        assertNotNull(next, "a continuation rotates the cursor forward");
        assertEquals(base + Reducer.PAGE_SIZE, next.cursor());
        assertTrue(next.cursor() < highWater);

        // Full drive: the continuation reconciles the second window and heals the late faction.
        Session s = new Session(st);
        List<Effect> all = s.apply(new SystemIntent.ReconcileSweep(0));
        assertEquals(1, Kern.countType(all, SystemEffect.AggregateDriftDetected.class));
        assertEquals(1, s.state.factions().resolve(driftHandle).landCount(),
                "late-window faction healed via continuation");
    }

    @Test
    void reconcileBeyondHighWaterIsNoOp() {
        KernelState st = consistentState(2);
        int highWater = st.factions().highWater();
        Reducer.Outcome out = step(st, new SystemIntent.ReconcileSweep(highWater + 10),
                Origin.SYSTEM_ORIGIN);
        assertTrue(out.effects().isEmpty(), "cursor past the arena reconciles nothing");
    }

    // ── unclaim / unclaim-all actor re-validation (#42) ─────────────────────────────────────

    @Test
    void unclaimChunksRejectsNonMemberActor() {
        Owned o = ownedFactionWithOneClaim();
        Session s = o.s();
        int fa = o.handle();
        long key = ChunkKeys.key(0, 0);

        // A non-member's unclaim is rejected at reduce time (was: silently applied).
        assertEquals(ReasonCode.NOT_IN_FACTION, Kern.firstReason(
                s.apply(new ClaimIntent.UnclaimChunks(Kern.player(5), fa, 0, new long[] {key}))));
        assertEquals(1, s.state.factions().resolve(fa).landCount(), "land untouched by the reject");

        // The owning member can still unclaim.
        assertEquals(1, Kern.countType(
                s.apply(new ClaimIntent.UnclaimChunks(Kern.player(0), fa, 0, new long[] {key})),
                ClaimEffect.ClaimRemoved.class));
        assertEquals(0, s.state.factions().resolve(fa).landCount());
    }

    @Test
    void unclaimAllPlayerOriginRequiresOwnerMembership() {
        Owned o = ownedFactionWithOneClaim();
        int fa = o.handle();

        // Valid owner, player origin: not rejected, the claim is removed.
        Reducer.Outcome ok = step(o.s().state, new ClaimIntent.UnclaimAll(Kern.player(0), fa),
                Origin.player(Kern.player(0)));
        assertFalse(hasReject(ok.effects()), "the owner is authorized");
        assertEquals(1, Kern.countType(ok.effects(), ClaimEffect.ClaimRemoved.class));

        // A non-member issuing the same intent (player origin) is rejected; land untouched.
        Reducer.Outcome nonMember = step(o.s().state, new ClaimIntent.UnclaimAll(Kern.player(5), fa),
                Origin.player(Kern.player(5)));
        assertEquals(ReasonCode.NOT_IN_FACTION, firstReason(nonMember.effects()));
        assertEquals(0, Kern.countType(nonMember.effects(), ClaimEffect.ClaimRemoved.class));
    }

    @Test
    void unclaimAllRejectsDemotedFormerOwner() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        openJoin(s, fa, Kern.player(1));
        s.connect(Kern.player(0));
        s.setPower(Kern.player(0), 10.0);
        s.apply(new ClaimIntent.ClaimChunks(Kern.player(0), fa, 0,
                new long[] {ChunkKeys.key(0, 0)}, ClaimMode.SINGLE));
        // Ownership moves to player1; player0 is demoted to officer.
        s.apply(new LifecycleIntent.TransferOwnership(fa, Kern.player(1), Kern.player(0)));

        // player0 (no longer owner) had queued an unclaim-all: rejected at reduce time.
        Reducer.Outcome demoted = step(s.state, new ClaimIntent.UnclaimAll(Kern.player(0), fa),
                Origin.player(Kern.player(0)));
        assertEquals(ReasonCode.MUST_BE_LEADER, firstReason(demoted.effects()));
        assertEquals(1, s.state.factions().resolve(fa).landCount(), "land untouched");
    }

    @Test
    void unclaimAllAdminOriginBypassesMembershipButPlayerOriginDoesNot() {
        // bigClaimState's owner has NO ledger row (not a member), so the two origins diverge.
        KernelState st = Kern.bigClaimState(3);
        int handle = Kern.handle(FactionHandle.FIRST_NORMAL_ORDINAL);

        // Admin origin: the membership/owner gate is bypassed; the claims are removed.
        Reducer.Outcome admin = step(st, new ClaimIntent.UnclaimAll(Kern.player(0), handle),
                Origin.admin(Kern.player(50)));
        assertFalse(hasReject(admin.effects()), "admin unclaim-all bypasses the actor gate");
        assertEquals(3, Kern.countType(admin.effects(), ClaimEffect.ClaimRemoved.class));

        // Player origin with a non-member actor: rejected.
        Reducer.Outcome player = step(st, new ClaimIntent.UnclaimAll(Kern.player(0), handle),
                Origin.player(Kern.player(0)));
        assertEquals(ReasonCode.NOT_IN_FACTION, firstReason(player.effects()));
    }

    // ── zero-progress paging termination (#42) ──────────────────────────────────────────────

    @Test
    void unclaimAllTerminatesWhenAPageRemovesZeroDespiteLandCountDrift() {
        // landCount says 5, but the atlas holds none of the faction's chunks. Keying the paging
        // loop on landCount would spin forever; keying on "claims actually removed" terminates.
        int ord = FactionHandle.FIRST_NORMAL_ORDINAL;
        int handle = FactionHandle.handle(0, ord);
        Faction f = FactionEdit.withLand(Kern.faction(ord, "Drift", Kern.player(0)), 5,
                FactionClaimList.empty());
        // Empty atlas (KernelState.empty default) => the faction's real claim count is 0.
        KernelState st = KernelState.empty(Kern.cfgDefault())
                .withFactions(FactionArena.empty().withFaction(ord, f))
                .withFactionNames(NameIndex.empty().with(f.nameFolded(), ord));

        Session s = new Session(st);
        // If this looped forever the harness would throw "continuation runaway"; reaching the
        // assertions proves termination.
        List<Effect> eff = s.apply(new ClaimIntent.UnclaimAll(Kern.player(0), handle));
        assertEquals(0, Kern.countType(eff, ClaimEffect.ClaimRemoved.class), "nothing to remove");
        assertNotNull(s.state.factions().resolve(handle), "faction still present");
    }

    // ── fixtures & helpers ──────────────────────────────────────────────────────────────────

    /** A faction at the first normal ordinal owning {@code realClaims} chunks, with {@code storedLand}
     *  as its (possibly drifted) stored landCount and an empty reverse index. */
    private static KernelState driftState(int realClaims, int storedLand) {
        int ord = FactionHandle.FIRST_NORMAL_ORDINAL;
        int handle = FactionHandle.handle(0, ord);
        dev.fablemc.factions.kernel.state.ClaimAtlas.Builder atlas =
                new dev.fablemc.factions.kernel.state.ClaimAtlas.Builder();
        for (int i = 0; i < realClaims; i++) {
            atlas.put(0, ChunkKeys.key(i, 0), handle);
        }
        Faction f = FactionEdit.withLand(Kern.faction(ord, "Drift", Kern.player(0)), storedLand,
                FactionClaimList.empty());
        return KernelState.empty(Kern.cfgDefault())
                .withFactions(FactionArena.empty().withFaction(ord, f))
                .withClaims(atlas.build())
                .withFactionNames(NameIndex.empty().with(f.nameFolded(), ord));
    }

    /** A fully-consistent faction: landCount, reverse index and atlas all show {@code n} claims. */
    private static KernelState consistentState(int n) {
        int ord = FactionHandle.FIRST_NORMAL_ORDINAL;
        int handle = FactionHandle.handle(0, ord);
        dev.fablemc.factions.kernel.state.ClaimAtlas.Builder atlas =
                new dev.fablemc.factions.kernel.state.ClaimAtlas.Builder();
        FactionClaimList list = FactionClaimList.empty();
        for (int i = 0; i < n; i++) {
            atlas.put(0, ChunkKeys.key(i, 0), handle);
            list = list.add(0, ChunkKeys.key(i, 0));
        }
        Faction f = FactionEdit.withLand(Kern.faction(ord, "Fine", Kern.player(0)), n, list);
        return KernelState.empty(Kern.cfgDefault())
                .withFactions(FactionArena.empty().withFaction(ord, f))
                .withClaims(atlas.build())
                .withFactionNames(NameIndex.empty().with(f.nameFolded(), ord));
    }

    /** {@code count} factions from ordinal {@code base}, all consistent except {@code driftOrd},
     *  which stores landCount 9 while the atlas holds exactly one of its chunks. */
    private static KernelState manyFactionsWithLateDrift(int base, int count, int driftOrd) {
        dev.fablemc.factions.kernel.state.ClaimAtlas.Builder atlas =
                new dev.fablemc.factions.kernel.state.ClaimAtlas.Builder();
        FactionArena arena = FactionArena.empty();
        for (int k = 0; k < count; k++) {
            int ord = base + k;
            int stored = (ord == driftOrd) ? 9 : 0;
            Faction f = FactionEdit.withLand(Kern.faction(ord, "F" + ord, Kern.player(0)), stored,
                    FactionClaimList.empty());
            arena = arena.withFaction(ord, f);
            if (ord == driftOrd) {
                atlas.put(0, ChunkKeys.key(0, 0), FactionHandle.handle(0, ord));
            }
        }
        return KernelState.empty(Kern.cfgDefault()).withFactions(arena).withClaims(atlas.build());
    }

    /** A player-owned faction (player0 is an owner-ranked member) holding one claim at (0,0). */
    private record Owned(Session s, int handle) {
    }

    private static Owned ownedFactionWithOneClaim() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        s.connect(Kern.player(0));
        s.setPower(Kern.player(0), 10.0);
        s.apply(new ClaimIntent.ClaimChunks(Kern.player(0), fa, 0,
                new long[] {ChunkKeys.key(0, 0)}, ClaimMode.SINGLE));
        return new Owned(s, fa);
    }

    private static void openJoin(Session s, int faction, java.util.UUID p) {
        s.apply(new PrefIntent.SetFactionFlag(faction, Faction.FLAG_OPEN, true, false, Kern.player(0)));
        s.apply(new MembershipIntent.JoinFaction(faction, p, MembershipIntent.OPEN_JOIN));
    }

    private static Reducer.Outcome step(KernelState st, Intent intent, Origin origin) {
        IntentEnvelope env = new IntentEnvelope(1L, 5_000_000L, 0, 0x9E3779B97F4A7C15L, origin, intent);
        return Reducer.apply(st, env);
    }

    private static SystemEffect.AggregateDriftDetected firstDrift(List<Effect> effects) {
        for (Effect e : effects) {
            if (e instanceof SystemEffect.AggregateDriftDetected d) {
                return d;
            }
        }
        return null;
    }

    private static SystemIntent.ReconcileSweep continuationSweep(List<Effect> effects) {
        for (Effect e : effects) {
            if (e instanceof SystemEffect.ContinuationRequested cr
                    && cr.next() instanceof SystemIntent.ReconcileSweep rs) {
                return rs;
            }
        }
        return null;
    }

    private static boolean hasReject(List<Effect> effects) {
        return Kern.countType(effects, FeedbackEffect.Rejected.class) > 0;
    }

    private static ReasonCode firstReason(List<Effect> effects) {
        return Kern.firstReason(effects);
    }
}
