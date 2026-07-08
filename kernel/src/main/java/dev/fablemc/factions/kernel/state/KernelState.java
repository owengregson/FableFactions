package dev.fablemc.factions.kernel.state;

import dev.fablemc.factions.kernel.config.ConfigImage;

/**
 * The single immutable value holding all domain state (proposal-C §4.1). Published through one
 * {@code AtomicReference<KernelSnapshot>}; constructed only by the {@code fable-kernel} writer.
 *
 * <p><b>Owning thread(s):</b> constructed by the writer; read by everyone via the snapshot.
 * <b>Mutability:</b> immutable value; every reachable array is frozen by discipline (AM-8), so
 * safe publication is the writer's single {@code AtomicReference.set}. <b>Reducer rule:</b> only
 * the reducer constructs a new {@code KernelState}, via clone-then-wrap-in-new-record (the
 * {@code with*} helpers below).
 *
 * <p>Per AM-2/§4.1 the member store is the COW-sharded {@link PlayerLedger} (not a whole-arena
 * copy). {@code config} is a field of the state (config is state; reload is a {@code SwapConfig}
 * intent), so a swap is serialized with every other mutation.
 */
public record KernelState(
        long version,
        int tick,
        ConfigImage config,
        FactionArena factions,
        PlayerLedger ledger,
        MemberDirectory members,
        ClaimAtlas claims,
        WarpTable warps,
        ChestTable chests,
        InviteTable invites,
        MergeTable mergeRequests,
        InboxTable inbox,
        EscrowTable escrows,
        NameIndex factionNames,
        ZoneStats zones,
        OnlineSet online) {

    /** An empty state (version 0, tick 0) carrying {@code config} and empty structures. */
    public static KernelState empty(ConfigImage config) {
        return new KernelState(
                0L, 0, config,
                FactionArena.empty(),
                PlayerLedger.empty(),
                MemberDirectory.empty(),
                ClaimAtlas.empty(),
                WarpTable.empty(),
                ChestTable.empty(),
                InviteTable.empty(),
                MergeTable.empty(),
                InboxTable.empty(),
                EscrowTable.empty(),
                NameIndex.empty(),
                ZoneStats.empty(),
                OnlineSet.empty());
    }

    /** An empty state carrying the reference-default config. */
    public static KernelState empty() {
        return empty(ConfigImage.defaults());
    }

    // ── COW field replacers (used by the reducer to build the next state) ─────────────────

    public KernelState withVersionTick(long version, int tick) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withConfig(ConfigImage config) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withFactions(FactionArena factions) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withLedger(PlayerLedger ledger) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withMembers(MemberDirectory members) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withClaims(ClaimAtlas claims) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withWarps(WarpTable warps) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withChests(ChestTable chests) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withInvites(InviteTable invites) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withMergeRequests(MergeTable mergeRequests) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withInbox(InboxTable inbox) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withEscrows(EscrowTable escrows) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withFactionNames(NameIndex factionNames) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withZones(ZoneStats zones) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }

    public KernelState withOnline(OnlineSet online) {
        return new KernelState(version, tick, config, factions, ledger, members, claims, warps,
                chests, invites, mergeRequests, inbox, escrows, factionNames, zones, online);
    }
}
