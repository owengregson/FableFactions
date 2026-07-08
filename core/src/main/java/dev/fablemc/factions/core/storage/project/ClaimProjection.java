package dev.fablemc.factions.core.storage.project;

import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.ids.ChunkKeys;

/**
 * Projects claim effects into the {@code board} table. Zone set/remove effects are not projected
 * here in W2b (see the projector class javadoc).
 *
 * <p><b>Owning thread(s):</b> {@code fable-storage}. <b>Mutability:</b> stateless static applier;
 * all mutable state lives in the passed {@link ProjectionContext}.
 */
public final class ClaimProjection {

    private ClaimProjection() {
    }

    public static void apply(ProjectionContext ctx, ClaimEffect.ClaimSet x) {
        String world = ctx.world(x.worldIdx());
        String fid = ctx.factionId(x.faction());
        if (world != null && fid != null) {
            ctx.add(new ProjectionOp(ctx.dialect().upsert("board",
                    new String[] {"world", "cx", "cz", "faction_id"},
                    new String[] {"world", "cx", "cz"}),
                    new Object[] {world, ChunkKeys.x(x.key()), ChunkKeys.z(x.key()), fid}));
        }
    }

    public static void apply(ProjectionContext ctx, ClaimEffect.ClaimRemoved x) {
        String world = ctx.world(x.worldIdx());
        if (world != null) {
            ctx.add(new ProjectionOp(ctx.dialect().deleteByKey("board",
                    new String[] {"world", "cx", "cz"}),
                    new Object[] {world, ChunkKeys.x(x.key()), ChunkKeys.z(x.key())}));
        }
    }
}
