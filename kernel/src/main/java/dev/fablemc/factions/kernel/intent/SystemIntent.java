package dev.fablemc.factions.kernel.intent;

import dev.fablemc.factions.kernel.config.ConfigImage;

/**
 * System intents: config swap (its tag re-render is paged via {@link RetagPage}), predefined
 * seeding, boot baseline import, and the config-swap tag re-render page.
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, reduced by the single writer.
 * <b>Mutability:</b> immutable value records. See {@link Intent} for the hierarchy contract.
 */
public sealed interface SystemIntent extends Intent
        permits SystemIntent.SwapConfig, SystemIntent.SeedPredefined, SystemIntent.ImportBaseline,
        SystemIntent.RetagPage {

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
}
