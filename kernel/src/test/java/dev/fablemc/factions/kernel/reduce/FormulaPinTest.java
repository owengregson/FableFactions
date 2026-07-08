package dev.fablemc.factions.kernel.reduce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.config.BakedTables;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.config.EconomyConfig;
import dev.fablemc.factions.kernel.config.LandConfig;
import dev.fablemc.factions.kernel.config.PowerConfig;
import dev.fablemc.factions.kernel.config.RelationConfig;
import dev.fablemc.factions.kernel.config.RoleConfig;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.ClaimRules;
import dev.fablemc.factions.kernel.rules.InviteRules;
import dev.fablemc.factions.kernel.rules.MoneyMath;
import dev.fablemc.factions.kernel.rules.NameRules;
import dev.fablemc.factions.kernel.rules.PowerMath;
import dev.fablemc.factions.kernel.rules.RelationRules;
import dev.fablemc.factions.kernel.rules.RoleRules;
import dev.fablemc.factions.kernel.rules.ShieldWindow;
import dev.fablemc.factions.kernel.rules.TaxMath;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.OnlineSet;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.kernel.state.RelationKind;
import dev.fablemc.factions.kernel.vocab.PowerSource;

/**
 * Hand-computed pins for every formula named in the W2a research ground truth
 * (ref-bugs-concurrency Appendix, ref-services power/claim/economy, ref-commands-* relation/role,
 * ref-engines death/kill/raidable). Each case is computed by hand from the reference defaults.
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b>
 * test-confined fixtures; no shared state between tests.
 */
class FormulaPinTest {

    private static final double EPS = 1e-9;

    // ── max land = min(maxLand, floor(totalPower/perPower)), or maxLand if perPower<=0 ─────

    @Test
    void computeMaxLand() {
        LandConfig land = LandConfig.defaults(); // perPower 1.0, maxLand 500
        assertEquals(10, ClaimRules.computeMaxLand(land, 10.4));
        assertEquals(10, ClaimRules.computeMaxLand(land, 10.0));
        assertEquals(0, ClaimRules.computeMaxLand(land, 0.0));
        assertEquals(500, ClaimRules.computeMaxLand(land, 600.0)); // hard ceiling
        assertEquals(500, ClaimRules.computeMaxLand(land, 500.0));

        LandConfig perTwoAndHalf = new LandConfig(0, 200, 2.5, 500, false, true, false, true,
                false, false, 8);
        assertEquals(4, ClaimRules.computeMaxLand(perTwoAndHalf, 10.0)); // floor(4.0)
        assertEquals(3, ClaimRules.computeMaxLand(perTwoAndHalf, 9.9));  // floor(3.96)

        LandConfig perZero = new LandConfig(0, 200, 0.0, 42, false, true, false, true, false,
                false, 8);
        assertEquals(42, ClaimRules.computeMaxLand(perZero, 5.0)); // perPower<=0 => maxLand
    }

    // ── death loss = baseLoss * multiplier^streak (streak>0) ───────────────────────────────

    @Test
    void deathLossStreakEscalation() {
        PowerConfig pc = Kern.pcDeathStreak(); // base 4.0, mult 1.5, streaks enabled
        assertEquals(4.0, PowerMath.deathLoss(pc, 0), EPS);
        assertEquals(6.0, PowerMath.deathLoss(pc, 1), EPS);   // 4 * 1.5
        assertEquals(9.0, PowerMath.deathLoss(pc, 2), EPS);   // 4 * 2.25
        assertEquals(13.5, PowerMath.deathLoss(pc, 3), EPS);  // 4 * 3.375
    }

    // ── next streak: prev+1 inside the window, else 0; disabled => 0 ───────────────────────

    @Test
    void nextStreakWindow() {
        PowerConfig off = PowerConfig.defaults(); // deathStreakEnabled == false
        assertEquals(0, PowerMath.nextStreak(off, 3, 1000L, 1000L));

        PowerConfig on = Kern.pcDeathStreak(); // window 600s => 600_000 ms
        long now = 10_000_000L;
        assertEquals(0, PowerMath.nextStreak(on, 3, 0L, now));               // no prior death
        assertEquals(4, PowerMath.nextStreak(on, 3, now - 100_000L, now));   // within window
        assertEquals(4, PowerMath.nextStreak(on, 3, now - 600_000L, now));   // exactly on edge
        assertEquals(0, PowerMath.nextStreak(on, 3, now - 600_001L, now));   // just outside
    }

    // ── kill scale: gain * clamp(victimBefore/killerPower, min, max) ───────────────────────

    @Test
    void killGainScaling() {
        PowerConfig off = PowerConfig.defaults(); // killScaleEnabled false, gain 2.0
        assertEquals(2.0, PowerMath.killGain(off, 5.0, 10.0), EPS);
        assertEquals(2.0, PowerMath.killGain(off, 0.0, 0.0), EPS);

        PowerConfig on = Kern.pcKillScale(0.25, 2.0); // gain 2.0
        assertEquals(1.0, PowerMath.killGain(on, 5.0, 10.0), EPS);   // ratio 0.5 => 2*0.5
        assertEquals(4.0, PowerMath.killGain(on, 100.0, 10.0), EPS); // ratio 10 clamp 2 => 2*2
        assertEquals(0.5, PowerMath.killGain(on, 1.0, 10.0), EPS);   // ratio .1 clamp .25 => 2*.25
        assertEquals(0.5, PowerMath.killGain(on, 5.0, 0.0), EPS);    // killerPower 0 => gain*min
    }

    // ── grace period: no loss while now-start < grace*1000 ─────────────────────────────────

    @Test
    void gracePeriod() {
        PowerConfig pc = PowerConfig.defaults(); // grace 3600s => 3_600_000 ms
        assertTrue(PowerMath.inGracePeriod(pc, 3_599_999L, 0L));
        assertFalse(PowerMath.inGracePeriod(pc, 3_600_000L, 0L));
        assertTrue(PowerMath.inGracePeriod(pc, 1_000_000L + 3_599_999L, 1_000_000L));
    }

    // ── per-event clamp is AUTOMATIC-only (D-2) ────────────────────────────────────────────

    @Test
    void eventClampAutomaticOnly() {
        PowerConfig pc = Kern.pcEventClamp(2.0);
        assertEquals(-2.0, PowerMath.applyEventClamp(pc, PowerSource.DEATH, -5.0), EPS);
        assertEquals(2.0, PowerMath.applyEventClamp(pc, PowerSource.REGEN_ONLINE, 5.0), EPS);
        assertEquals(2.0, PowerMath.applyEventClamp(pc, PowerSource.KILL, 9.0), EPS);
        // Non-automatic sources are never clamped.
        assertEquals(-5.0, PowerMath.applyEventClamp(pc, PowerSource.BUY, -5.0), EPS);
        assertEquals(9.0, PowerMath.applyEventClamp(pc, PowerSource.ADMIN_ADD, 9.0), EPS);
        // maxChangePerEvent <= 0 disables the clamp entirely.
        PowerConfig none = PowerConfig.defaults();
        assertEquals(-5.0, PowerMath.applyEventClamp(none, PowerSource.DEATH, -5.0), EPS);
    }

    // ── full apply pipeline: clamp + no-change epsilon + freeze gating ──────────────────────

    @Test
    void applyPipelineClampAndEpsilon() {
        PowerConfig pc = PowerConfig.defaults();
        BakedTables baked = BakedTables.defaults(pc);
        // death from full power: 10 - 4 => 6
        PowerMath.PowerResult r = PowerMath.apply(pc, baked, 10.0, false, PowerSource.DEATH, 4.0,
                false, -1, PowerMath.ZONE_WILDERNESS, "DEATH");
        assertTrue(r.changed());
        assertEquals(6.0, r.after(), EPS);
        assertEquals(-4.0, r.effectiveDelta(), EPS);

        // death from 0 power: clamps at min, effective 0 => NO_CHANGE
        PowerMath.PowerResult z = PowerMath.apply(pc, baked, 0.0, false, PowerSource.DEATH, 4.0,
                false, -1, PowerMath.ZONE_WILDERNESS, "DEATH");
        assertFalse(z.changed());
        assertEquals(0.0, z.effectiveDelta(), EPS);
        assertEquals("NO_CHANGE", z.reasonCode());

        // frozen blocks an automatic source (freezeBlocksAutomatic default true)
        PowerMath.PowerResult f = PowerMath.apply(pc, baked, 8.0, true, PowerSource.DEATH, 4.0,
                false, -1, PowerMath.ZONE_WILDERNESS, "DEATH");
        assertTrue(f.blockedByFreeze());
        assertFalse(f.changed());

        // admin bypass ignores freeze
        PowerMath.PowerResult a = PowerMath.apply(pc, baked, 8.0, true, PowerSource.ADMIN_ADD, 1.0,
                true, -1, PowerMath.ZONE_WILDERNESS, "ADMIN_ADD");
        assertTrue(a.changed());
        assertEquals(9.0, a.after(), EPS);
    }

    // ── settle == KernelSnapshot.powerAt (the lazy-accrual equivalence) ────────────────────

    @Test
    void settleEqualsPowerAt() {
        boolean[] onlines = {true, false};
        boolean[] frozens = {true, false};
        double[] bases = {0.0, 2.5, 7.0, 10.0, 12.0 /* above max */, -3.0 /* below min */};
        long[] asOfTicks = {0L, 5L};
        int[] ticks = {0, 1, 3, 10, 50};
        for (boolean online : onlines) {
            for (boolean frozen : frozens) {
                for (double base : bases) {
                    for (long asOf : asOfTicks) {
                        for (int tick : ticks) {
                            ConfigImage cfg = ConfigImage.defaults();
                            PowerConfig pc = cfg.power();
                            double expect = PowerMath.settle(pc, online, base, asOf, frozen, tick);
                            KernelSnapshot snap = snapshotWith(cfg, online, base, asOf, frozen);
                            assertEquals(expect, snap.powerAt(0, tick), EPS,
                                    "online=" + online + " frozen=" + frozen + " base=" + base
                                            + " asOf=" + asOf + " tick=" + tick);
                        }
                    }
                }
            }
        }
    }

    private static KernelSnapshot snapshotWith(ConfigImage cfg, boolean online, double base,
                                               long asOfTick, boolean frozen) {
        PlayerLedger ledger = PlayerLedger.empty()
                .withNewMember(0, UUID.randomUUID(), "P")
                .withPower(0, base, asOfTick)
                .withPowerFrozen(0, frozen);
        OnlineSet set = online ? OnlineSet.empty().with(0) : OnlineSet.empty();
        return new KernelSnapshot(KernelState.empty(cfg).withLedger(ledger).withOnline(set));
    }

    // ── money round2 + parser suffixes ─────────────────────────────────────────────────────

    @Test
    void moneyRound2() {
        assertEquals(3.33, MoneyMath.round2(10.0 / 3.0), 0.0);
        assertEquals(2.5, MoneyMath.round2(2.5), 0.0);
        assertEquals(0.3, MoneyMath.round2(0.1 + 0.2), 0.0);
        assertEquals(1.24, MoneyMath.round2(1.235), 0.0);
        assertEquals(-2.0, MoneyMath.round2(-2.0), 0.0);
    }

    @Test
    void moneyParse() {
        assertEquals(1000.0, MoneyMath.parse("1k"), EPS);
        assertEquals(2.5e6, MoneyMath.parse("2.5m"), EPS);
        assertEquals(3e9, MoneyMath.parse("3b"), EPS);
        assertEquals(1e12, MoneyMath.parse("1t"), EPS);
        assertEquals(42.0, MoneyMath.parse("42"), EPS);
        assertEquals(5000.0, MoneyMath.parse("  5K "), EPS); // trim + case-fold
        assertTrue(MoneyMath.isInvalid(MoneyMath.parse(null)));
        assertTrue(MoneyMath.isInvalid(MoneyMath.parse("")));
        assertTrue(MoneyMath.isInvalid(MoneyMath.parse("abc")));
        assertTrue(MoneyMath.isInvalid(MoneyMath.parse("k"))); // empty numeric part
    }

    // ── tax chain (round2) ─────────────────────────────────────────────────────────────────

    @Test
    void taxChain() {
        EconomyConfig off = EconomyConfig.defaults(); // taxEnabled false
        assertEquals(0.0, TaxMath.taxFor(off, 1000.0), 0.0);

        EconomyConfig on = new EconomyConfig(true, 50.0, 100.0, true, 0.05, 24, 0.0, 0.01, 8);
        assertEquals(50.0, TaxMath.taxFor(on, 1000.0), EPS);       // round2(1000*0.05)
        assertEquals(950.0, TaxMath.bankAfter(1000.0, 50.0), EPS);
        assertEquals(0.0, TaxMath.taxFor(on, 0.0), 0.0);           // bank <= minBank

        // computed below minCharge => skip
        EconomyConfig hiMin = new EconomyConfig(true, 50.0, 100.0, true, 0.05, 24, 0.0, 5.0, 8);
        assertEquals(0.0, TaxMath.taxFor(hiMin, 10.0), 0.0);       // round2(0.5) < 5.0

        // charge never exceeds the bank
        EconomyConfig full = new EconomyConfig(true, 50.0, 100.0, true, 1.0, 24, 0.0, 0.01, 8);
        assertEquals(7.0, TaxMath.taxFor(full, 7.0), EPS);
        assertEquals(0.0, TaxMath.bankAfter(7.0, 7.0), EPS);
    }

    // ── invite TTL ─────────────────────────────────────────────────────────────────────────

    @Test
    void inviteTtl() {
        assertEquals(72L * 3_600_000L, InviteRules.ttlMillis(72));
        assertEquals(3_600_000L, InviteRules.ttlMillis(0));   // floored at 1 hour
        assertEquals(3_600_000L, InviteRules.ttlMillis(-5));  // floored at 1 hour
        long created = 1_000L;
        assertTrue(InviteRules.isActive(created, 72, created + 72L * 3_600_000L));
        assertFalse(InviteRules.isActive(created, 72, created + 72L * 3_600_000L + 1L));
    }

    // ── relation effectiveKind truth table ─────────────────────────────────────────────────

    @Test
    void effectiveKind() {
        byte A = RelationKind.ALLY;
        byte T = RelationKind.TRUCE;
        byte N = RelationKind.NEUTRAL;
        byte E = RelationKind.ENEMY;
        assertEquals(E, RelationRules.effectiveKind(E, N));
        assertEquals(E, RelationRules.effectiveKind(N, E));
        assertEquals(E, RelationRules.effectiveKind(A, E));
        assertEquals(A, RelationRules.effectiveKind(A, A));
        assertEquals(N, RelationRules.effectiveKind(A, N));
        assertEquals(N, RelationRules.effectiveKind(A, T));
        assertEquals(T, RelationRules.effectiveKind(T, T));
        assertEquals(N, RelationRules.effectiveKind(T, N));
        assertEquals(N, RelationRules.effectiveKind(N, N));
        // symmetric in its arguments
        assertEquals(RelationRules.effectiveKind(A, T), RelationRules.effectiveKind(T, A));
    }

    // ── relation limit (ally/truce only, only on change) ───────────────────────────────────

    @Test
    void relationLimit() {
        RelationConfig cfg = RelationConfig.defaults(); // max 5 each
        byte[] fourAllies = {RelationKind.ALLY, RelationKind.ALLY, RelationKind.ALLY, RelationKind.ALLY};
        byte[] fiveAllies = {RelationKind.ALLY, RelationKind.ALLY, RelationKind.ALLY,
                RelationKind.ALLY, RelationKind.ALLY};
        assertTrue(RelationRules.withinRelationLimit(cfg, fourAllies, 4, RelationKind.NEUTRAL,
                RelationKind.ALLY));
        assertFalse(RelationRules.withinRelationLimit(cfg, fiveAllies, 5, RelationKind.NEUTRAL,
                RelationKind.ALLY));
        // no change (already ally toward that target) => always allowed
        assertTrue(RelationRules.withinRelationLimit(cfg, fiveAllies, 5, RelationKind.ALLY,
                RelationKind.ALLY));
        // enemy/neutral are unlimited
        assertTrue(RelationRules.withinRelationLimit(cfg, fiveAllies, 5, RelationKind.NEUTRAL,
                RelationKind.ENEMY));
        assertTrue(RelationRules.withinRelationLimit(cfg, fiveAllies, 5, RelationKind.ALLY,
                RelationKind.NEUTRAL));
    }

    // ── role stepping is by SORTED priority, not array index ───────────────────────────────

    @Test
    void stepRankByPriority() {
        Rank member = new Rank("m", "member", null, 10);
        Rank officer = new Rank("o", "officer", null, 50);
        Rank owner = new Rank("w", "owner", null, 100);
        Rank[] sorted = {member, officer, owner}; // indices 0,1,2
        assertEquals(1, RoleRules.stepRank(sorted, 0, true));   // member -> officer
        assertEquals(-1, RoleRules.stepRank(sorted, 1, true));  // officer -> owner blocked
        assertEquals(0, RoleRules.stepRank(sorted, 1, false));  // officer -> member
        assertEquals(-1, RoleRules.stepRank(sorted, 0, false)); // member -> none lower

        // Scrambled array order: promotion still follows priority, not slot order.
        Rank[] scrambled = {owner, member, officer}; // idx 0=owner,1=member,2=officer
        assertEquals(2, RoleRules.stepRank(scrambled, 1, true));  // member(idx1) -> officer(idx2)
        assertEquals(-1, RoleRules.stepRank(scrambled, 2, true)); // officer(idx2) -> owner blocked
        assertEquals(1, RoleRules.stepRank(scrambled, 2, false)); // officer(idx2) -> member(idx1)
    }

    @Test
    void canManageAndCustomPriority() {
        Rank member = new Rank("m", "member", null, 10);
        Rank officer = new Rank("o", "officer", null, 50);
        assertTrue(RoleRules.canManage(officer, member));
        assertFalse(RoleRules.canManage(member, officer));
        assertFalse(RoleRules.canManage(officer, officer)); // strict >
        assertFalse(RoleRules.canManage(null, member));

        RoleConfig role = RoleConfig.defaults(); // [11,99]
        assertTrue(RoleRules.validCustomPriority(role, 11));
        assertTrue(RoleRules.validCustomPriority(role, 99));
        assertFalse(RoleRules.validCustomPriority(role, 10));
        assertFalse(RoleRules.validCustomPriority(role, 100));

        // misconfigured (min>max) falls back to [11,99]
        RoleConfig bad = new RoleConfig(true, false, 80, 20, 0, true, 32, "", "", "");
        assertEquals(11, RoleRules.priorityLow(bad));
        assertEquals(99, RoleRules.priorityHigh(bad));
    }

    // ── name rules ─────────────────────────────────────────────────────────────────────────

    @Test
    void nameRules() {
        assertEquals(ReasonCode.NAME_TOO_SHORT, NameRules.validateFormat("ab"));
        assertEquals(ReasonCode.NAME_TOO_SHORT, NameRules.validateFormat(null));
        assertEquals(ReasonCode.NAME_TOO_LONG, NameRules.validateFormat("x".repeat(33)));
        assertEquals(ReasonCode.NAME_INVALID, NameRules.validateFormat("abc!"));
        assertNull(NameRules.validateFormat("Abc_-9"));
        assertNull(NameRules.validateFormat("x".repeat(32)));

        NameIndex names = NameIndex.empty().with(NameIndex.fold("Alpha"), 2);
        assertEquals(ReasonCode.NAME_TAKEN, NameRules.validate("alpha", names)); // fold-case clash
        assertNull(NameRules.validate("Beta", names));
    }

    // ── shield window (UTC hour, wrap, always/never) ───────────────────────────────────────

    @Test
    void shieldWindow() {
        long hour5 = 5L * 3_600_000L; // UTC hour 5
        assertEquals(5, ShieldWindow.utcHour(hour5));
        assertTrue(ShieldWindow.isActive(5, 1, hour5));      // [5,6) contains 5
        assertFalse(ShieldWindow.isActive(7, 1, hour5));     // [7,8) excludes 5
        assertTrue(ShieldWindow.isActive(3, 3, hour5));      // [3,6) contains 5
        // wrap past midnight: start 23, dur 2 covers 23 and 0
        assertTrue(ShieldWindow.isActive(23, 2, 0L));        // hour 0 covered
        assertFalse(ShieldWindow.isActive(23, 2, 1L * 3_600_000L)); // hour 1 not covered
        assertTrue(ShieldWindow.isActive(0, 24, hour5));     // full-day always active
        assertFalse(ShieldWindow.isActive(0, 0, hour5));     // zero duration inactive
        assertFalse(ShieldWindow.isActive(Faction.NO_SHIELD, 4, hour5)); // unset start
    }

    // ── border 4-neighbor rule ─────────────────────────────────────────────────────────────

    @Test
    void borderFourNeighbor() {
        // faction ord 2 owns (0,0); claiming (1,0) with land=1 => own neighbor => valid.
        KernelState st = twoFactionState();
        int aHandle = FactionHandle.handle(0, 2);
        st = st.withClaims(st.claims().withClaim(0, ChunkKeys.key(0, 0), aHandle));
        assertTrue(ClaimRules.isValidBorder(st, aHandle, 0, ChunkKeys.key(1, 0), 1));
        // first claim (land 0) is always valid regardless of neighbors
        assertTrue(ClaimRules.isValidBorder(st, aHandle, 0, ChunkKeys.key(99, 99), 0));
        // wilderness neighbor => valid (claim outward)
        assertTrue(ClaimRules.isValidBorder(st, aHandle, 0, ChunkKeys.key(50, 50), 1));

        // surround (10,10) on all four sides with an ENEMY faction => invalid border
        int bHandle = FactionHandle.handle(0, 3);
        long center = ChunkKeys.key(10, 10);
        st = st.withClaims(st.claims()
                .withClaim(0, ChunkKeys.key(11, 10), bHandle)
                .withClaim(0, ChunkKeys.key(9, 10), bHandle)
                .withClaim(0, ChunkKeys.key(10, 11), bHandle)
                .withClaim(0, ChunkKeys.key(10, 9), bHandle));
        st = enemy(st, 2, 3);
        assertFalse(ClaimRules.isValidBorder(st, aHandle, 0, center, 1));
    }

    // ── buffer zone (Chebyshev spacing) ────────────────────────────────────────────────────

    @Test
    void bufferZone() {
        KernelState st = twoFactionState();
        int aHandle = FactionHandle.handle(0, 2);
        int bHandle = FactionHandle.handle(0, 3);
        // buffer 0 => always ok
        assertTrue(ClaimRules.bufferZoneOk(st, aHandle, 0, ChunkKeys.key(5, 5), 0));
        // buffer 1 with an OTHER faction's chunk within Chebyshev 1 => rejected
        st = st.withClaims(st.claims().withClaim(0, ChunkKeys.key(6, 6), bHandle));
        assertFalse(ClaimRules.bufferZoneOk(st, aHandle, 0, ChunkKeys.key(5, 5), 1));
        // own chunk within range does not block
        KernelState st2 = twoFactionState().withClaims(
                twoFactionState().claims().withClaim(0, ChunkKeys.key(6, 6), aHandle));
        assertTrue(ClaimRules.bufferZoneOk(st2, aHandle, 0, ChunkKeys.key(5, 5), 1));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private static KernelState twoFactionState() {
        Faction a = Kern.faction(2, "Alpha", Kern.player(0));
        Faction b = Kern.faction(3, "Beta", Kern.player(1));
        FactionArena arena = FactionArena.empty().withFaction(2, a).withFaction(3, b);
        return KernelState.empty(ConfigImage.defaults()).withFactions(arena);
    }

    private static KernelState enemy(KernelState st, int ordA, int ordB) {
        Faction a = st.factions().at(ordA);
        RelationEdges.Edges eff = RelationEdges.with(a.relEff(), a.relEffKind(), a.relEff().length,
                ordB, RelationKind.ENEMY);
        Faction a2 = dev.fablemc.factions.kernel.rules.FactionEdit.withRelations(a, a.relOut(),
                a.relOutKind(), eff.ordinals(), eff.kinds());
        return st.withFactions(st.factions().replace(ordA, a2));
    }
}
