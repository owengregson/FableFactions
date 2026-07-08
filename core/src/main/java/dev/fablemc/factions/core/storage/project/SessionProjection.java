package dev.fablemc.factions.core.storage.project;

import dev.fablemc.factions.kernel.effect.SessionEffect;

/**
 * Projects session effects: last-activity updates on the {@code players} table and the
 * {@code faction_inbox} queue/deliver lifecycle.
 *
 * <p><b>Owning thread(s):</b> {@code fable-storage}. <b>Mutability:</b> stateless static applier;
 * all mutable state lives in the passed {@link ProjectionContext}.
 */
public final class SessionProjection {

    private SessionProjection() {
    }

    public static void apply(ProjectionContext ctx, SessionEffect.SessionStarted x) {
        ctx.playerUpsert(x.player(), "last_activity", x.lastActivity());
    }

    public static void apply(ProjectionContext ctx, SessionEffect.SessionEnded x) {
        ctx.playerUpsert(x.player(), "last_activity", x.lastActivity());
    }

    public static void apply(ProjectionContext ctx, SessionEffect.InboxQueued x) {
        String msg = x.key() == null ? "" : x.key().key();
        ctx.upsertById("faction_inbox", new String[] {"id", "player_id", "message", "created_at"},
                ctx.ledgerId(x.seq()), x.player().toString(), msg, ctx.now());
    }

    public static void apply(ProjectionContext ctx, SessionEffect.InboxDelivered x) {
        ctx.deleteBy("faction_inbox", "player_id", x.player().toString());
    }
}
