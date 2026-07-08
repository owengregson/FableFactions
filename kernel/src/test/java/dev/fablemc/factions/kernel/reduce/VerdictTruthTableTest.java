package dev.fablemc.factions.kernel.reduce;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.config.ZoneConfig;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.rules.Action;
import dev.fablemc.factions.kernel.rules.ActorBits;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.rules.Verdict;
import dev.fablemc.factions.kernel.rules.Verdicts;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.kernel.state.RelationKind;

/**
 * The protection verdict truth table over (zone × relation × flags × action), built from a small
 * fixture snapshot (proposal-C §5c, ref-engines §3.1). {@link Verdicts#decide} is the zero-alloc
 * read-side decision; here we pin every branch of its short-circuit order.
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b>
 * test-confined fixtures; no shared state between tests.
 */
class VerdictTruthTableTest {

    private static final int WORLD = 0;
    private static final long OWNED = ChunkKeys.key(0, 0);   // faction A (ord 2)
    private static final long SAFE = ChunkKeys.key(1, 0);    // SAFEZONE (ord 0)
    private static final long WAR = ChunkKeys.key(2, 0);     // WARZONE (ord 1)
    private static final long WILD = ChunkKeys.key(9, 9);    // unclaimed

    private static final int A = FactionHandle.handle(0, 2);
    private static final int B = FactionHandle.handle(0, 3);
    private static final int SAFE_H = FactionHandle.handle(0, FactionHandle.SAFEZONE_ORDINAL);
    private static final int WAR_H = FactionHandle.handle(0, FactionHandle.WARZONE_ORDINAL);

    // ── fixture ────────────────────────────────────────────────────────────────────────────

    /** Snapshot with faction A owning OWNED, plus zone chunks, and B carrying an eff edge to A. */
    private static KernelSnapshot snap(ConfigImage cfg, byte bToAEff, long flagBitsA) {
        Faction a = FactionEdit.withFlagBits(Kern.faction(2, "Alpha", Kern.player(0)), flagBitsA);
        Faction b = Kern.faction(3, "Beta", Kern.player(1));
        if (bToAEff != RelationKind.NEUTRAL) {
            RelationEdges.Edges eff = RelationEdges.with(b.relEff(), b.relEffKind(),
                    b.relEff().length, 2, bToAEff);
            b = FactionEdit.withRelations(b, b.relOut(), b.relOutKind(), eff.ordinals(), eff.kinds());
        }
        FactionArena arena = FactionArena.empty().withFaction(2, a).withFaction(3, b);
        KernelState st = KernelState.empty(cfg)
                .withFactions(arena)
                .withClaims(dev.fablemc.factions.kernel.state.ClaimAtlas.empty()
                        .withClaim(WORLD, OWNED, A)
                        .withClaim(WORLD, SAFE, SAFE_H)
                        .withClaim(WORLD, WAR, WAR_H));
        return new KernelSnapshot(st);
    }

    private static long actor(int factionHandle, boolean bypass, boolean overriding) {
        return ActorBits.of(-1, factionHandle, bypass, overriding, true);
    }

    // ── bypass / overriding short-circuit ──────────────────────────────────────────────────

    @Test
    void bypassAndOverridingAlwaysAllow() {
        KernelSnapshot s = snap(ConfigImage.defaults(), RelationKind.ENEMY, 0L);
        long bypass = actor(B, true, false);
        long override = actor(B, false, true);
        int[] actions = {Action.BUILD, Action.PVP, Action.EXPLOSION, Action.FIRE_SPREAD};
        for (int act : actions) {
            assertEquals(Verdict.ALLOW, Verdicts.decide(s, bypass, WORLD, OWNED, act));
            assertEquals(Verdict.ALLOW, Verdicts.decide(s, override, WORLD, SAFE, act));
        }
    }

    // ── wilderness ─────────────────────────────────────────────────────────────────────────

    @Test
    void wildernessAllowsEverything() {
        KernelSnapshot s = snap(ConfigImage.defaults(), RelationKind.NEUTRAL, 0L);
        long a = actor(B, false, false);
        for (int act : new int[] {Action.BUILD, Action.PVP, Action.EXPLOSION, Action.FIRE_SPREAD}) {
            assertEquals(Verdict.ALLOW, Verdicts.decide(s, a, WORLD, WILD, act));
        }
    }

    // ── safezone ─────────────────────────────────────────────────────────────────────────

    @Test
    void safezoneEnabled() {
        KernelSnapshot s = snap(ConfigImage.defaults(), RelationKind.NEUTRAL, 0L);
        long a = actor(B, false, false);
        assertEquals(Verdict.DENY_SAFEZONE, Verdicts.decide(s, a, WORLD, SAFE, Action.BUILD));
        assertEquals(Verdict.DENY_SAFEZONE, Verdicts.decide(s, a, WORLD, SAFE, Action.PVP));
        assertEquals(Verdict.DENY_EXPLOSIONS, Verdicts.decide(s, a, WORLD, SAFE, Action.EXPLOSION));
        assertEquals(Verdict.ALLOW, Verdicts.decide(s, a, WORLD, SAFE, Action.FIRE_SPREAD));
    }

    @Test
    void safezoneDisabledIsWilderness() {
        ConfigImage cfg = withZones(new ZoneConfig(false, true));
        KernelSnapshot s = snap(cfg, RelationKind.NEUTRAL, 0L);
        long a = actor(B, false, false);
        assertEquals(Verdict.ALLOW, Verdicts.decide(s, a, WORLD, SAFE, Action.BUILD));
        assertEquals(Verdict.ALLOW, Verdicts.decide(s, a, WORLD, SAFE, Action.PVP));
    }

    // ── warzone ─────────────────────────────────────────────────────────────────────────

    @Test
    void warzoneEnabled() {
        KernelSnapshot s = snap(ConfigImage.defaults(), RelationKind.NEUTRAL, 0L);
        long a = actor(B, false, false);
        assertEquals(Verdict.DENY_WARZONE, Verdicts.decide(s, a, WORLD, WAR, Action.BUILD));
        assertEquals(Verdict.ALLOW, Verdicts.decide(s, a, WORLD, WAR, Action.PVP)); // war = free pvp
        assertEquals(Verdict.DENY_EXPLOSIONS, Verdicts.decide(s, a, WORLD, WAR, Action.EXPLOSION));
        assertEquals(Verdict.ALLOW, Verdicts.decide(s, a, WORLD, WAR, Action.FIRE_SPREAD));
    }

    @Test
    void warzoneDisabledIsWilderness() {
        ConfigImage cfg = withZones(new ZoneConfig(true, false));
        KernelSnapshot s = snap(cfg, RelationKind.NEUTRAL, 0L);
        long a = actor(B, false, false);
        assertEquals(Verdict.ALLOW, Verdicts.decide(s, a, WORLD, WAR, Action.BUILD));
    }

    // ── normal faction build-like by relation ──────────────────────────────────────────────

    @Test
    void normalBuildByRelation() {
        long own = actor(A, false, false);
        long factionless = actor(FactionHandle.WILDERNESS, false, false);
        // own territory
        assertEquals(Verdict.ALLOW, Verdicts.decide(snap(ConfigImage.defaults(),
                RelationKind.NEUTRAL, 0L), own, WORLD, OWNED, Action.BUILD));
        // ally may build
        assertEquals(Verdict.ALLOW, Verdicts.decide(snap(ConfigImage.defaults(),
                RelationKind.ALLY, 0L), actor(B, false, false), WORLD, OWNED, Action.BUILD));
        // neutral / truce / enemy denied with the matching code
        assertEquals(Verdict.DENY_NEUTRAL, Verdicts.decide(snap(ConfigImage.defaults(),
                RelationKind.NEUTRAL, 0L), actor(B, false, false), WORLD, OWNED, Action.BUILD));
        assertEquals(Verdict.DENY_TRUCE, Verdicts.decide(snap(ConfigImage.defaults(),
                RelationKind.TRUCE, 0L), actor(B, false, false), WORLD, OWNED, Action.BUILD));
        assertEquals(Verdict.DENY_ENEMY, Verdicts.decide(snap(ConfigImage.defaults(),
                RelationKind.ENEMY, 0L), actor(B, false, false), WORLD, OWNED, Action.BUILD));
        // factionless actor is treated as neutral
        assertEquals(Verdict.DENY_NEUTRAL, Verdicts.decide(snap(ConfigImage.defaults(),
                RelationKind.NEUTRAL, 0L), factionless, WORLD, OWNED, Action.BUILD));
        // every build-like action shares the gate
        for (int act : new int[] {Action.INTERACT, Action.CONTAINER, Action.LIQUID, Action.PISTON,
                Action.ENTITY_GRIEF, Action.TRAMPLE}) {
            assertEquals(Verdict.DENY_ENEMY, Verdicts.decide(snap(ConfigImage.defaults(),
                    RelationKind.ENEMY, 0L), actor(B, false, false), WORLD, OWNED, act));
        }
    }

    // ── normal faction flag-gated actions (PVP / EXPLOSION / FIRE) ──────────────────────────

    @Test
    void normalFlagGatedActions() {
        long a = actor(B, false, false);
        // PVP flag defaults ON => allow; explicitly OFF => deny
        assertEquals(Verdict.ALLOW, Verdicts.decide(snap(ConfigImage.defaults(),
                RelationKind.ENEMY, 0L), a, WORLD, OWNED, Action.PVP));
        long pvpOff = Faction.withFlag(0L, Faction.FLAG_PVP, false);
        assertEquals(Verdict.DENY_PVP_FLAG, Verdicts.decide(snap(ConfigImage.defaults(),
                RelationKind.ENEMY, pvpOff), a, WORLD, OWNED, Action.PVP));

        // EXPLOSION default OFF => deny; explicitly ON => allow
        assertEquals(Verdict.DENY_EXPLOSIONS, Verdicts.decide(snap(ConfigImage.defaults(),
                RelationKind.NEUTRAL, 0L), a, WORLD, OWNED, Action.EXPLOSION));
        long expOn = Faction.withFlag(0L, Faction.FLAG_EXPLOSIONS, true);
        assertEquals(Verdict.ALLOW, Verdicts.decide(snap(ConfigImage.defaults(),
                RelationKind.NEUTRAL, expOn), a, WORLD, OWNED, Action.EXPLOSION));

        // FIRE_SPREAD default OFF => deny; explicitly ON => allow
        assertEquals(Verdict.DENY_FIRE, Verdicts.decide(snap(ConfigImage.defaults(),
                RelationKind.NEUTRAL, 0L), a, WORLD, OWNED, Action.FIRE_SPREAD));
        long fireOn = Faction.withFlag(0L, Faction.FLAG_FIRE_SPREAD, true);
        assertEquals(Verdict.ALLOW, Verdicts.decide(snap(ConfigImage.defaults(),
                RelationKind.NEUTRAL, fireOn), a, WORLD, OWNED, Action.FIRE_SPREAD));
    }

    private static ConfigImage withZones(ZoneConfig zones) {
        ConfigImage d = ConfigImage.defaults();
        return new ConfigImage(d.limits(), d.language(), d.display(), d.updates(), d.metrics(),
                d.mergeEnabled(), d.flagDefaults(), d.power(), d.land(), d.economy(), d.fly(),
                d.chat(), d.relation(), d.role(), zones, d.notifications(), d.gui(), d.predefined(),
                d.storage(), d.baked());
    }
}
