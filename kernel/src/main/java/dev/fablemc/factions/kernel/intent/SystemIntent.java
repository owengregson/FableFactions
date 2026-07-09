package dev.fablemc.factions.kernel.intent;

import dev.fablemc.factions.kernel.config.ConfigImage;

/**
 * System intents: config swap (its tag re-render is paged via {@link RetagPage}), predefined
 * seeding, boot baseline import, the config-swap tag re-render page, and the low-frequency
 * aggregate reconciliation sweep ({@link ReconcileSweep}, AM-4).
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. See {@link Intent} for the hierarchy contract.
 */
public sealed interface SystemIntent extends Intent
        permits SystemIntent.SwapConfig, SystemIntent.SeedPredefined, SystemIntent.ImportBaseline,
        SystemIntent.RetagPage, SystemIntent.ReconcileSweep {

    /** Swap the whole {@link ConfigImage} (reload). Tag re-render is paged via {@link RetagPage}. */
    record SwapConfig(ConfigImage config) implements SystemIntent {
    }

    /** Seed a predefined faction preset (folded into {@code CreateFaction} at runtime). */
    record SeedPredefined(String name) implements SystemIntent {
    }

    /** Boot-only migration import from a legacy baseline source. */
    record ImportBaseline(String source) implements SystemIntent {
    }

    /** A config-swap tag re-render page over factions, at {@code cursor}. */
    record RetagPage(int cursor) implements SystemIntent {
    }

    /**
     * A low-frequency aggregate-reconciliation page (AM-4) beginning at faction ordinal
     * {@code cursor}. Recomputes a rotating, bounded window of factions' incremental aggregates
     * from ground truth and self-heals any drift. Issued periodically (every ~30 min) by the
     * {@code :core} scheduler at {@code cursor == 0}; the reducer pages the rotation forward with
     * a continuation (AM-5) so one invocation stays within the per-step budget.
     */
    record ReconcileSweep(int cursor) implements SystemIntent {
    }
}
