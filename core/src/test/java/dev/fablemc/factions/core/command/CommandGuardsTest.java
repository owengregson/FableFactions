package dev.fablemc.factions.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.Rank;

/** Unit tests for {@link CommandGuards} — snapshot-based faction / rank gates returning a reason or null. */
final class CommandGuardsTest {

    private final KernelSnapshot snap = Fixture.snapshot();

    @Test
    void requireFactionPassesForMembersFailsForOutsiders() {
        assertNull(CommandGuards.requireFaction(snap, Fixture.MEMBER));
        assertNull(CommandGuards.requireFaction(snap, Fixture.OWNER));
        assertEquals(ReasonCode.NOT_IN_FACTION, CommandGuards.requireFaction(snap, Fixture.OUTSIDER));
    }

    @Test
    void requireOwnerOnlyPassesForOwner() {
        assertNull(CommandGuards.requireOwner(snap, Fixture.OWNER));
        assertEquals(ReasonCode.MUST_BE_LEADER, CommandGuards.requireOwner(snap, Fixture.OFFICER));
        assertEquals(ReasonCode.MUST_BE_LEADER, CommandGuards.requireOwner(snap, Fixture.MEMBER));
        assertEquals(ReasonCode.NOT_IN_FACTION, CommandGuards.requireOwner(snap, Fixture.OUTSIDER));
    }

    @Test
    void requireOfficerOrAbovePassesForOfficerAndOwner() {
        assertNull(CommandGuards.requireOfficerOrAbove(snap, Fixture.OWNER));
        assertNull(CommandGuards.requireOfficerOrAbove(snap, Fixture.OFFICER));
        assertEquals(ReasonCode.MUST_BE_OFFICER, CommandGuards.requireOfficerOrAbove(snap, Fixture.MEMBER));
        assertEquals(ReasonCode.NOT_IN_FACTION, CommandGuards.requireOfficerOrAbove(snap, Fixture.OUTSIDER));
    }

    @Test
    void requireRankAtLeastComparesPriority() {
        assertNull(CommandGuards.requireRankAtLeast(snap, Fixture.MEMBER, Rank.PRIORITY_MEMBER));
        assertEquals(ReasonCode.MUST_BE_OFFICER,
                CommandGuards.requireRankAtLeast(snap, Fixture.MEMBER, Rank.PRIORITY_OFFICER));
        assertEquals(ReasonCode.MUST_BE_LEADER,
                CommandGuards.requireRankAtLeast(snap, Fixture.OFFICER, Rank.PRIORITY_OWNER));
        assertNull(CommandGuards.requireRankAtLeast(snap, Fixture.OWNER, Rank.PRIORITY_OWNER));
        assertEquals(ReasonCode.NOT_IN_FACTION,
                CommandGuards.requireRankAtLeast(snap, Fixture.OUTSIDER, Rank.PRIORITY_MEMBER));
    }

    @Test
    void resolutionHelpersReturnFactionAndRank() {
        assertNotNull(CommandGuards.factionOf(snap, Fixture.OWNER));
        assertNull(CommandGuards.factionOf(snap, Fixture.OUTSIDER));
        assertEquals(Rank.PRIORITY_OWNER, CommandGuards.rankOf(snap, Fixture.OWNER).priority());
        assertEquals(Rank.PRIORITY_MEMBER, CommandGuards.rankOf(snap, Fixture.MEMBER).priority());
        assertNull(CommandGuards.rankOf(snap, Fixture.OUTSIDER));
    }
}
