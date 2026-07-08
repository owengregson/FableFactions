package dev.fablemc.factions.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.state.KernelSnapshot;

/** Unit tests for {@link Completions} — snapshot-backed, prefix-filtered suggestion sources. */
final class CompletionsTest {

    private final KernelSnapshot snap = Fixture.snapshot();

    @Test
    void factionNamesMatchPrefixCaseInsensitively() {
        assertEquals(List.of("Wolves"), Completions.factionNames(snap, ""));
        assertEquals(List.of("Wolves"), Completions.factionNames(snap, "wo"));
        assertEquals(List.of("Wolves"), Completions.factionNames(snap, "WOL"));
        assertTrue(Completions.factionNames(snap, "z").isEmpty());
    }

    @Test
    void memberNamesListFactionMembersInOrdinalOrder() {
        assertEquals(List.of("Alpha", "Bravo", "Charlie"),
                Completions.memberNames(snap, Fixture.FACTION_HANDLE, ""));
        assertEquals(List.of("Bravo"), Completions.memberNames(snap, Fixture.FACTION_HANDLE, "b"));
        // The factionless outsider "Delta" is never listed as a member.
        assertTrue(Completions.memberNames(snap, Fixture.FACTION_HANDLE, "d").isEmpty());
    }

    @Test
    void memberNamesEmptyForUnknownFaction() {
        assertTrue(Completions.memberNames(snap, 0x7FFF, "").isEmpty());
    }

    @Test
    void warpNamesMatchPrefix() {
        assertEquals(List.of("base", "mine"), Completions.warpNames(snap, Fixture.FACTION_HANDLE, ""));
        assertEquals(List.of("mine"), Completions.warpNames(snap, Fixture.FACTION_HANDLE, "m"));
    }

    @Test
    void chestNamesMatchPrefix() {
        assertEquals(List.of("vault", "storage"), Completions.chestNames(snap, Fixture.FACTION_HANDLE, ""));
        assertEquals(List.of("vault"), Completions.chestNames(snap, Fixture.FACTION_HANDLE, "v"));
    }

    @Test
    void roleNamesMatchPrefix() {
        assertEquals(List.of("member", "officer", "owner"),
                Completions.roleNames(snap, Fixture.FACTION_HANDLE, ""));
        assertEquals(List.of("officer", "owner"), Completions.roleNames(snap, Fixture.FACTION_HANDLE, "o"));
    }

    @Test
    void matchingFiltersCandidateArray() {
        String[] candidates = {"on", "off", "once"};
        assertEquals(List.of("on", "once"), Completions.matching(candidates, "on"));
        assertEquals(List.of("on", "off", "once"), Completions.matching(candidates, ""));
        assertTrue(Completions.matching(candidates, "x").isEmpty());
    }
}
