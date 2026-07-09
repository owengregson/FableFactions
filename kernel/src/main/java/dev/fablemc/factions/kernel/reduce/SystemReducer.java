package dev.fablemc.factions.kernel.reduce;

import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.FactionClaimList;

/**
 * Reduces the system intents: config swap and retag (paged) / predefined seed marker / baseline
 * import marker / aggregate reconciliation sweep (paged, AM-4).
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved the code; the P3 sweep standardized the
 * guard/emission shapes without behavior change), except for the newly-implemented
 * {@link #reconcileSweep} (AM-4), which had no prior monolithic counterpart.
 */
final class SystemReducer {

    private SystemReducer() {
    }

    static void reduce(ReduceSupport s, SystemIntent i) {
        if (i instanceof SystemIntent.SwapConfig x) {
            swapConfig(s, x);
        } else if (i instanceof SystemIntent.SeedPredefined x) {
            seedPredefined(s, x);
        } else if (i instanceof SystemIntent.ImportBaseline x) {
            importBaseline(s, x);
        } else if (i instanceof SystemIntent.RetagPage x) {
            retagPage(s, x.cursor());
        } else if (i instanceof SystemIntent.ReconcileSweep x) {
            reconcileSweep(s, x.cursor());
        } else {
            throw new IllegalStateException("unhandled system intent: " + i.getClass().getName());
        }
    }

    static void swapConfig(ReduceSupport s, SystemIntent.SwapConfig c) {
        s.state = s.state.withConfig(c.config());
        s.emit(new SystemEffect.ConfigSwapped(s.seq, s.origin, "reload"));
        s.continuation(new SystemIntent.RetagPage(0));
    }

    static void retagPage(ReduceSupport s, int cursor) {
        FactionArena arena = s.state.factions();
        int highWater = arena.highWater();
        int processed = 0;
        int ord = cursor;
        for (; ord < highWater && processed < Reducer.PAGE_SIZE; ord++) {
            Faction f = arena.at(ord);
            if (f == null || !f.isNormal()) {
                continue;
            }
            processed++;
            String tag = f.name();
            if (!tag.equals(f.tagLegacy()) || !tag.equals(f.tagMini())) {
                Faction nf = FactionEdit.withName(f, f.name(), f.nameFolded(), tag, tag);
                s.state = s.state.withFactions(s.state.factions().replace(ord, nf));
                arena = s.state.factions();
            }
        }
        if (ord < highWater) {
            s.continuation(new SystemIntent.RetagPage(ord));
        }
    }

    /**
     * AM-4 aggregate reconciliation: recomputes a rotating, bounded window of factions'
     * incrementally-maintained {@code landCount} from ground truth (the claim atlas), self-heals
     * any drift, and emits one WARN {@link SystemEffect.AggregateDriftDetected} per corrected
     * faction — drift is a reducer-bug signal and is <b>never silent</b>. When more ordinals
     * remain the sweep requests a continuation at the next window (AM-5), so one invocation
     * touches at most {@link Reducer#PAGE_SIZE} faction ordinals and the 30-min rotation stays
     * within the per-step budget. A scheduled sweep restarts the rotation at {@code cursor == 0}.
     *
     * <p>Ground truth is the atlas: it is the claim authority every protection read consults, so a
     * single atlas scan tallies each in-window ordinal's real claim count, and BOTH derived views
     * — the scalar {@code landCount} and the {@link FactionClaimList} reverse index (which the
     * dynmap layer renders from) — are rebuilt to it in lockstep on drift.
     *
     * <p><b>Aggregates reconciled here:</b> only {@code landCount}. {@code memberCount} is not a
     * stored {@link Faction} field (it is recomputed on demand from the ledger), and
     * {@code powerCacheSum} is not incrementally maintained by the reducer, so neither has a stored
     * value to reconcile against a recompute; both are recomputed from ground truth at their point
     * of use and are outside this sweep.
     */
    static void reconcileSweep(ReduceSupport s, int cursor) {
        FactionArena arena = s.state.factions();
        int highWater = arena.highWater();
        int start = Math.max(cursor, FactionHandle.FIRST_NORMAL_ORDINAL);
        if (start >= highWater) {
            return; // rotation complete; the next scheduled sweep restarts at ordinal 0
        }
        int end = Math.min(highWater, start + Reducer.PAGE_SIZE);

        // Ground truth: one atlas scan tallies real claims per ordinal in [start, end). Zones
        // (ordinals < FIRST_NORMAL_ORDINAL) and out-of-window owners are ignored. start/end are
        // effectively final; real's reference is final (its contents are mutated in the scan).
        int[] real = new int[end - start];
        s.state.claims().forEachClaim((worldIdx, chunkKey, ownerHandle) -> {
            int ord = FactionHandle.ordinal(ownerHandle);
            if (ord >= start && ord < end && FactionHandle.isNormalOrdinal(ord)) {
                real[ord - start]++;
            }
        });

        for (int ord = start; ord < end; ord++) {
            Faction f = arena.at(ord);
            if (f == null || !f.isNormal()) {
                continue;
            }
            int recomputed = real[ord - start];
            int stored = f.landCount();
            if (recomputed != stored) {
                // Self-heal: rebuild both the scalar aggregate and the reverse index from truth.
                s.state = s.state.withFactions(s.state.factions().replace(ord,
                        FactionEdit.withLand(f, recomputed, rebuildClaimList(s, ord))));
                arena = s.state.factions();
                s.emit(new SystemEffect.AggregateDriftDetected(s.seq, s.origin,
                        arena.handleOf(ord), "landCount", stored, recomputed));
            }
        }

        if (end < highWater) {
            s.continuation(new SystemIntent.ReconcileSweep(end));
        }
    }

    /** Rebuilds a faction's reverse claim index from atlas ground truth (drift heal only). */
    static FactionClaimList rebuildClaimList(ReduceSupport s, int factionOrd) {
        FactionClaimList list = FactionClaimList.empty();
        for (long[] wk : s.collectFactionClaims(factionOrd, Integer.MAX_VALUE)) {
            list = list.add((int) wk[0], wk[1]);
        }
        return list;
    }

    static void seedPredefined(ReduceSupport s, SystemIntent.SeedPredefined c) {
        // Predefined seeding is folded into CreateFaction upstream (:core); no kernel state
        // change here beyond acknowledging the intent.
    }

    static void importBaseline(ReduceSupport s, SystemIntent.ImportBaseline c) {
        // Boot migration is performed by the :core baseline loader before the writer starts;
        // in the kernel this is a no-op marker.
    }
}
