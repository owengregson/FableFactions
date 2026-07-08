package dev.fablemc.factions.core.storage.project;

import dev.fablemc.factions.kernel.effect.AuditEffect;
import dev.fablemc.factions.kernel.effect.PowerEffect;

/**
 * Projects power + audit effects into the {@code players} power columns and the append-only
 * {@code power_history} / {@code audit_logs} ledgers.
 *
 * <p><b>Owning thread(s):</b> {@code fable-storage}. <b>Mutability:</b> stateless static applier;
 * all mutable state lives in the passed {@link ProjectionContext}.
 */
public final class HistoryProjection {

    private HistoryProjection() {
    }

    public static void apply(ProjectionContext ctx, PowerEffect.PowerChanged x) {
        ctx.add(new ProjectionOp(ctx.dialect().upsert("players",
                new String[] {"id", "power"}, new String[] {"id"}),
                new Object[] {x.player().toString(), x.after()}));
        ctx.add(powerHistoryInsert(ctx, x.seq(), x.player().toString(), x.after() - x.before(),
                x.reasonCode(), x.after()));
    }

    public static void apply(ProjectionContext ctx, PowerEffect.PowerFrozenChanged x) {
        ctx.playerUpsert(x.player(), "power_frozen", x.frozen() ? 1 : 0);
    }

    public static void apply(ProjectionContext ctx, PowerEffect.DeathStreakAdvanced x) {
        ctx.add(new ProjectionOp(ctx.dialect().upsert("players",
                new String[] {"id", "death_streak", "last_death_at"}, new String[] {"id"}),
                new Object[] {x.player().toString(), x.streak(), ctx.now()}));
    }

    public static void apply(ProjectionContext ctx, AuditEffect.AuditRecorded x) {
        String fid = ctx.factionId(x.faction());
        if (fid != null) {
            ctx.add(auditInsert(ctx, x.seq(), fid, x.actor() == null ? null : x.actor().toString(),
                    x.action() == null ? "unknown" : x.action().id(), x.detail()));
        }
    }

    private static ProjectionOp powerHistoryInsert(ProjectionContext ctx, long seq, String player,
                                                   double delta, String reason, double after) {
        return new ProjectionOp(ctx.dialect().upsert("power_history", new String[] {"id",
                "player_uuid", "delta", "reason", "power_after", "created_at"}, new String[] {"id"}),
                new Object[] {ctx.ledgerId(seq), player, delta, reason == null ? "" : reason, after,
                        ctx.now()});
    }

    private static ProjectionOp auditInsert(ProjectionContext ctx, long seq, String factionId,
                                            String actor, String action, String detail) {
        return new ProjectionOp(ctx.dialect().upsert("audit_logs", new String[] {"id", "faction_id",
                "actor_uuid", "action", "detail", "created_at"}, new String[] {"id"}),
                new Object[] {ctx.ledgerId(seq), factionId, actor, action, detail, ctx.now()});
    }
}
