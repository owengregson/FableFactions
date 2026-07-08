package dev.fablemc.factions.kernel.reduce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.reduce.Kern.Session;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.RelationKind;

/** Targeted behavioural pins for the reducer's membership / role / relation / claim / power paths. */
class ReducerScenarioTest {

    // ── lifecycle & naming ─────────────────────────────────────────────────────────────────

    @Test
    void createRejectsDuplicateFoldedNameAndReValidates() {
        Session s = Session.empty(Kern.cfgDefault());
        assertTrue(s.createFaction("Alpha", Kern.player(0)) > 0);
        // fold-case duplicate
        List<Effect> dup = s.apply(new Intent.CreateFaction("alpha", Kern.player(1)));
        assertEquals(ReasonCode.NAME_TAKEN, Kern.firstReason(dup));
        // second faction by an already-in-faction owner
        List<Effect> already = s.apply(new Intent.CreateFaction("Beta", Kern.player(0)));
        assertEquals(ReasonCode.ALREADY_IN_FACTION, Kern.firstReason(already));
        // bad name
        assertEquals(ReasonCode.NAME_TOO_SHORT,
                Kern.firstReason(s.apply(new Intent.CreateFaction("ab", Kern.player(2)))));
    }

    @Test
    void renameReleasesOldNameAndRegistersNew() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        s.apply(new Intent.RenameFaction(fa, "Gamma", Kern.player(0)));
        KernelSnapshot snap = new KernelSnapshot(s.state);
        assertNull(snap.factionByName("alpha"));
        assertNotNull(snap.factionByName("gamma"));
        // old name is now free to reuse
        assertTrue(s.createFaction("Alpha", Kern.player(1)) > 0);
    }

    // ── membership & invites ───────────────────────────────────────────────────────────────

    @Test
    void inviteAcceptIsAtomicAndCapEnforced() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        // officer (owner) invites P1
        List<Effect> inv = s.apply(new Intent.SendInvite(fa, Kern.player(0), Kern.player(1)));
        long inviteId = -1;
        for (Effect e : inv) {
            if (e instanceof Effect.InviteCreated ic) {
                inviteId = ic.inviteId();
            }
        }
        assertTrue(inviteId >= 0);
        // duplicate invite rejected
        assertEquals(ReasonCode.ALREADY_INVITED,
                Kern.firstReason(s.apply(new Intent.SendInvite(fa, Kern.player(0), Kern.player(1)))));
        // accept via join: member joins and the invite is consumed in the same step
        List<Effect> joined = s.apply(new Intent.JoinFaction(fa, Kern.player(1), inviteId));
        assertEquals(1, Kern.countType(joined, Effect.MemberJoined.class));
        assertEquals(1, Kern.countType(joined, Effect.InviteRemoved.class));
        assertEquals(0, s.state.invites().size());
        assertEquals(fa, memberFactionHandleOf(s.state, Kern.player(1)));
    }

    @Test
    void joinWithExpiredInviteIsRejected() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        long id = -1;
        for (Effect e : s.apply(new Intent.SendInvite(fa, Kern.player(0), Kern.player(1)))) {
            if (e instanceof Effect.InviteCreated ic) {
                id = ic.inviteId();
            }
        }
        s.advanceTime(73L * 3_600_000L, 0); // TTL is 72h
        assertEquals(ReasonCode.NOT_INVITED,
                Kern.firstReason(s.apply(new Intent.JoinFaction(fa, Kern.player(1), id))));
    }

    @Test
    void openJoinRequiresOpenFlag() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        assertEquals(ReasonCode.NO_INVITE_PENDING, Kern.firstReason(
                s.apply(new Intent.JoinFaction(fa, Kern.player(3), Intent.OPEN_JOIN))));
        s.apply(new Intent.SetFactionFlag(fa, Faction.FLAG_OPEN, true, false, Kern.player(0)));
        assertEquals(1, Kern.countType(
                s.apply(new Intent.JoinFaction(fa, Kern.player(3), Intent.OPEN_JOIN)),
                Effect.MemberJoined.class));
    }

    @Test
    void leaveAndKickSemantics() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        joinOpen(s, fa, Kern.player(1));
        // member cannot kick the owner
        assertEquals(ReasonCode.CANNOT_KICK_LEADER,
                Kern.firstReason(s.apply(new Intent.KickMember(fa, Kern.player(1), Kern.player(0)))));
        // cannot kick self
        assertEquals(ReasonCode.CANNOT_KICK_SELF,
                Kern.firstReason(s.apply(new Intent.KickMember(fa, Kern.player(0), Kern.player(0)))));
        // owner kicks the member
        List<Effect> k = s.apply(new Intent.KickMember(fa, Kern.player(0), Kern.player(1)));
        assertEquals(1, Kern.countType(k, Effect.MemberLeft.class));
        assertEquals(FactionHandle.WILDERNESS, memberFactionHandleOf(s.state, Kern.player(1)));
        // leaving when not in the faction
        assertEquals(ReasonCode.NOT_IN_FACTION,
                Kern.firstReason(s.apply(new Intent.LeaveFaction(fa, Kern.player(1)))));
    }

    // ── roles ─────────────────────────────────────────────────────────────────────────────

    @Test
    void promoteDemoteAndOwnerGuard() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        joinOpen(s, fa, Kern.player(1));
        // promote member -> officer
        assertEquals(1, Kern.countType(
                s.apply(new Intent.PromoteMember(fa, Kern.player(0), Kern.player(1))),
                Effect.RankChanged.class));
        assertTrue(rankOf(s.state, fa, Kern.player(1)).isOfficerOrAbove());
        // promoting an officer into the owner rank is blocked
        assertEquals(ReasonCode.ROLE_FAILED,
                Kern.firstReason(s.apply(new Intent.PromoteMember(fa, Kern.player(0), Kern.player(1)))));
        // demote back to member
        s.apply(new Intent.DemoteMember(fa, Kern.player(0), Kern.player(1)));
        assertFalse(rankOf(s.state, fa, Kern.player(1)).isOfficerOrAbove());
    }

    @Test
    void transferOwnershipSwapsRanks() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        joinOpen(s, fa, Kern.player(1));
        List<Effect> t = s.apply(new Intent.TransferOwnership(fa, Kern.player(1), Kern.player(0)));
        assertEquals(1, Kern.countType(t, Effect.OwnershipTransferred.class));
        assertEquals(Kern.player(1), s.state.factions().resolve(fa).ownerId());
        assertTrue(rankOf(s.state, fa, Kern.player(1)).isOwner());
        assertTrue(rankOf(s.state, fa, Kern.player(0)).isOfficerOrAbove());
        assertFalse(rankOf(s.state, fa, Kern.player(0)).isOwner());
    }

    @Test
    void customRoleCreateAssignAndAuthority() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        joinOpen(s, fa, Kern.player(1));
        // owner (priority 100) creates a custom role at priority 40
        List<Effect> c = s.apply(new Intent.CreateRole(fa, Kern.player(0), "elite", 40, "[E]"));
        assertEquals(1, Kern.countType(c, Effect.RoleCreated.class));
        // duplicate / protected names rejected
        assertEquals(ReasonCode.ROLE_NAME_TAKEN,
                Kern.firstReason(s.apply(new Intent.CreateRole(fa, Kern.player(0), "elite", 30, null))));
        // assign it to the member
        assertEquals(1, Kern.countType(
                s.apply(new Intent.AssignRole(fa, Kern.player(0), Kern.player(1), "elite")),
                Effect.RoleAssigned.class));
        assertEquals(40, rankOf(s.state, fa, Kern.player(1)).priority());
    }

    // ── relations (symmetry + announce) ────────────────────────────────────────────────────

    @Test
    void relationsAreSymmetricAndReciprocalAlly() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        int fb = s.createFaction("Beta", Kern.player(1));
        int ordB = FactionHandle.ordinal(fb);
        int ordA = FactionHandle.ordinal(fa);
        // A wishes ALLY; unreciprocated => effective NEUTRAL both directions
        s.apply(new Intent.DeclareRelation(fa, fb, RelationKind.ALLY, Kern.player(0)));
        assertEquals(RelationKind.NEUTRAL, effective(s.state, ordA, ordB));
        assertEquals(RelationKind.NEUTRAL, effective(s.state, ordB, ordA));
        // B reciprocates => effective ALLY both directions (symmetry)
        s.apply(new Intent.DeclareRelation(fb, fa, RelationKind.ALLY, Kern.player(1)));
        assertEquals(RelationKind.ALLY, effective(s.state, ordA, ordB));
        assertEquals(RelationKind.ALLY, effective(s.state, ordB, ordA));
        // ENEMY is bilateral & instant, and broadcasts
        List<Effect> war = s.apply(new Intent.DeclareRelation(fa, fb, RelationKind.ENEMY, Kern.player(0)));
        assertEquals(RelationKind.ENEMY, effective(s.state, ordA, ordB));
        assertEquals(RelationKind.ENEMY, effective(s.state, ordB, ordA));
        assertEquals(1, Kern.countType(war, Effect.Broadcast.class));
        // self relation & bad kind rejected
        assertEquals(ReasonCode.RELATION_SELF,
                Kern.firstReason(s.apply(new Intent.DeclareRelation(fa, fa, RelationKind.ALLY, Kern.player(0)))));
    }

    // ── claims & overclaim gate ─────────────────────────────────────────────────────────────

    @Test
    void claimRequiresPowerBorderAndOwnership() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        s.connect(Kern.player(0));
        // with zero power, maxLand is 0 => cannot claim
        assertEquals(ReasonCode.NOT_ENOUGH_POWER, Kern.firstReason(
                s.apply(new Intent.ClaimChunks(Kern.player(0), fa, 0,
                        new long[] {ChunkKeys.key(0, 0)}, 0))));
        s.setPower(Kern.player(0), 10.0); // maxLand = 10
        // first claim (land 0) always borders; then an adjacent claim
        assertEquals(1, Kern.countType(s.apply(new Intent.ClaimChunks(Kern.player(0), fa, 0,
                new long[] {ChunkKeys.key(0, 0)}, 0)), Effect.ClaimSet.class));
        assertEquals(1, s.state.factions().resolve(fa).landCount());
        assertEquals(1, Kern.countType(s.apply(new Intent.ClaimChunks(Kern.player(0), fa, 0,
                new long[] {ChunkKeys.key(1, 0)}, 0)), Effect.ClaimSet.class));
        // re-claiming own land
        assertEquals(ReasonCode.ALREADY_CLAIMED, Kern.firstReason(
                s.apply(new Intent.ClaimChunks(Kern.player(0), fa, 0,
                        new long[] {ChunkKeys.key(0, 0)}, 0))));
        // unclaim
        assertEquals(1, Kern.countType(s.apply(new Intent.UnclaimChunks(Kern.player(0), fa, 0,
                new long[] {ChunkKeys.key(1, 0)})), Effect.ClaimRemoved.class));
        assertEquals(1, s.state.factions().resolve(fa).landCount());
    }

    @Test
    void overclaimGateNeedsEnemyRaidableUnprotected() {
        Session s = Session.empty(Kern.cfgOverclaim());
        int fa = s.createFaction("Alpha", Kern.player(0));
        int fb = s.createFaction("Beta", Kern.player(1));
        s.connect(Kern.player(0));
        s.connect(Kern.player(1));
        s.setPower(Kern.player(0), 10.0);
        s.setPower(Kern.player(1), 10.0);
        // B claims one chunk, then loses all power => landCount(1) > maxLand(0) => raidable
        s.apply(new Intent.ClaimChunks(Kern.player(1), fb, 0, new long[] {ChunkKeys.key(5, 5)}, 0));
        // not enemy yet => overclaim rejected
        assertEquals(ReasonCode.ALREADY_CLAIMED, Kern.firstReason(
                s.apply(new Intent.ClaimChunks(Kern.player(0), fa, 0,
                        new long[] {ChunkKeys.key(5, 5)}, 0))));
        // declare enemy (bilateral) and drop B's power below its land
        s.apply(new Intent.DeclareRelation(fa, fb, RelationKind.ENEMY, Kern.player(0)));
        s.apply(new Intent.AdminPowerSet(Kern.player(1), 0.0, Kern.player(999), "drain"));
        List<Effect> oc = s.apply(new Intent.ClaimChunks(Kern.player(0), fa, 0,
                new long[] {ChunkKeys.key(5, 5)}, 0));
        assertEquals(1, Kern.countType(oc, Effect.ClaimSet.class));
        assertEquals(FactionHandle.ordinal(fa),
                FactionHandle.ordinal(s.state.claims().ownerAt(0, ChunkKeys.key(5, 5))));
        assertEquals(0, s.state.factions().resolve(fb).landCount());
        assertEquals(1, s.state.factions().resolve(fa).landCount());
    }

    // ── power: death / streak / kill / buy / raidable ──────────────────────────────────────

    @Test
    void deathLosesPowerPastGrace() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        s.connect(Kern.player(0));
        s.setPower(Kern.player(0), 10.0);
        List<Effect> d = s.apply(new Intent.RecordDeath(Kern.player(0), null, 0, ChunkKeys.key(3, 3)));
        Effect.PowerChanged pc = firstPower(d);
        assertNotNull(pc);
        assertEquals(10.0, pc.before(), 1e-9);
        assertEquals(6.0, pc.after(), 1e-9); // -4 loss
        assertEquals(1, Kern.countType(d, Effect.DeathStreakAdvanced.class));
    }

    @Test
    void deathStreakEscalatesInsideWindow() {
        Session s = new Session(KernelState.empty(Kern.cfgPower(Kern.pcDeathStreak())));
        int fa = s.createFaction("Alpha", Kern.player(0));
        s.connect(Kern.player(0));
        s.setPower(Kern.player(0), 10.0);
        long wild = ChunkKeys.key(3, 3);
        Effect.PowerChanged d1 = firstPower(
                s.apply(new Intent.RecordDeath(Kern.player(0), null, 0, wild)));
        assertEquals(6.0, d1.after(), 1e-9); // streak 0 -> loss 4
        s.advanceTime(1000L, 0); // still inside the 600s window
        Effect.PowerChanged d2 = firstPower(
                s.apply(new Intent.RecordDeath(Kern.player(0), null, 0, wild)));
        assertEquals(0.0, d2.after(), 1e-9); // streak 1 -> loss 6, clamps at min
    }

    @Test
    void killGrantsGainToKiller() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        joinOpen(s, fa, Kern.player(1));
        s.connect(Kern.player(0));
        s.connect(Kern.player(1));
        s.setPower(Kern.player(0), 10.0); // victim
        s.setPower(Kern.player(1), 5.0);  // killer
        List<Effect> d = s.apply(new Intent.RecordDeath(Kern.player(0), Kern.player(1), 0,
                ChunkKeys.key(3, 3)));
        Effect.PowerChanged killer = null;
        for (Effect e : d) {
            if (e instanceof Effect.PowerChanged p
                    && p.source() == dev.fablemc.factions.kernel.rules.PowerMath.SRC_KILL) {
                killer = p;
            }
        }
        assertNotNull(killer);
        assertEquals(7.0, killer.after(), 1e-9); // 5 + 2 gain
    }

    @Test
    void buyDisabledThenEnabled() {
        Session off = Session.empty(Kern.cfgDefault());
        off.createFaction("Alpha", Kern.player(0));
        assertEquals(ReasonCode.POWER_BUY_DISABLED, Kern.firstReason(
                off.apply(new Intent.BuyPower(Kern.player(0), 3.0, 300.0, 1L))));

        Session on = new Session(KernelState.empty(Kern.cfgPower(Kern.pcBuy(5.0, 100.0))));
        on.connect(Kern.player(0));
        List<Effect> b = on.apply(new Intent.BuyPower(Kern.player(0), 3.0, 300.0, 1L));
        assertEquals(1, Kern.countType(b, Effect.PowerChanged.class));
        assertEquals(3.0, firstPower(b).after(), 1e-9);
        // over the per-purchase cap is rejected
        assertEquals(ReasonCode.POWER_BUY_INVALID_AMOUNT, Kern.firstReason(
                on.apply(new Intent.BuyPower(Kern.player(0), 6.0, 600.0, 2L))));
    }

    @Test
    void adminClaimCanExceedLandThenTickFlagsRaidable() {
        Session s = Session.empty(Kern.cfgDefault());
        int fa = s.createFaction("Alpha", Kern.player(0));
        // zero power => maxLand 0; admin-claim ignores the power gate
        s.apply(new Intent.AdminClaimChunks(fa, 0,
                new long[] {ChunkKeys.key(0, 0), ChunkKeys.key(1, 0), ChunkKeys.key(2, 0)},
                Kern.player(999)));
        assertEquals(3, s.state.factions().resolve(fa).landCount());
        assertFalse(s.state.factions().resolve(fa).raidable());
        List<Effect> tick = s.apply(new Intent.PowerTick(1));
        assertEquals(1, Kern.countType(tick, Effect.RaidableChanged.class));
        assertTrue(s.state.factions().resolve(fa).raidable());
    }

    @Test
    void frozenPowerBlocksAutomaticButAdminBypasses() {
        Session s = Session.empty(Kern.cfgDefault());
        s.createFaction("Alpha", Kern.player(0));
        s.connect(Kern.player(0));
        s.setPower(Kern.player(0), 8.0);
        s.apply(new Intent.SetPowerFrozen(Kern.player(0), true, Kern.player(999), "freeze"));
        // a death cannot change frozen power
        List<Effect> d = s.apply(new Intent.RecordDeath(Kern.player(0), null, 0, ChunkKeys.key(3, 3)));
        assertEquals(0, Kern.countType(d, Effect.PowerChanged.class));
        // admin set bypasses the freeze (freezeAllowAdminBypass default true)
        List<Effect> a = s.apply(new Intent.AdminPowerSet(Kern.player(0), 2.0, Kern.player(999), "x"));
        assertEquals(1, Kern.countType(a, Effect.PowerChanged.class));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private static void joinOpen(Session s, int faction, UUID p) {
        // Open the faction, join, then (leave it open — harmless for these tests).
        s.apply(new Intent.SetFactionFlag(faction, Faction.FLAG_OPEN, true, false, Kern.player(0)));
        s.apply(new Intent.JoinFaction(faction, p, Intent.OPEN_JOIN));
    }

    private static int memberFactionHandleOf(KernelState st, UUID p) {
        int ord = st.members().get(p);
        return ord < 0 ? FactionHandle.WILDERNESS : st.ledger().factionHandle(ord);
    }

    private static dev.fablemc.factions.kernel.state.Rank rankOf(KernelState st, int faction, UUID p) {
        int ord = st.members().get(p);
        Faction f = st.factions().resolve(faction);
        int ri = st.ledger().rankIdx(ord);
        return f.ranks()[ri];
    }

    private static byte effective(KernelState st, int ordA, int ordB) {
        return st.factions().at(ordA).relationEffective(ordB);
    }

    private static Effect.PowerChanged firstPower(List<Effect> effects) {
        for (Effect e : effects) {
            if (e instanceof Effect.PowerChanged p) {
                return p;
            }
        }
        return null;
    }
}
