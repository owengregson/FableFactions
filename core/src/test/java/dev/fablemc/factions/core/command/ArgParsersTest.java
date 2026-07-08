package dev.fablemc.factions.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.command.ArgParsers.ParsedOptions;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.vocab.Relation;

/** Unit tests for {@link ArgParsers} — numeric / money / flag / relation / faction / option parsing. */
final class ArgParsersTest {

    @Test
    void parseIntHandlesValidAndInvalid() {
        assertEquals(5, ArgParsers.parseInt("5").orElse(-1));
        assertEquals(-3, ArgParsers.parseInt(" -3 ").orElse(0));
        assertTrue(ArgParsers.parseInt("x").isEmpty());
        assertTrue(ArgParsers.parseInt(null).isEmpty());
        assertTrue(ArgParsers.parseInt("").isEmpty());
        assertEquals(7, ArgParsers.parseInt("nope", 7));
        assertEquals(9, ArgParsers.parseInt("9", 7));
    }

    @Test
    void parsePageClampsToAtLeastOne() {
        assertEquals(1, ArgParsers.parsePage(null));
        assertEquals(1, ArgParsers.parsePage(""));
        assertEquals(1, ArgParsers.parsePage("bogus"));
        assertEquals(1, ArgParsers.parsePage("0"));
        assertEquals(1, ArgParsers.parsePage("-4"));
        assertEquals(3, ArgParsers.parsePage("3"));
    }

    @Test
    void parseDoubleHandlesValidAndInvalid() {
        assertEquals(2.5, ArgParsers.parseDouble("2.5").orElse(0.0));
        assertTrue(ArgParsers.parseDouble("abc").isEmpty());
        assertTrue(ArgParsers.parseDouble(null).isEmpty());
    }

    @Test
    void parseMoneyUsesKernelMagnitudeSuffixes() {
        assertEquals(10_000.0, ArgParsers.parseMoney("10k"));
        assertEquals(1_000_000.0, ArgParsers.parseMoney("1m"));
        assertEquals(50.0, ArgParsers.parseMoney("50"));
        assertTrue(ArgParsers.isValidAmount(ArgParsers.parseMoney("50")));
        assertFalse(ArgParsers.isValidAmount(ArgParsers.parseMoney("nope")));
        assertFalse(ArgParsers.isValidAmount(ArgParsers.parseMoney(null)));
    }

    @Test
    void parseOnOffMapsTokens() {
        assertEquals(Boolean.TRUE, ArgParsers.parseOnOff("on"));
        assertEquals(Boolean.TRUE, ArgParsers.parseOnOff("ON"));
        assertEquals(Boolean.FALSE, ArgParsers.parseOnOff("off"));
        assertNull(ArgParsers.parseOnOff("toggle"));
        assertNull(ArgParsers.parseOnOff(null));
    }

    @Test
    void parseRelationMapsNamesCaseInsensitively() {
        assertEquals(Relation.ALLY, ArgParsers.parseRelation("ally"));
        assertEquals(Relation.ENEMY, ArgParsers.parseRelation("ENEMY"));
        assertEquals(Relation.MEMBER, ArgParsers.parseRelation("member"));
        assertNull(ArgParsers.parseRelation("frenemy"));
        assertNull(ArgParsers.parseRelation(null));
    }

    @Test
    void factionByNameResolvesFoldedName() {
        KernelSnapshot snap = Fixture.snapshot();
        Faction wolves = ArgParsers.factionByName(snap, "Wolves");
        assertNotNull(wolves);
        assertEquals("Wolves", wolves.name());
        assertEquals(wolves.idx(), ArgParsers.factionByName(snap, "WOLVES").idx());
        assertEquals(wolves.idx(), ArgParsers.factionByName(snap, "wolves").idx());
        assertNull(ArgParsers.factionByName(snap, "Sheep"));
        assertNull(ArgParsers.factionByName(snap, null));
    }

    @Test
    void parseOptionsSplitsPositionalsAndEqualsForm() {
        ParsedOptions parsed = ArgParsers.parseOptions(List.of("2", "--action=claim"), "action");
        assertTrue(parsed.ok());
        assertEquals(List.of("2"), parsed.positionals());
        assertEquals("claim", parsed.option("action"));
        assertNull(parsed.option("missing"));
    }

    @Test
    void parseOptionsSupportsSpaceForm() {
        ParsedOptions parsed = ArgParsers.parseOptions(List.of("--size", "5", "keep"), "size");
        assertTrue(parsed.ok());
        assertEquals(List.of("keep"), parsed.positionals());
        assertEquals("5", parsed.option("size"));
    }

    @Test
    void parseOptionsRejectsUnknownAndMissingValue() {
        ParsedOptions unknown = ArgParsers.parseOptions(List.of("--bogus=1"), "action");
        assertFalse(unknown.ok());
        assertEquals("--bogus", unknown.badOption());

        ParsedOptions missing = ArgParsers.parseOptions(List.of("--action"), "action");
        assertFalse(missing.ok());
        assertEquals("--action", missing.badOption());
    }

    @Test
    void parseOptionsWithoutKnownSetAcceptsAnyEqualsOption() {
        ParsedOptions parsed = ArgParsers.parseOptions(List.of("a", "--foo=bar", "b"));
        assertTrue(parsed.ok());
        assertEquals(List.of("a", "b"), parsed.positionals());
        assertEquals("bar", parsed.option("foo"));
    }
}
