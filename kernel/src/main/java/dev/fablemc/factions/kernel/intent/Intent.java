package dev.fablemc.factions.kernel.intent;

/**
 * The complete mutation vocabulary — every state change is one of these pure-data records
 * (proposal-C §4.2), plus the AM-5 paged continuations.
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, drained and reduced by the single
 * writer. <b>Mutability:</b> immutable value hierarchy. <b>Reducer rule:</b> the reducer
 * exhaustively switches over these permitted records; there is no other way to change state.
 *
 * <p>The records are split into per-domain sealed sub-interfaces (W25-REORG §P1) so no single
 * file is monolithic; each sub-interface nests its own records, referenced as (for example)
 * {@code ClaimIntent.ClaimChunks}. The nested layout keeps the sealed hierarchy self-contained
 * (the compiler infers {@code permits} transitively), guaranteeing switch exhaustiveness and
 * keeping the {@code seq}/order vocabulary in one place. Faction references are generation-tagged
 * handles (AM-6); players are UUIDs; worlds are dense {@code worldIdx}; chunk positions are packed
 * keys.
 */
public sealed interface Intent permits LifecycleIntent, MembershipIntent, RoleIntent, ClaimIntent,
        RelationIntent, PowerIntent, EconomyIntent, TravelIntent, ChestIntent, PrefIntent,
        SessionIntent, SystemIntent {
}
