package dev.fablemc.factions.core.storage.project;

import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.effect.PowerEffect;
import dev.fablemc.factions.kernel.effect.PrefEffect;
import dev.fablemc.factions.kernel.state.NameIndex;

/**
 * Projects faction-row effects into the {@code factions} table (creation, rename, description/motd,
 * ownership, raidable/shield state) plus faction lifecycle cascades: disband (whole-graph delete)
 * and merge-request insert/clear.
 *
 * <p><b>Owning thread(s):</b> {@code fable-storage}. <b>Mutability:</b> stateless static applier;
 * all mutable state lives in the passed {@link ProjectionContext}.
 */
public final class FactionProjection {

    private FactionProjection() {
    }

    public static void apply(ProjectionContext ctx, LifecycleEffect.FactionCreated x) {
        ctx.putFaction(x.faction(), x.id().toString());
        ctx.add(new ProjectionOp(ctx.dialect().upsert("factions",
                new String[] {"id", "name", "name_folded"}, new String[] {"id"}),
                new Object[] {x.id().toString(), x.name(), NameIndex.fold(x.name())}));
    }

    public static void apply(ProjectionContext ctx, LifecycleEffect.FactionRenamed x) {
        String fid = ctx.factionId(x.faction());
        if (fid != null) {
            ctx.add(new ProjectionOp("UPDATE `factions` SET `name`=?, `name_folded`=? WHERE `id`=?",
                    new Object[] {x.newName(), NameIndex.fold(x.newName()), fid}));
        }
    }

    public static void apply(ProjectionContext ctx, LifecycleEffect.DescriptionChanged x) {
        ctx.factionUpdate(x.faction(), "`description`=?", x.description());
    }

    public static void apply(ProjectionContext ctx, LifecycleEffect.MotdChanged x) {
        ctx.factionUpdate(x.faction(), "`motd`=?", x.motd());
    }

    public static void apply(ProjectionContext ctx, LifecycleEffect.OwnershipTransferred x) {
        ctx.factionUpdate(x.faction(), "`owner_id`=?",
                x.newOwner() == null ? null : x.newOwner().toString());
    }

    public static void apply(ProjectionContext ctx, PowerEffect.RaidableChanged x) {
        ctx.factionUpdate(x.faction(), "`is_raidable`=?", x.nowRaidable() ? 1 : 0);
    }

    public static void apply(ProjectionContext ctx, PrefEffect.ShieldChanged x) {
        String fid = ctx.factionId(x.faction());
        if (fid != null) {
            ctx.add(new ProjectionOp("UPDATE `factions` SET `shield_start_hour`=?, "
                    + "`shield_duration_hours`=? WHERE `id`=?",
                    new Object[] {x.startHour(), x.durationHours(), fid}));
        }
    }

    public static void apply(ProjectionContext ctx, LifecycleEffect.FactionDisbanded x) {
        String fid = ctx.removeFaction(x.faction());
        if (fid != null) {
            disbandCascade(ctx, fid);
        }
    }

    public static void apply(ProjectionContext ctx, LifecycleEffect.MergeRequested x) {
        String s = ctx.factionId(x.sender());
        String t = ctx.factionId(x.target());
        if (s != null && t != null) {
            ctx.add(new ProjectionOp(ctx.dialect().upsert("merge_requests",
                    new String[] {"id", "sender_faction_id", "target_faction_id", "actor_id",
                            "created_at"}, new String[] {"id"}),
                    new Object[] {ctx.ledgerId(x.seq()), s, t, s, ctx.now()}));
        }
    }

    public static void apply(ProjectionContext ctx, LifecycleEffect.MergeCompleted x) {
        String s = ctx.factionId(x.sender());
        if (s != null) {
            ctx.add(new ProjectionOp(ctx.dialect().deleteByColumn("merge_requests",
                    "sender_faction_id"), new Object[] {s}));
        }
    }

    private static void disbandCascade(ProjectionContext ctx, String fid) {
        ctx.add(new ProjectionOp(ctx.dialect().deleteByColumn("board", "faction_id"),
                new Object[] {fid}));
        ctx.add(new ProjectionOp(ctx.dialect().deleteByColumn("warps", "faction_id"),
                new Object[] {fid}));
        ctx.add(new ProjectionOp(ctx.dialect().deleteByColumn("ranks", "faction_id"),
                new Object[] {fid}));
        ctx.add(new ProjectionOp(ctx.dialect().deleteByColumn("team_chests", "faction_id"),
                new Object[] {fid}));
        ctx.add(new ProjectionOp(ctx.dialect().deleteByColumn("invitations", "faction_id"),
                new Object[] {fid}));
        ctx.add(new ProjectionOp(ctx.dialect().deleteByColumn("merge_requests", "sender_faction_id"),
                new Object[] {fid}));
        ctx.add(new ProjectionOp("UPDATE `players` SET `faction_id`=NULL, `rank_id`=NULL "
                + "WHERE `faction_id`=?", new Object[] {fid}));
        ctx.add(new ProjectionOp(ctx.dialect().deleteByColumn("factions", "id"),
                new Object[] {fid}));
    }
}
