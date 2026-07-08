package dev.fablemc.factions.core.storage.project;

import dev.fablemc.factions.kernel.effect.ChestEffect;

/**
 * Projects team-chest content changes into the {@code team_chests.blob_ref} column. Chest
 * create/delete are folded into faction lifecycle rows and are not projected here in W2b.
 *
 * <p><b>Owning thread(s):</b> {@code fable-storage}. <b>Mutability:</b> stateless static applier;
 * all mutable state lives in the passed {@link ProjectionContext}.
 */
public final class ChestProjection {

    private ChestProjection() {
    }

    public static void apply(ProjectionContext ctx, ChestEffect.ChestContentsChanged x) {
        String fid = ctx.factionId(x.faction());
        if (fid != null) {
            ctx.add(new ProjectionOp("UPDATE `team_chests` SET `blob_ref`=? WHERE `faction_id`=? "
                    + "AND `name`=?", new Object[] {x.blobRef(), fid, x.name()}));
        }
    }
}
