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
 * Routing is two-level, mirroring the journal codec: first by sealed domain sub-interface, then by
 * record within the domain — so a non-projected domain (role, relation, travel, system, feedback,
 * external) costs one type test, and each domain method documents exactly which of its records
 * project in W2b. Non-projected records fall through as deliberate no-ops — see the
 * {@code StorageProjector} class javadoc.
 *
 * <p><b>Owning thread(s):</b> {@code fable-storage}. <b>Mutability:</b> stateless static dispatch.
 */
public final class Projections {

    private Projections() {
    }

    public static void dispatch(ProjectionContext ctx, Effect e) {
        if (e instanceof LifecycleEffect x) {
            lifecycle(ctx, x);
        } else if (e instanceof ClaimEffect x) {
            claim(ctx, x);
        } else if (e instanceof MembershipEffect x) {
            membership(ctx, x);
        } else if (e instanceof PowerEffect x) {
            power(ctx, x);
        } else if (e instanceof PrefEffect x) {
            pref(ctx, x);
        } else if (e instanceof EconomyEffect x) {
            economy(ctx, x);
        } else if (e instanceof SessionEffect x) {
            session(ctx, x);
        } else if (e instanceof AuditEffect x) {
            audit(ctx, x);
        } else if (e instanceof ChestEffect x) {
            chest(ctx, x);
        }
    }

    /** Every lifecycle record projects — all into the faction row / its lifecycle cascades. */
    private static void lifecycle(ProjectionContext ctx, LifecycleEffect e) {
        if (e instanceof LifecycleEffect.FactionCreated x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.FactionDisbanded x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.FactionRenamed x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.DescriptionChanged x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.MotdChanged x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.OwnershipTransferred x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.MergeRequested x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof LifecycleEffect.MergeCompleted x) {
            FactionProjection.apply(ctx, x);
        }
    }

    /** Claim set/remove project into {@code board}; zone set/remove do not (W2b). */
    private static void claim(ProjectionContext ctx, ClaimEffect e) {
        if (e instanceof ClaimEffect.ClaimSet x) {
            ClaimProjection.apply(ctx, x);
        } else if (e instanceof ClaimEffect.ClaimRemoved x) {
            ClaimProjection.apply(ctx, x);
        }
    }

    /** Join/leave project into {@code players}; invite create/remove do not (W2b). */
    private static void membership(ProjectionContext ctx, MembershipEffect e) {
        if (e instanceof MembershipEffect.MemberJoined x) {
            MemberProjection.apply(ctx, x);
        } else if (e instanceof MembershipEffect.MemberLeft x) {
            MemberProjection.apply(ctx, x);
        }
    }

    /** Raidable flips the faction row; the rest land in player power columns + history ledger. */
    private static void power(ProjectionContext ctx, PowerEffect e) {
        if (e instanceof PowerEffect.PowerChanged x) {
            HistoryProjection.apply(ctx, x);
        } else if (e instanceof PowerEffect.PowerFrozenChanged x) {
            HistoryProjection.apply(ctx, x);
        } else if (e instanceof PowerEffect.DeathStreakAdvanced x) {
            HistoryProjection.apply(ctx, x);
        } else if (e instanceof PowerEffect.RaidableChanged x) {
            FactionProjection.apply(ctx, x);
        }
    }

    /** Shield is a faction column; locale/auto-mode/override are player columns; the rest W2b no-ops. */
    private static void pref(ProjectionContext ctx, PrefEffect e) {
        if (e instanceof PrefEffect.ShieldChanged x) {
            FactionProjection.apply(ctx, x);
        } else if (e instanceof PrefEffect.LocaleChanged x) {
            MemberProjection.apply(ctx, x);
        } else if (e instanceof PrefEffect.AutoModeChanged x) {
            MemberProjection.apply(ctx, x);
        } else if (e instanceof PrefEffect.OverrideChanged x) {
            MemberProjection.apply(ctx, x);
        }
    }

    /** Both economy records project — money column + bank ledger. */
    private static void economy(ProjectionContext ctx, EconomyEffect e) {
        if (e instanceof EconomyEffect.BankChanged x) {
            EconomyProjection.apply(ctx, x);
        } else if (e instanceof EconomyEffect.TaxCharged x) {
            EconomyProjection.apply(ctx, x);
        }
    }

    /** Every session record projects — last-activity + inbox lifecycle. */
    private static void session(ProjectionContext ctx, SessionEffect e) {
        if (e instanceof SessionEffect.SessionStarted x) {
            SessionProjection.apply(ctx, x);
        } else if (e instanceof SessionEffect.SessionEnded x) {
            SessionProjection.apply(ctx, x);
        } else if (e instanceof SessionEffect.InboxQueued x) {
            SessionProjection.apply(ctx, x);
        } else if (e instanceof SessionEffect.InboxDelivered x) {
            SessionProjection.apply(ctx, x);
        }
    }

    /** The audit ledger is append-only history. */
    private static void audit(ProjectionContext ctx, AuditEffect e) {
        if (e instanceof AuditEffect.AuditRecorded x) {
            HistoryProjection.apply(ctx, x);
        }
    }

    /** Contents-blob refs project; chest create/delete are folded into faction rows (W2b). */
    private static void chest(ProjectionContext ctx, ChestEffect e) {
        if (e instanceof ChestEffect.ChestContentsChanged x) {
            ChestProjection.apply(ctx, x);
        }
    }
}
