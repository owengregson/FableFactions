package dev.fablemc.factions.core.storage.project;

import dev.fablemc.factions.kernel.effect.AuditEffect;
import dev.fablemc.factions.kernel.effect.ChestEffect;
import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.EconomyEffect;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.effect.MembershipEffect;
import dev.fablemc.factions.kernel.effect.PowerEffect;
import dev.fablemc.factions.kernel.effect.PrefEffect;
import dev.fablemc.factions.kernel.effect.SessionEffect;

/**
 * The projector's translation router: dispatches each {@link Effect} to the per-domain applier that
 * turns it into buffered {@link ProjectionOp}s on the {@link ProjectionContext} (proposal-C §6.2).
 * All other effects (feedback, external requests, zones, invites, roles, homes, warps, config) are
 * not projected in W2b — see the {@code StorageProjector} class javadoc.
 *
 * <p><b>Owning thread(s):</b> {@code fable-storage}. <b>Mutability:</b> stateless static dispatch.
 */
public final class Projections {

    private Projections() {
    }

    public static void dispatch(ProjectionContext ctx, Effect e) {
        if (e instanceof ClaimEffect.ClaimSet x) {
            ClaimProjection.apply(ctx, x);
        } else if (e instanceof ClaimEffect.ClaimRemoved x) {
            ClaimProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.FactionCreated x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.FactionRenamed x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.DescriptionChanged x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.MotdChanged x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.OwnershipTransferred x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof PowerEffect.RaidableChanged x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof PrefEffect.ShieldChanged x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof EconomyEffect.BankChanged x) {
            EconomyProjection.apply(ctx, x);
        } else if (e instanceof EconomyEffect.TaxCharged x) {
            EconomyProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.FactionDisbanded x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof MembershipEffect.MemberJoined x) {
            MemberProjection.apply(ctx, x);
        } else if (e instanceof MembershipEffect.MemberLeft x) {
            MemberProjection.apply(ctx, x);
        } else if (e instanceof PowerEffect.PowerChanged x) {
            HistoryProjection.apply(ctx, x);
        } else if (e instanceof PowerEffect.PowerFrozenChanged x) {
            HistoryProjection.apply(ctx, x);
        } else if (e instanceof PowerEffect.DeathStreakAdvanced x) {
            HistoryProjection.apply(ctx, x);
        } else if (e instanceof PrefEffect.LocaleChanged x) {
            MemberProjection.apply(ctx, x);
        } else if (e instanceof PrefEffect.AutoModeChanged x) {
            MemberProjection.apply(ctx, x);
        } else if (e instanceof PrefEffect.OverrideChanged x) {
            MemberProjection.apply(ctx, x);
        } else if (e instanceof SessionEffect.SessionStarted x) {
            SessionProjection.apply(ctx, x);
        } else if (e instanceof SessionEffect.SessionEnded x) {
            SessionProjection.apply(ctx, x);
        } else if (e instanceof AuditEffect.AuditRecorded x) {
            HistoryProjection.apply(ctx, x);
        } else if (e instanceof SessionEffect.InboxQueued x) {
            SessionProjection.apply(ctx, x);
        } else if (e instanceof SessionEffect.InboxDelivered x) {
            SessionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.MergeRequested x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.MergeCompleted x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof ChestEffect.ChestContentsChanged x) {
            ChestProjection.apply(ctx, x);
        }
    }
}
