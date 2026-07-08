package dev.fablemc.factions.core.command.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.flagcmd.CmdFlag;
import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/** Dispatch test for the {@code /f flag} tree (ref-commands-admin.md §4). */
final class FlagCommandsTest {

    private static final String[] PERMS = {"factions.cmd.flag", "factions.cmd.flag.set"};

    @Test
    void officerSettingPvpOffSubmitsSetFactionFlag() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        CommandContext.Services services = W3cSupport.services(bus, snap, null, null);
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.OFFICER, "Bravo", PERMS);
        new CmdFlag().execute(W3cSupport.ctx(services, sender.asPlayer(), snap, "set", "pvp", "off"));

        List<Intent> intents = W3cSupport.drain(bus);
        assertEquals(1, intents.size(), sender.captured.toString());
        PrefIntent.SetFactionFlag flag =
                assertInstanceOf(PrefIntent.SetFactionFlag.class, intents.get(0));
        assertEquals(Faction.FLAG_PVP, flag.flag());
        assertFalse(flag.value());
        assertFalse(flag.byAdmin());
    }

    @Test
    void bareFlagRendersList() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        CommandContext.Services services = W3cSupport.services(bus, snap, null, null);
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.OFFICER, "Bravo", PERMS);
        new CmdFlag().execute(W3cSupport.ctx(services, sender.asPlayer(), snap));
        assertTrue(sender.captured.get(0).startsWith("flag.list-header"), sender.captured.toString());
    }
}
