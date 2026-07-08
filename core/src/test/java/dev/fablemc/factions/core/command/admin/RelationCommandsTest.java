package dev.fablemc.factions.core.command.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.relation.CmdRelation;
import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.RelationIntent;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.vocab.Relation;

/** Dispatch test for the {@code /f relation} tree (ref-commands-admin.md §6). */
final class RelationCommandsTest {

    @Test
    void officerDeclaringEnemySubmitsDeclareRelation() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        CommandContext.Services services = W3cSupport.services(bus, snap, null, null);
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.OFFICER, "Bravo", "factions.cmd.relation");
        new CmdRelation().execute(W3cSupport.ctx(services, sender.asPlayer(), snap, "Foxes", "enemy"));

        List<Intent> intents = W3cSupport.drain(bus);
        assertEquals(1, intents.size(), sender.captured.toString());
        RelationIntent.DeclareRelation declare =
                assertInstanceOf(RelationIntent.DeclareRelation.class, intents.get(0));
        assertEquals(Relation.ENEMY, declare.kind());
        assertEquals(snap.state().factions().handleOf(W3cSupport.WOLVES_ORDINAL), declare.actorFaction());
        assertEquals(snap.state().factions().handleOf(W3cSupport.FOXES_ORDINAL), declare.targetFaction());
    }

    @Test
    void memberIsRejectedByOfficerGate() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        CommandContext.Services services = W3cSupport.services(bus, snap, null, null);
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.MEMBER, "Charlie", "factions.cmd.relation");
        new CmdRelation().execute(W3cSupport.ctx(services, sender.asPlayer(), snap, "Foxes", "enemy"));

        assertTrue(W3cSupport.drain(bus).isEmpty());
        assertTrue(sender.sole().startsWith("general.must-be-officer"), sender.sole());
    }

    @Test
    void listRoutesToChildAndRendersHeader() {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        CommandContext.Services services = W3cSupport.services(bus, snap, null, null);
        W3cSupport.Actor sender = W3cSupport.player(W3cSupport.OFFICER, "Bravo", "factions.cmd.relation");
        new CmdRelation().execute(W3cSupport.ctx(services, sender.asPlayer(), snap, "list"));
        assertTrue(sender.captured.get(0).startsWith("relations.list-header"), sender.captured.toString());
    }
}
