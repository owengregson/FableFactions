package dev.fablemc.factions.core.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.command.CommandTestHarness.Console;

/**
 * One dispatch assertion per {@code /f} tree root, driven through {@link FCommandExecutor} over the
 * {@link Fixture} snapshot with a recording console sender. Player-only mutation roots (member /
 * claim / travel / bank / power) prove they are registered and route through the pipeline by
 * rejecting the console <b>after</b> their permission passes; the console-safe read roots render.
 */
final class FCommandExecutorDispatchTest {

    private final FCommandExecutor executor = CommandTestHarness.executor();

    private Console run(Console console, String... args) {
        executor.onCommand(console, null, "f", args);
        return console;
    }

    @Test
    void unknownSubcommandRoutesToHelpHint() {
        Console console = run(new Console(), "notacommand");
        assertTrue(console.sawKey("general.unknown-subcommand-detailed"));
    }

    @Test
    void bareCommandFallsBackToHelpForConsole() {
        Console console = run(new Console());
        assertTrue(console.sawKey("help.title"));
    }

    @Test
    void memberMutationRootDispatchesThroughPipeline() {
        Console console = run(new Console().grant("factions.cmd.create"), "create", "NewFaction");
        assertTrue(console.sawKey("general.player-only"));
    }

    @Test
    void claimRootDispatchesThroughPipeline() {
        Console console = run(new Console().grant("factions.cmd.claim"), "claim");
        assertTrue(console.sawKey("general.player-only"));
    }

    @Test
    void travelRootDispatchesThroughPipeline() {
        Console console = run(new Console().grant("factions.cmd.warp"), "warp");
        assertTrue(console.sawKey("general.player-only"));
    }

    @Test
    void bankRootDispatchesThroughPipeline() {
        Console console = run(new Console().grant("factions.cmd.bank"), "bank");
        assertTrue(console.sawKey("general.player-only"));
    }

    @Test
    void powerRootDispatchesThroughPipeline() {
        Console console = run(new Console().grant("factions.cmd.power"), "power");
        assertTrue(console.sawKey("general.player-only"));
    }

    @Test
    void listRootRendersDirectory() {
        Console console = run(new Console().grant("factions.cmd.list"), "list");
        assertTrue(console.sawKey("custom.list.header"));
    }

    @Test
    void infoRootRendersFactionCard() {
        Console console = run(new Console(), "info", "Wolves");
        assertTrue(console.sawKey("info.header"));
    }

    @Test
    void aliasResolvesToTheSameNode() {
        // "i" is a registered alias of info; it must render the same card.
        Console console = run(new Console(), "i", "Wolves");
        assertTrue(console.sawKey("info.header"));
    }
}
