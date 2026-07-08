package dev.fablemc.factions.kernel.reduce;

import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;

/**
 * System intents: config swap and retag (paged) / predefined seed marker / baseline import marker.
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved this code unchanged).
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
        int hw = arena.highWater();
        int processed = 0;
        int ord = cursor;
        for (; ord < hw && processed < Reducer.PAGE_SIZE; ord++) {
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
        if (ord < hw) {
            s.continuation(new SystemIntent.RetagPage(ord));
        }
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
