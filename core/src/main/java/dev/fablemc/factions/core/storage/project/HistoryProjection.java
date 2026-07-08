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
        ctx.playerUpsert(x.player(), "power", x.after());
        String reason = x.reasonCode() == null ? "" : x.reasonCode();
        ctx.upsertById("power_history", new String[] {"id", "player_uuid", "delta", "reason",
                "power_after", "created_at"},
                ctx.ledgerId(x.seq()), x.player().toString(), x.after() - x.before(), reason,
                x.after(), ctx.now());
    }

    public static void apply(ProjectionContext ctx, PowerEffect.PowerFrozenChanged x) {
        ctx.playerUpsert(x.player(), "power_frozen", x.frozen() ? 1 : 0);
    }

    public static void apply(ProjectionContext ctx, PowerEffect.DeathStreakAdvanced x) {
        ctx.upsertById("players", new String[] {"id", "death_streak", "last_death_at"},
                x.player().toString(), x.streak(), ctx.now());
    }

    public static void apply(ProjectionContext ctx, AuditEffect.AuditRecorded x) {
        String fid = ctx.factionId(x.faction());
        if (fid != null) {
            ctx.upsertById("audit_logs", new String[] {"id", "faction_id", "actor_uuid", "action",
                    "detail", "created_at"},
                    ctx.ledgerId(x.seq()), fid, x.actor() == null ? null : x.actor().toString(),
                    x.action() == null ? "unknown" : x.action().id(), x.detail(), ctx.now());
        }
    }
}
