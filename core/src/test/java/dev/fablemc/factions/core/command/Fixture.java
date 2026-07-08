package dev.fablemc.factions.core.command;

import java.util.UUID;

import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.ChestTable;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.MemberDirectory;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.kernel.state.Warp;
import dev.fablemc.factions.kernel.state.WarpTable;

/**
 * Shared fixture snapshot for the command-layer tests — one normal faction "Wolves" (ordinal 2)
 * with an owner, an officer, a member, and a factionless outsider, plus two warps, two chests and
 * the three built-in ranks. Built from the kernel's public COW builders (the reducer-test pattern).
 *
 * <p><b>Owning thread(s):</b> the JUnit worker (single-threaded). <b>Mutability:</b> test-confined;
 * the produced snapshot is immutable.
 */
final class Fixture {

    private Fixture() {
    }

    static final int FACTION_ORDINAL = FactionHandle.FIRST_NORMAL_ORDINAL; // 2
    static final int FACTION_HANDLE = FactionHandle.handle(0, FACTION_ORDINAL);

    static final UUID OWNER = new UUID(0L, 100L);   // rank owner (idx 2), name "Alpha"
    static final UUID OFFICER = new UUID(0L, 101L);  // rank officer (idx 1), name "Bravo"
    static final UUID MEMBER = new UUID(0L, 102L);   // rank member (idx 0), name "Charlie"
    static final UUID OUTSIDER = new UUID(0L, 200L);  // factionless, name "Delta"

    static KernelSnapshot snapshot() {
        Rank[] ranks = {
                new Rank("member", "member", null, Rank.PRIORITY_MEMBER),   // idx 0
                new Rank("officer", "officer", null, Rank.PRIORITY_OFFICER), // idx 1
                new Rank("owner", "owner", null, Rank.PRIORITY_OWNER),       // idx 2
        };
        int ord = FACTION_ORDINAL;
        Faction faction = new Faction(ord, new UUID(1L, ord), "Wolves", NameIndex.fold("Wolves"),
                OWNER, "", "", 0L, 0.0, 0.0, 0L,
                RelationEdges.NO_ORDINALS, RelationEdges.NO_KINDS,
                RelationEdges.NO_ORDINALS, RelationEdges.NO_KINDS, null,
                Faction.NO_SHIELD, 0, false, ranks, 0, 0.0, 0L, FactionClaimList.empty(),
                "Wolves", "Wolves");
        FactionArena arena = FactionArena.empty().withFaction(ord, faction);
        int handle = arena.handleOf(ord);

        NameIndex names = NameIndex.empty().with(faction.nameFolded(), ord);

        PlayerLedger ledger = PlayerLedger.empty();
        MemberDirectory directory = MemberDirectory.empty();
        int o0 = ledger.nextOrdinal();
        ledger = ledger.withNewMember(o0, OWNER, "Alpha").withFactionHandle(o0, handle).withRankIdx(o0, 2);
        directory = directory.withMapping(OWNER, o0);
        int o1 = ledger.nextOrdinal();
        ledger = ledger.withNewMember(o1, OFFICER, "Bravo").withFactionHandle(o1, handle).withRankIdx(o1, 1);
        directory = directory.withMapping(OFFICER, o1);
        int o2 = ledger.nextOrdinal();
        ledger = ledger.withNewMember(o2, MEMBER, "Charlie").withFactionHandle(o2, handle).withRankIdx(o2, 0);
        directory = directory.withMapping(MEMBER, o2);
        int o3 = ledger.nextOrdinal();
        ledger = ledger.withNewMember(o3, OUTSIDER, "Delta"); // stays wilderness, rank 0
        directory = directory.withMapping(OUTSIDER, o3);

        WarpTable warps = WarpTable.empty()
                .set(ord, new Warp("base", 0, 0.0, 64.0, 0.0, 0f, 0f, OWNER, 0L, null, 0.0))
                .set(ord, new Warp("mine", 0, 10.0, 64.0, 10.0, 0f, 0f, OWNER, 0L, null, 0.0));

        ChestTable chests = ChestTable.empty()
                .set(ord, new ChestRef("vault", ChestRef.EMPTY_BLOB, 0L))
                .set(ord, new ChestRef("storage", ChestRef.EMPTY_BLOB, 0L));

        KernelState state = KernelState.empty()
                .withFactions(arena)
                .withFactionNames(names)
                .withLedger(ledger)
                .withMembers(directory)
                .withWarps(warps)
                .withChests(chests);
        return new KernelSnapshot(state);
    }
}
