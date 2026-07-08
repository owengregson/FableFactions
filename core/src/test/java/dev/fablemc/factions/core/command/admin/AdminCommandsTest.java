package dev.fablemc.factions.core.command.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.FaCommandExecutor;
import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.platform.resolve.Worlds;

/**
 * Dispatch test for the {@code /fa} admin tree via {@link FaCommandExecutor} (ref-commands-admin.md
 * §2): subcommand routing, per-command permission gating, and tab completion of the root names.
 */
final class AdminCommandsTest {

    private FaCommandExecutor executor(KernelSnapshot snap, IntentBus bus) {
        CommandContext.Services services = W3cSupport.services(bus, snap, null, null);
        return new FaCommandExecutor(services, new SnapshotHub(snap), new Worlds(), new W3cSupport.FakeQueries());
    }

    @Test
    void bypassRoutesAndSubmitsSetOverriding() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.OFFICER, "Bravo", "factions.admin");
        executor(snap, bus).onCommand(sender.asPlayer(), null, "fa", new String[] {"bypass"});

        List<Intent> intents = W3cSupport.drain(bus);
        assertEquals(1, intents.size(), sender.captured.toString());
        PrefIntent.SetOverriding overriding =
                assertInstanceOf(PrefIntent.SetOverriding.class, intents.get(0));
        assertTrue(overriding.on());
        assertEquals(W3cSupport.OFFICER, overriding.player());
    }

    @Test
    void disbandRoutesFromConsoleAndSubmitsByAdmin() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        W3cSupport.Actor sender = W3cSupport.console("factions.cmd.disband.other");
        executor(snap, bus).onCommand(sender.asSender(), null, "fa", new String[] {"disband", "Foxes"});

        List<Intent> intents = W3cSupport.drain(bus);
        assertEquals(1, intents.size(), sender.captured.toString());
        LifecycleIntent.DisbandFaction disband =
                assertInstanceOf(LifecycleIntent.DisbandFaction.class, intents.get(0));
        assertTrue(disband.byAdmin());
        assertEquals(snap.state().factions().handleOf(W3cSupport.FOXES_ORDINAL), disband.faction());
    }

    @Test
    void subcommandPermissionIsEnforced() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.OFFICER, "Bravo"); // lacks factions.admin
        executor(snap, bus).onCommand(sender.asPlayer(), null, "fa", new String[] {"bypass"});

        assertTrue(W3cSupport.drain(bus).isEmpty());
        assertTrue(sender.sole().startsWith("general.no-permission"), sender.sole());
    }

    @Test
    void tabCompleteListsPermittedSubcommands() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.OFFICER, "Bravo", "factions.admin");
        List<String> names = executor(snap, bus)
                .onTabComplete(sender.asPlayer(), null, "fa", new String[] {""});
        assertTrue(names.contains("bypass"), names.toString());
        assertTrue(names.contains("help"), names.toString());
    }
}
