package dev.fablemc.factions.kernel.intent;

import java.util.UUID;

import dev.fablemc.factions.kernel.config.ConfigImage;

/**
 * The complete mutation vocabulary — every state change is one of these pure-data records
 * (proposal-C §4.2), plus the AM-5 paged continuations.
 *
 * <p><b>Owning thread(s):</b> constructed on any thread, drained and reduced by the single
 * writer. <b>Mutability:</b> immutable value hierarchy. <b>Reducer rule:</b> the reducer
 * exhaustively switches over these permitted records; there is no other way to change state.
 *
 * <p>The records are nested here so the sealed hierarchy is self-contained (the compiler infers
 * {@code permits} from the nested subtypes), guaranteeing switch exhaustiveness and keeping the
 * {@code seq}/order vocabulary in one place. Faction references are generation-tagged handles
 * (AM-6); players are UUIDs; worlds are dense {@code worldIdx}; chunk positions are packed keys.
 */
public sealed interface Intent {

    // ── Lifecycle ────────────────────────────────────────────────────────────────────────

    /** Create a new faction named {@code name}, owned by {@code owner}. */
    record CreateFaction(String name, UUID owner) implements Intent {
    }

    /** Disband {@code faction}; {@code byAdmin} bypasses ownership checks. Paged (AM-5). */
    record DisbandFaction(int faction, boolean byAdmin, UUID actor) implements Intent {
    }

    /** Rename {@code faction} to {@code newName} (re-renders the chat tag). */
    record RenameFaction(int faction, String newName, UUID actor) implements Intent {
    }

    /** Set {@code faction}'s description. */
    record SetDescription(int faction, String description, UUID actor) implements Intent {
    }

    /** Set {@code faction}'s MOTD ({@code null}/blank clears). */
    record SetMotd(int faction, String motd, UUID actor) implements Intent {
    }

    /** Transfer ownership of {@code faction} to {@code newOwner}. */
    record TransferOwnership(int faction, UUID newOwner, UUID actor) implements Intent {
    }

    /** Send a merge request from {@code sender} into {@code target}. */
    record SendMergeRequest(int sender, int target, UUID actor) implements Intent {
    }

    /** Accept a merge of {@code sender} into {@code target}. Paged (AM-5). */
    record AcceptMergeRequest(int sender, int target, UUID actor) implements Intent {
    }

    // ── Membership / invites ─────────────────────────────────────────────────────────────

    /** {@code player} joins {@code faction} via {@code viaInviteId}, or {@link #OPEN_JOIN}. */
    record JoinFaction(int faction, UUID player, long viaInviteId) implements Intent {
    }

    /** {@code player} leaves {@code faction}. */
    record LeaveFaction(int faction, UUID player) implements Intent {
    }

    /** {@code actor} kicks {@code target} from {@code faction}. */
    record KickMember(int faction, UUID actor, UUID target) implements Intent {
    }

    /** {@code inviter} invites {@code invitee} to {@code faction}. */
    record SendInvite(int faction, UUID inviter, UUID invitee) implements Intent {
    }

    /** {@code inviter} revokes {@code invitee}'s invite to {@code faction}. */
    record RevokeInvite(int faction, UUID inviter, UUID invitee) implements Intent {
    }

    /** {@code invitee} declines a specific faction's invite. */
    record DeclineInvite(int faction, UUID invitee) implements Intent {
    }

    /** {@code invitee} declines all pending invites. */
    record DeclineAllInvites(UUID invitee) implements Intent {
    }

    // ── Ranks / roles ────────────────────────────────────────────────────────────────────

    /** {@code actor} promotes {@code target} one rank in {@code faction}. */
    record PromoteMember(int faction, UUID actor, UUID target) implements Intent {
    }

    /** {@code actor} demotes {@code target} one rank in {@code faction}. */
    record DemoteMember(int faction, UUID actor, UUID target) implements Intent {
    }

    /** Create a custom role; {@code prefix} may be {@code null}. */
    record CreateRole(int faction, UUID actor, String name, int priority, String prefix)
            implements Intent {
    }

    /** Rename role {@code oldName} to {@code newName}. */
    record RenameRole(int faction, UUID actor, String oldName, String newName) implements Intent {
    }

    /** Set role {@code roleName}'s priority. */
    record SetRolePriority(int faction, UUID actor, String roleName, int priority)
            implements Intent {
    }

    /** Set role {@code roleName}'s prefix ({@code null} clears). */
    record SetRolePrefix(int faction, UUID actor, String roleName, String prefix)
            implements Intent {
    }

    /** Delete custom role {@code roleName}. */
    record DeleteRole(int faction, UUID actor, String roleName) implements Intent {
    }

    /** Assign role {@code roleName} to {@code target}. */
    record AssignRole(int faction, UUID actor, UUID target, String roleName) implements Intent {
    }

    // ── Claims / zones ───────────────────────────────────────────────────────────────────

    /** Claim {@code keys} in {@code worldIdx} for {@code faction}; {@code mode} is the claim mode. */
    record ClaimChunks(UUID player, int faction, int worldIdx, long[] keys, int mode)
            implements Intent {
    }

    /** Unclaim {@code keys} in {@code worldIdx} for {@code faction}. */
    record UnclaimChunks(UUID player, int faction, int worldIdx, long[] keys) implements Intent {
    }

    /** Unclaim all of {@code faction}'s land. Paged (AM-5). */
    record UnclaimAll(UUID actor, int faction) implements Intent {
    }

    /** Admin-claim unclaimed {@code keys} for {@code faction}. */
    record AdminClaimChunks(int faction, int worldIdx, long[] keys, UUID actor) implements Intent {
    }

    /** Admin-unclaim {@code keys} owned by {@code faction}. */
    record AdminUnclaimChunks(int faction, int worldIdx, long[] keys, UUID actor)
            implements Intent {
    }

    /** Assign {@code keys} to a system zone (SAFEZONE=0 / WARZONE=1). Paged (AM-5). */
    record SetZoneChunks(int zoneOrdinal, int worldIdx, long[] keys, UUID actor) implements Intent {
    }

    /** Remove one chunk from a system zone. */
    record RemoveZoneChunk(int zoneOrdinal, int worldIdx, long key, UUID actor) implements Intent {
    }

    // ── Relations ────────────────────────────────────────────────────────────────────────

    /** Declare a relation kind from {@code actorFaction} toward {@code targetFaction}. */
    record DeclareRelation(int actorFaction, int targetFaction, int kind, UUID actor)
            implements Intent {
    }

    // ── Power ────────────────────────────────────────────────────────────────────────────

    /** A death: streak/multiplier/kill-scaling are computed inside the reducer from config+state. */
    record RecordDeath(UUID dead, UUID killer, int worldIdx, long chunkKey) implements Intent {
    }

    /** Periodic power tick for tick {@code tick} (coalesced; O(online)). */
    record PowerTick(int tick) implements Intent {
    }

    /** Buy {@code points} power for {@code cost}, backed by open escrow {@code escrowId}. */
    record BuyPower(UUID player, double points, double cost, long escrowId) implements Intent {
    }

    /** Admin: set {@code target}'s power to {@code amount}. */
    record AdminPowerSet(UUID target, double amount, UUID actor, String reason) implements Intent {
    }

    /** Admin: add {@code amount} power to {@code target}. */
    record AdminPowerAdd(UUID target, double amount, UUID actor, String reason) implements Intent {
    }

    /** Admin: remove {@code amount} power from {@code target}. */
    record AdminPowerRemove(UUID target, double amount, UUID actor, String reason)
            implements Intent {
    }

    /** Admin: reset {@code target}'s power to the configured max. */
    record AdminPowerReset(UUID target, UUID actor, String reason) implements Intent {
    }

    /** Admin: freeze/unfreeze {@code target}'s power accrual. */
    record SetPowerFrozen(UUID target, boolean frozen, UUID actor, String reason)
            implements Intent {
    }

    // ── Economy ──────────────────────────────────────────────────────────────────────────

    /** Deposit phase-2: credit {@code faction}'s bank, settling escrow {@code escrowId}. */
    record CreditBank(int faction, double amount, UUID actor, long escrowId) implements Intent {
    }

    /** Request a bank withdrawal (reducer debits and opens the payout escrow). */
    record RequestBankWithdrawal(int faction, double amount, UUID actor) implements Intent {
    }

    /** Settle escrow {@code escrowId} with {@code outcome} ({@link #ESCROW_OK}/{@link #ESCROW_FAILED}). */
    record SettleEscrow(long escrowId, int outcome) implements Intent {
    }

    /** Transfer {@code amount} from {@code from}'s bank to {@code to}'s bank. */
    record TransferBank(int from, int to, double amount, UUID actor) implements Intent {
    }

    /** Periodic tax sweep for tick {@code tick} (coalesced). Paged (AM-5). */
    record TaxSweep(int tick) implements Intent {
    }

    // ── Homes / warps / chests ───────────────────────────────────────────────────────────

    /** Set {@code faction}'s home. */
    record SetHome(int faction, int worldIdx, double x, double y, double z, float yaw, float pitch,
                   UUID actor) implements Intent {
    }

    /** Clear {@code faction}'s home. */
    record UnsetHome(int faction, UUID actor) implements Intent {
    }

    /** Create/update warp {@code name}. */
    record SetWarp(int faction, String name, int worldIdx, double x, double y, double z, float yaw,
                   float pitch, UUID creator) implements Intent {
    }

    /** Delete warp {@code name}. */
    record DeleteWarp(int faction, String name, UUID actor) implements Intent {
    }

    /** Set/clear warp {@code name}'s password ({@code null}/blank clears). */
    record SetWarpPassword(int faction, String name, String password, UUID actor)
            implements Intent {
    }

    /** Set warp {@code name}'s per-use cost. */
    record SetWarpCost(int faction, String name, double cost, UUID actor) implements Intent {
    }

    /** Create team chest {@code name}. */
    record CreateChest(int faction, String name, UUID actor) implements Intent {
    }

    /** Delete team chest {@code name}. */
    record DeleteChest(int faction, String name, UUID actor) implements Intent {
    }

    /** Commit new contents (blob ref) for chest {@code name}, guarded by {@code sessionNonce}. */
    record CommitChestContents(int faction, String name, long blobRef, long sessionNonce,
                               UUID actor) implements Intent {
    }

    // ── Flags / prefs / session ──────────────────────────────────────────────────────────

    /** Set a faction {@code flag} (a {@code Faction.FLAG_*} ordinal); {@code byAdmin} bypasses locks. */
    record SetFactionFlag(int faction, int flag, boolean value, boolean byAdmin, UUID actor)
            implements Intent {
    }

    /** Toggle a per-player notification preference bit. */
    record SetNotifyPref(UUID player, int prefBit, boolean on) implements Intent {
    }

    /** Set a player's locale. */
    record SetLocale(UUID player, int localeIdx) implements Intent {
    }

    /** Set a player's auto-territory mode ({@code PlayerLedger.AUTO_MODE_*}). */
    record SetAutoTerritoryMode(UUID player, int mode) implements Intent {
    }

    /** Toggle a player's territory-title display. */
    record SetTerritoryTitles(UUID player, boolean on) implements Intent {
    }

    /** Toggle a player's faction flight state. */
    record SetFly(UUID player, boolean on) implements Intent {
    }

    /** Toggle a player's admin protection-override state (persisted). */
    record SetOverriding(UUID player, boolean on) implements Intent {
    }

    /** Set {@code faction}'s war-shield window (UTC start hour + duration). */
    record SetShield(int faction, int startHour, int durationHours, UUID actor) implements Intent {
    }

    /** Clear {@code faction}'s war shield. */
    record ClearShield(int faction, UUID actor) implements Intent {
    }

    /** A player connected: (re)establish the online set + settle offline power epoch. */
    record PlayerConnected(UUID player, String name, String localeHint) implements Intent {
    }

    /** A player disconnected: leave the online set + settle online power epoch. */
    record PlayerDisconnected(UUID player) implements Intent {
    }

    /** Acknowledge delivery of specific inbox entry ids (deletes exactly the delivered set). */
    record AckInbox(UUID player, long[] entryIds) implements Intent {
    }

    // ── System ───────────────────────────────────────────────────────────────────────────

    /** Swap the whole {@link ConfigImage} (reload). Tag re-render is paged via {@link RetagPage}. */
    record SwapConfig(ConfigImage config) implements Intent {
    }

    /** Seed a predefined faction preset (folded into {@link CreateFaction} at runtime). */
    record SeedPredefined(String name) implements Intent {
    }

    /** Boot-only migration import from a legacy baseline source. */
    record ImportBaseline(String source) implements Intent {
    }

    // ── Paged continuations (AM-5) ───────────────────────────────────────────────────────

    /** A disband page: {@code phase} 0=claims, 1=members, 2=final; {@code cursor} is progress. */
    record DisbandPage(int faction, int phase, int cursor, boolean byAdmin, UUID actor)
            implements Intent {
    }

    /** An unclaim-all page for {@code faction} at {@code cursor}. */
    record UnclaimAllPage(int faction, int cursor, UUID actor) implements Intent {
    }

    /** A merge page: {@code phase} 0=claims/warps, 1=members, 2=final; {@code cursor} is progress. */
    record MergePage(int sender, int target, int phase, int cursor, UUID actor) implements Intent {
    }

    /** A tax-sweep page over factions, at {@code cursor}, for tick {@code tick}. */
    record TaxSweepPage(int tick, int cursor) implements Intent {
    }

    /** A zone-assignment page for {@code keys[cursor..]}. */
    record ZonePage(int zoneOrdinal, int worldIdx, long[] keys, int cursor, UUID actor)
            implements Intent {
    }

    /** A config-swap tag re-render page over factions, at {@code cursor}. */
    record RetagPage(int cursor) implements Intent {
    }

    // ── Sentinels ────────────────────────────────────────────────────────────────────────

    /** {@link JoinFaction#viaInviteId()} value meaning "joined via the OPEN flag, no invite". */
    long OPEN_JOIN = -1L;
    /** {@link SettleEscrow#outcome()} success. */
    byte ESCROW_OK = 0;
    /** {@link SettleEscrow#outcome()} failure (Vault failed) — triggers compensation. */
    byte ESCROW_FAILED = 1;
}
