package dev.fablemc.factions.core.command.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.predefined.CmdPredefined;
import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/** Dispatch test for the {@code /f predefined} tree (ref-commands-misc.md §6). */
final class PredefinedCommandsTest {

    @Test
    void createIsRejectedWhenSubsystemDisabled() {
        // predefined.enabled defaults false → the enable gate fires before any create.
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        CommandContext.Services services = W3cSupport.services(bus, snap, null, null);
        W3cSupport.RecordingPredefined seam = new W3cSupport.RecordingPredefined();
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.OUTSIDER, "Delta",
                "factions.cmd.predefined", "factions.cmd.predefined.create");
        new CmdPredefined(seam).execute(W3cSupport.ctx(services, sender.asPlayer(), snap, "create", "Foo"));

        assertTrue(W3cSupport.drain(bus).isEmpty());
        assertTrue(sender.sole().startsWith("predefined.disabled"), sender.sole());
    }

    @Test
    void reloadInvokesSeamRegardlessOfEnableGate() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        CommandContext.Services services = W3cSupport.services(bus, snap, null, null);
        W3cSupport.RecordingPredefined seam = new W3cSupport.RecordingPredefined();
        W3cSupport.Actor sender = W3cSupport.console(
                "factions.cmd.predefined", "factions.cmd.predefined.reload");
        new CmdPredefined(seam).execute(W3cSupport.ctx(services, sender.asSender(), snap, "reload"));

        assertEquals(1, seam.reloads);
        assertTrue(sender.sole().startsWith("predefined.reload-success"), sender.sole());
    }
}
