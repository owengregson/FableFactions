package dev.fablemc.factions.core.command.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.merge.CmdMerge;
import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/** Dispatch test for the {@code /f merge} tree (ref-commands-misc.md §4). */
final class MergeCommandsTest {

    @Test
    void sendIsRejectedWhenMergeDisabled() {
        // factions.merge.enabled defaults false → routing to `send` still gates on the feature.
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        CommandContext.Services services = W3cSupport.services(bus, snap, null, null);
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.OFFICER, "Bravo", "factions.cmd.merge");
        new CmdMerge().execute(W3cSupport.ctx(services, sender.asPlayer(), snap, "send", "Foxes"));

        assertTrue(W3cSupport.drain(bus).isEmpty());
        assertTrue(sender.sole().startsWith("merge.disabled"), sender.sole());
    }

    @Test
    void bareMergePrintsHelp() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        CommandContext.Services services = W3cSupport.services(bus, snap, null, null);
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.OFFICER, "Bravo", "factions.cmd.merge");
        new CmdMerge().execute(W3cSupport.ctx(services, sender.asPlayer(), snap));

        assertEquals(3, sender.captured.size(), sender.captured.toString());
        assertTrue(sender.captured.get(0).startsWith("custom.merge.help-title"));
    }
}
