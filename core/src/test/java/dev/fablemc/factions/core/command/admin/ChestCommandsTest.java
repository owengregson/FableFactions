package dev.fablemc.factions.core.command.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.chestcmd.CmdChest;
import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.kernel.intent.ChestIntent;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/** Dispatch test for the {@code /f chest} tree (ref-commands-misc.md §2). */
final class ChestCommandsTest {

    @Test
    void officerCreatingChestSubmitsCreateChest() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        CommandContext.Services services = W3cSupport.services(bus, snap, new W3cSupport.RecordingChests(), null);
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.OFFICER, "Bravo",
                "factions.cmd.chest", "factions.cmd.chest.create");
        new CmdChest().execute(W3cSupport.ctx(services, sender.asPlayer(), snap, "create", "vault2"));

        List<Intent> intents = W3cSupport.drain(bus);
        assertEquals(1, intents.size(), sender.captured.toString());
        ChestIntent.CreateChest create = assertInstanceOf(ChestIntent.CreateChest.class, intents.get(0));
        assertEquals("vault2", create.name());
    }

    @Test
    void openDelegatesToChestsSeam() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        W3cSupport.RecordingChests chests = new W3cSupport.RecordingChests();
        CommandContext.Services services = W3cSupport.services(bus, snap, chests, null);
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.MEMBER, "Charlie", "factions.cmd.chest");
        new CmdChest().execute(W3cSupport.ctx(services, sender.asPlayer(), snap, "open", "vault"));

        assertTrue(W3cSupport.drain(bus).isEmpty());
        assertEquals(List.of("vault"), chests.opened);
    }
}
