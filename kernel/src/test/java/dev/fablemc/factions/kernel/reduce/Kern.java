package dev.fablemc.factions.kernel.reduce;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.config.EconomyConfig;
import dev.fablemc.factions.kernel.config.LandConfig;
import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.EconomyEffect;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.effect.MembershipEffect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.intent.PowerIntent;
import dev.fablemc.factions.kernel.intent.SessionIntent;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.state.ClaimAtlas;
import dev.fablemc.factions.kernel.state.EscrowTable;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.Home;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;

/**
 * Shared test harness for the reducer suite (Wave 2a). Not a test class.
 *
 * <p>Provides a {@link Session} that folds intents through {@link Reducer#apply} and drains AM-5
 * paged continuations exactly as the writer would (re-enqueue {@code ContinuationRequested.next},
 * FIFO, to completion), plus deterministic fixtures, a canonical state digest for replay-equality,
 * and structural scanners (per-faction atlas land counts, dangling-reference checks).
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b>
 * test-confined fixtures; no shared state between tests.
 */
final class Kern {

    private Kern() {
    }

    static final Origin O = Origin.SYSTEM_ORIGIN;

    /** A stable, deterministic player UUID for index {@code i}. */
    static UUID player(int i) {
        return new UUID(0xF00D0000L, i);
    }

    // ── config builders ──────────────────────────────────────────────────────────────────

    static ConfigImage cfgDefault() {
        return ConfigImage.defaults();
    }

    /** Default config but with faction merge enabled. */
    static ConfigImage cfgMerge() {
        ConfigImage d = ConfigImage.defaults();
        return new ConfigImage(d.limits(), d.language(), d.display(), d.updates(), d.metrics(),
                true, d.flagDefaults(), d.power(), d.land(), d.economy(), d.fly(), d.chat(),
                d.relation(), d.role(), d.zones(), d.notifications(), d.gui(), d.predefined(),
                d.storage(), d.baked());
    }

    /** Default config but with overclaiming enabled (enemy-relation required, offline+shield off). */
    static ConfigImage cfgOverclaim() {
        ConfigImage d = ConfigImage.defaults();
        LandConfig l = d.land();
        LandConfig nl = new LandConfig(l.bufferZone(), l.maxPerCommand(), l.perPower(), l.maxLand(),
                true, true, false, l.raidableBroadcastEnabled(), l.raidableBroadcastServerWide(),
                false, l.warShieldMaxDurationHours());
        return withLand(d, nl);
    }

    private static ConfigImage withLand(ConfigImage d, LandConfig nl) {
        return new ConfigImage(d.limits(), d.language(), d.display(), d.updates(), d.metrics(),
                d.mergeEnabled(), d.flagDefaults(), d.power(), nl, d.economy(), d.fly(), d.chat(),
                d.relation(), d.role(), d.zones(), d.notifications(), d.gui(), d.predefined(),
                d.storage(), d.baked());
    }

    /** Default config but with the periodic bank tax enabled at the given rate. */
    static ConfigImage cfgTax(double rate, double minBank, double minCharge) {
        ConfigImage d = ConfigImage.defaults();
        EconomyConfig e = d.economy();
        EconomyConfig ne = new EconomyConfig(e.enabled(), e.costCreate(), e.costClaim(), true, rate,
                e.taxIntervalHours(), minBank, minCharge, e.historyPageSize());
        return new ConfigImage(d.limits(), d.language(), d.display(), d.updates(), d.metrics(),
                d.mergeEnabled(), d.flagDefaults(), d.power(), d.land(), ne, d.fly(), d.chat(),
                d.relation(), d.role(), d.zones(), d.notifications(), d.gui(), d.predefined(),
                d.storage(), d.baked());
    }

    // ── driver ───────────────────────────────────────────────────────────────────────────

    /** Drives a mutable fold over a {@link KernelState}, draining paged continuations. */
    static final class Session {
        KernelState state;
        long seq = 1;
        long epoch = 5_000_000L;
        int tick = 0;
        /** Max per-entity effects produced by any single {@link Reducer#apply} (AM-5 page bound). */
        int maxPageEntities = 0;

        Session(KernelState s) {
            this.state = s;
        }

        static Session empty(ConfigImage cfg) {
            return new Session(KernelState.empty(cfg));
        }

        /** Applies one intent at the current (epoch,tick) and drains every continuation it spawns. */
        List<Effect> apply(Intent intent) {
            List<Effect> all = new ArrayList<>();
            Deque<Intent> queue = new ArrayDeque<>();
            queue.add(intent);
            int guard = 0;
            while (!queue.isEmpty()) {
                Intent nx = queue.poll();
                long s = seq++;
                IntentEnvelope env = new IntentEnvelope(s, epoch, tick,
                        s * 0x9E3779B97F4A7C15L, O, nx);
                Reducer.Outcome o = Reducer.apply(state, env);
                state = o.next();
                int pageEntities = 0;
                for (Effect e : o.effects()) {
                    all.add(e);
                    if (isPageEntity(e)) {
                        pageEntities++;
                    }
                    if (e instanceof SystemEffect.ContinuationRequested cr) {
                        queue.add(cr.next());
                    }
                }
                maxPageEntities = Math.max(maxPageEntities, pageEntities);
                if (++guard > 1_000_000) {
                    throw new AssertionError("continuation runaway for " + intent);
                }
            }
            return all;
        }

        /** Applies one intent WITHOUT draining continuations (single page), returning its effects. */
        List<Effect> step(Intent intent) {
            long s = seq++;
            IntentEnvelope env = new IntentEnvelope(s, epoch, tick, s * 0x9E3779B97F4A7C15L, O, intent);
            Reducer.Outcome o = Reducer.apply(state, env);
            state = o.next();
            return o.effects();
        }

        void advanceTime(long dEpoch, int dTick) {
            epoch += dEpoch;
            tick += dTick;
        }

        /** Creates a faction and returns its handle, or -1 on rejection. */
        int createFaction(String name, UUID owner) {
            for (Effect e : apply(new LifecycleIntent.CreateFaction(name, owner))) {
                if (e instanceof LifecycleEffect.FactionCreated fc) {
                    return fc.faction();
                }
            }
            return -1;
        }

        /** Connects a player (online + settled). */
        void connect(UUID p) {
            apply(new SessionIntent.PlayerConnected(p, "P" + p.getLeastSignificantBits(), "en"));
        }

        /** Sets a member's power via admin-set (bypasses freeze; clamped to config max). */
        void setPower(UUID p, double amount) {
            apply(new PowerIntent.AdminPowerSet(p, amount, player(999), "test"));
        }
    }

    // ── faction hand-builder (for bulk fixtures the reducer path can't cheaply reach) ─────

    /** A minimal live faction record at {@code ord} with the built-in ranks. */
    static Faction faction(int ord, String name, UUID owner) {
        Rank[] ranks = {
                new Rank(Rank.NAME_MEMBER, Rank.NAME_MEMBER, null, Rank.PRIORITY_MEMBER),
                new Rank(Rank.NAME_OFFICER, Rank.NAME_OFFICER, null, Rank.PRIORITY_OFFICER),
                new Rank(Rank.NAME_OWNER, Rank.NAME_OWNER, null, Rank.PRIORITY_OWNER)
        };
        return new Faction(ord, new UUID(1, ord), name, NameIndex.fold(name), owner, "", "", 0L,
                0.0, 0.0, 0L, RelationEdges.NO_ORDINALS, RelationEdges.NO_KINDS,
                RelationEdges.NO_ORDINALS, RelationEdges.NO_KINDS, null, Faction.NO_SHIELD, 0, false,
                ranks, 0, 0.0, 0L, FactionClaimList.empty(), name, name);
    }

    /**
     * A state holding one faction (ordinal 2, handle {@code (0,2)}) that owns {@code nClaims}
     * contiguous chunks in world 0, with {@code landCount == nClaims}. Used to exercise the ≤1024
     * page bound for bulk unclaim/disband without threading them through the claim validator.
     */
    static KernelState bigClaimState(int nClaims) {
        int ord = FactionHandle.FIRST_NORMAL_ORDINAL;
        int handle = FactionHandle.handle(0, ord);
        ClaimAtlas.Builder atlas = new ClaimAtlas.Builder();
        for (int i = 0; i < nClaims; i++) {
            atlas.put(0, ChunkKeys.key(i, 0), handle);
        }
        Faction f = FactionEdit.withLand(faction(ord, "BigFac", player(0)), nClaims,
                FactionClaimList.empty());
        FactionArena arena = FactionArena.empty().withFaction(ord, f);
        return KernelState.empty(ConfigImage.defaults())
                .withFactions(arena)
                .withClaims(atlas.build())
                .withFactionNames(NameIndex.empty().with(f.nameFolded(), ord));
    }

    static int handle(int ord) {
        return FactionHandle.handle(0, ord);
    }

    // ── structural scanners ────────────────────────────────────────────────────────────────

    /** Per-normal-faction land counts recomputed from the atlas (AM-4 recompute-from-scratch). */
    static Map<Integer, Integer> atlasLandByOrdinal(KernelState st) {
        Map<Integer, Integer> out = new HashMap<>();
        st.claims().forEachClaim((w, k, owner) -> {
            int o = FactionHandle.ordinal(owner);
            if (FactionHandle.isNormalOrdinal(o)) {
                out.merge(o, 1, Integer::sum);
            }
        });
        return out;
    }

    static int atlasCountForZone(KernelState st, int zoneOrdinal) {
        int[] n = {0};
        st.claims().forEachClaim((w, k, owner) -> {
            if (FactionHandle.ordinal(owner) == zoneOrdinal) {
                n[0]++;
            }
        });
        return n[0];
    }

    /** {@code true} when NO reference of any table still points at freed {@code ordinal}. */
    static String danglingRefs(KernelState st, int ordinal, String foldedName) {
        StringBuilder sb = new StringBuilder();
        // atlas claims
        int[] claims = {0};
        st.claims().forEachClaim((w, k, owner) -> {
            if (FactionHandle.ordinal(owner) == ordinal) {
                claims[0]++;
            }
        });
        if (claims[0] > 0) {
            sb.append("claims=").append(claims[0]).append(' ');
        }
        // invites
        for (InviteTable.Invite in : st.invites().forFaction(ordinal)) {
            sb.append("invite=").append(in.id()).append(' ');
        }
        // merges
        if (st.mergeRequests().removeInvolving(ordinal).size() != st.mergeRequests().size()) {
            sb.append("merge ");
        }
        // warps / chests
        if (st.warps().countForFaction(ordinal) > 0) {
            sb.append("warps ");
        }
        if (st.chests().countForFaction(ordinal) > 0) {
            sb.append("chests ");
        }
        // escrows
        List<Long> esc = new ArrayList<>();
        st.escrows().forEach(e -> {
            if (e.factionOrdinal() == ordinal) {
                esc.add(e.id());
            }
        });
        if (!esc.isEmpty()) {
            sb.append("escrows=").append(esc).append(' ');
        }
        // relation edges on every other live faction
        FactionArena arena = st.factions();
        for (int i = 0; i < arena.highWater(); i++) {
            Faction f = arena.at(i);
            if (f == null) {
                continue;
            }
            if (RelationEdges.indexOf(f.relOut(), f.relOut().length, ordinal) >= 0
                    || RelationEdges.indexOf(f.relEff(), f.relEff().length, ordinal) >= 0) {
                sb.append("relEdge@").append(i).append(' ');
            }
        }
        // members still assigned
        var l = st.ledger();
        for (int i = 0; i < l.highWater(); i++) {
            if (l.has(i) && FactionHandle.ordinal(l.factionHandle(i)) == ordinal) {
                sb.append("member@").append(i).append(' ');
            }
        }
        // name still registered
        if (foldedName != null && st.factionNames().contains(foldedName)) {
            sb.append("name ");
        }
        return sb.toString();
    }

    // ── canonical digest (replay equality) ─────────────────────────────────────────────────

    /** A canonical, order-independent digest of all domain state (for replay-determinism). */
    static String digest(KernelState st) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("tick=").append(st.tick()).append('\n');

        FactionArena arena = st.factions();
        for (int ord = 0; ord < arena.highWater(); ord++) {
            Faction f = arena.at(ord);
            if (f == null) {
                continue;
            }
            sb.append("F").append(ord)
                    .append(" g=").append(arena.generationAt(ord))
                    .append(" id=").append(f.id())
                    .append(" n=").append(f.name())
                    .append(" own=").append(f.ownerId())
                    .append(" bank=").append(Double.doubleToLongBits(f.bank()))
                    .append(" boost=").append(Double.doubleToLongBits(f.powerBoost()))
                    .append(" flags=").append(f.flagBits())
                    .append(" land=").append(f.landCount())
                    .append(" raid=").append(f.raidable())
                    .append(" sh=").append(f.shieldStartHour()).append('/').append(f.shieldDurationHours())
                    .append(" tag=").append(f.tagLegacy()).append('|').append(f.tagMini());
            Home h = f.home();
            if (h != null) {
                sb.append(" home=").append(h.worldIdx()).append(',')
                        .append(Double.doubleToLongBits(h.x())).append(',')
                        .append(Double.doubleToLongBits(h.y())).append(',')
                        .append(Double.doubleToLongBits(h.z()));
            }
            for (Rank r : f.ranks()) {
                sb.append(" r[").append(r.id()).append(':').append(r.name()).append(':')
                        .append(r.prefix()).append(':').append(r.priority()).append(']');
            }
            appendEdges(sb, " out", f.relOut(), f.relOutKind());
            appendEdges(sb, " eff", f.relEff(), f.relEffKind());
            sb.append('\n');
        }

        var l = st.ledger();
        for (int ord = 0; ord < l.highWater(); ord++) {
            if (!l.has(ord)) {
                continue;
            }
            sb.append("M").append(ord)
                    .append(" u=").append(l.uuid(ord))
                    .append(" fh=").append(FactionHandle.ordinal(l.factionHandle(ord)))
                    .append(" rk=").append(l.rankIdx(ord))
                    .append(" pb=").append(Double.doubleToLongBits(l.powerBase(ord)))
                    .append(" pt=").append(l.powerAsOfTick(ord))
                    .append(" fz=").append(l.powerFrozen(ord))
                    .append(" la=").append(l.lastActivity(ord))
                    .append(" ld=").append(l.lastDeathAt(ord))
                    .append(" ds=").append(l.deathStreak(ord))
                    .append(" pf=").append(l.prefsBits(ord))
                    .append(" lo=").append(l.localeIdx(ord))
                    .append(" ja=").append(l.joinedAt(ord))
                    .append('\n');
        }

        List<long[]> claims = new ArrayList<>();
        st.claims().forEachClaim((w, k, owner) -> claims.add(new long[] {w, k, owner}));
        claims.sort((a, b) -> {
            int c = Long.compare(a[0], b[0]);
            if (c != 0) {
                return c;
            }
            return Long.compare(a[1], b[1]);
        });
        for (long[] c : claims) {
            sb.append("C ").append(c[0]).append(' ').append(c[1]).append(' ').append(c[2]).append('\n');
        }

        InviteTable.Invite[] invs = collectInvites(st);
        java.util.Arrays.sort(invs, (a, b) -> Long.compare(a.id(), b.id()));
        for (InviteTable.Invite in : invs) {
            sb.append("I ").append(in.id()).append(' ').append(in.factionOrdinal()).append(' ')
                    .append(in.inviter()).append(' ').append(in.invitee()).append(' ')
                    .append(in.createdAt()).append('\n');
        }

        List<EscrowTable.Escrow> escs = new ArrayList<>();
        st.escrows().forEach(escs::add);
        escs.sort((a, b) -> Long.compare(a.id(), b.id()));
        for (EscrowTable.Escrow e : escs) {
            sb.append("E ").append(e.id()).append(' ').append(e.kind()).append(' ')
                    .append(e.player()).append(' ').append(e.factionOrdinal()).append(' ')
                    .append(Double.doubleToLongBits(e.amount())).append('\n');
        }
        sb.append("escTotal=").append(Double.doubleToLongBits(st.escrows().openTotal())).append('\n');
        sb.append("zones=").append(st.zones().safezoneChunks()).append('/')
                .append(st.zones().warzoneChunks()).append('\n');
        int[] onlineCount = {0};
        st.online().forEach(o -> onlineCount[0]++);
        List<Integer> online = new ArrayList<>();
        st.online().forEach(online::add);
        sb.append("online=").append(online).append('\n');
        return sb.toString();
    }

    private static void appendEdges(StringBuilder sb, String tag, int[] ords, byte[] kinds) {
        for (int i = 0; i < ords.length; i++) {
            sb.append(tag).append('[').append(ords[i]).append('=').append(kinds[i]).append(']');
        }
    }

    private static InviteTable.Invite[] collectInvites(KernelState st) {
        List<InviteTable.Invite> out = new ArrayList<>();
        FactionArena arena = st.factions();
        // Scan invites by faction ordinal 0..highWater plus any orphans by brute id probe is not
        // possible; forFaction covers every live sender ordinal, and disband scrubs orphans.
        for (int ord = 0; ord < Math.max(arena.highWater(), 2); ord++) {
            for (InviteTable.Invite in : st.invites().forFaction(ord)) {
                out.add(in);
            }
        }
        return out.toArray(new InviteTable.Invite[0]);
    }

    // ── convenience assertions over effect lists ────────────────────────────────────────────

    static boolean hasRejection(List<Effect> effects) {
        for (Effect e : effects) {
            if (e instanceof FeedbackEffect.Rejected) {
                return true;
            }
        }
        return false;
    }

    static dev.fablemc.factions.kernel.msg.ReasonCode firstReason(List<Effect> effects) {
        for (Effect e : effects) {
            if (e instanceof FeedbackEffect.Rejected r) {
                return r.reason();
            }
        }
        return null;
    }

    static int countType(List<Effect> effects, Class<? extends Effect> type) {
        int n = 0;
        for (Effect e : effects) {
            if (type.isInstance(e)) {
                n++;
            }
        }
        return n;
    }

    /** A per-entity effect (the kind AM-5 pages bound to {@link Reducer#PAGE_SIZE}). */
    static boolean isPageEntity(Effect e) {
        return e instanceof ClaimEffect.ClaimSet || e instanceof ClaimEffect.ClaimRemoved
                || e instanceof ClaimEffect.ZoneSet || e instanceof ClaimEffect.ZoneRemoved
                || e instanceof MembershipEffect.MemberJoined || e instanceof MembershipEffect.MemberLeft
                || e instanceof EconomyEffect.TaxCharged;
    }

    /** Total money in kernel custody: Σ live-faction bank + open escrow. */
    static double custody(KernelState st) {
        double total = st.escrows().openTotal();
        FactionArena arena = st.factions();
        for (int ord = 0; ord < arena.highWater(); ord++) {
            Faction f = arena.at(ord);
            if (f != null) {
                total += f.bank();
            }
        }
        return total;
    }

    /** Ids of every currently-open escrow. */
    static long[] openEscrowIds(KernelState st) {
        List<Long> ids = new ArrayList<>();
        st.escrows().forEach(e -> ids.add(e.id()));
        long[] out = new long[ids.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ids.get(i);
        }
        return out;
    }

    /** {@code true} when no live faction has a negative bank. */
    static boolean allBanksNonNegative(KernelState st) {
        FactionArena arena = st.factions();
        for (int ord = 0; ord < arena.highWater(); ord++) {
            Faction f = arena.at(ord);
            if (f != null && f.bank() < -1e-9) {
                return false;
            }
        }
        return true;
    }

    // ── PowerConfig variants (rebuild default with a few overridden knobs) ─────────────────

    private static dev.fablemc.factions.kernel.config.PowerConfig rebuild(
            dev.fablemc.factions.kernel.config.PowerConfig d,
            boolean killScaleEnabled, double killMin, double killMax,
            boolean deathStreakEnabled, double maxChangePerEvent,
            boolean buyEnabled, double buyMax, double buyCost) {
        return new dev.fablemc.factions.kernel.config.PowerConfig(
                d.perPlayerMax(), d.regenPerSecond(), d.lossOnDeath(), d.gracePeriodSeconds(),
                d.tickIntervalSeconds(), d.gainOnKillEnabled(), d.gainOnKillAmount(),
                killScaleEnabled, killMin, killMax, d.inactiveExclusionEnabled(),
                d.inactiveExclusionDays(), deathStreakEnabled, d.deathStreakWindowSeconds(),
                d.deathStreakMultiplier(), buyEnabled, buyCost, buyMax,
                d.sourceRegenOnlineEnabled(), d.sourceRegenOfflineEnabled(),
                d.sourceDeathLossEnabled(), d.sourceKillGainEnabled(), d.sourceBuyEnabled(),
                d.sourceRegenOnlineAmount(), d.sourceRegenOfflineAmount(),
                d.sourceDeathLossAmount(), d.sourceKillGainAmount(), d.minPower(), d.maxPower(),
                maxChangePerEvent, d.freezeBlocksAutomatic(), d.freezeBlocksRegen(),
                d.freezeAllowAdminBypass(), d.notifyActor(), d.notifyFaction(), d.notifyStaff(),
                d.zoneMultipliers(), d.worldMultiplierNames(), d.worldMultiplierValues());
    }

    static dev.fablemc.factions.kernel.config.PowerConfig pcKillScale(double min, double max) {
        var d = dev.fablemc.factions.kernel.config.PowerConfig.defaults();
        return rebuild(d, true, min, max, false, 0.0, false, 5.0, 100.0);
    }

    static dev.fablemc.factions.kernel.config.PowerConfig pcDeathStreak() {
        var d = dev.fablemc.factions.kernel.config.PowerConfig.defaults();
        return rebuild(d, false, 0.25, 2.0, true, 0.0, false, 5.0, 100.0);
    }

    static dev.fablemc.factions.kernel.config.PowerConfig pcEventClamp(double maxChange) {
        var d = dev.fablemc.factions.kernel.config.PowerConfig.defaults();
        return rebuild(d, false, 0.25, 2.0, false, maxChange, false, 5.0, 100.0);
    }

    static dev.fablemc.factions.kernel.config.PowerConfig pcBuy(double max, double cost) {
        var d = dev.fablemc.factions.kernel.config.PowerConfig.defaults();
        return rebuild(d, false, 0.25, 2.0, false, 0.0, true, max, cost);
    }

    /** Default config with {@code power} swapped in. */
    static ConfigImage cfgPower(dev.fablemc.factions.kernel.config.PowerConfig power) {
        ConfigImage d = ConfigImage.defaults();
        return new ConfigImage(d.limits(), d.language(), d.display(), d.updates(), d.metrics(),
                d.mergeEnabled(), d.flagDefaults(), power, d.land(), d.economy(), d.fly(), d.chat(),
                d.relation(), d.role(), d.zones(), d.notifications(), d.gui(), d.predefined(),
                d.storage(), dev.fablemc.factions.kernel.config.BakedTables.defaults(power));
    }
}
