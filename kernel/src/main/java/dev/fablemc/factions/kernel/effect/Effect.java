package dev.fablemc.factions.kernel.effect;

import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * The complete effect vocabulary — the ordered, seq-numbered output of the reducer
 * (proposal-C §4.3). Effects drive derived indexes, storage projection, feedback, integrations
 * and the API event bridge; the journal of effects is the complete replayable history.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out to journal/storage/subscribers.
 * <b>Mutability:</b> immutable value hierarchy. <b>Reducer rule:</b> only the reducer emits
 * effects, and it never emits text — feedback effects carry a {@link MessageKey} plus args.
 *
 * <p>Every effect carries its {@code long seq} (the writer's total order) and the causing
 * intent's {@link Origin} for traceability. The records are split into per-domain sealed
 * sub-interfaces (W25-REORG §P1) so no single file is monolithic; each sub-interface nests its
 * own records, referenced as (for example) {@code ClaimEffect.ClaimSet}. The nested layout keeps
 * the sealed hierarchy self-contained and switch-exhaustive (the storage projector and API bridge
 * each switch over these transitively).
 */
public sealed interface Effect permits LifecycleEffect, MembershipEffect, RoleEffect, ClaimEffect,
        RelationEffect, PowerEffect, EconomyEffect, TravelEffect, ChestEffect, PrefEffect,
        SessionEffect, AuditEffect, SystemEffect, FeedbackEffect, ExternalEffect {

    /** The writer-assigned total-order sequence number every effect carries. */
    long seq();

    /** The origin of the intent that produced this effect. */
    Origin origin();
}
