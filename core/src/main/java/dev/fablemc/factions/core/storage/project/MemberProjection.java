package dev.fablemc.factions.core.storage.project;

import dev.fablemc.factions.kernel.effect.MembershipEffect;
import dev.fablemc.factions.kernel.effect.PrefEffect;

/**
 * Projects membership + player-pref effects into the {@code players} table: join (faction link),
 * leave (link clear), and the locale/auto-territory/override columns.
 *
 * <p><b>Owning thread(s):</b> {@code fable-storage}. <b>Mutability:</b> stateless static applier;
 * all mutable state lives in the passed {@link ProjectionContext}.
 */
public final class MemberProjection {

    private MemberProjection() {
    }

    public static void apply(ProjectionContext ctx, MembershipEffect.MemberJoined x) {
        String fid = ctx.factionId(x.faction());
        if (fid != null) {
            ctx.upsertById("players", new String[] {"id", "faction_id"}, x.player().toString(), fid);
        }
    }

    public static void apply(ProjectionContext ctx, MembershipEffect.MemberLeft x) {
        ctx.op("UPDATE `players` SET `faction_id`=NULL, `rank_id`=NULL WHERE `id`=?",
                x.player().toString());
    }

    public static void apply(ProjectionContext ctx, PrefEffect.LocaleChanged x) {
        ctx.playerUpsert(x.player(), "locale", localeName(x.localeIdx()));
    }

    public static void apply(ProjectionContext ctx, PrefEffect.AutoModeChanged x) {
        ctx.playerUpsert(x.player(), "auto_territory_mode", x.mode());
    }

    public static void apply(ProjectionContext ctx, PrefEffect.OverrideChanged x) {
        ctx.playerUpsert(x.player(), "overriding", x.on() ? 1 : 0);
    }

    private static String localeName(int localeIdx) {
        return localeIdx < 0 ? null : Integer.toString(localeIdx);
    }
}
