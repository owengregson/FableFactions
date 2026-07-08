package dev.fablemc.factions.core.command.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.role.CmdRole;
import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * Dispatch + role-gate matrix tests for the {@code /f role} tree (ref-commands-admin.md §5).
 * Verifies group→child routing renders the list and that the officer/owner/member/factionless rank
 * gate produces the right rejection or advances past the rank check.
 */
final class RoleCommandsTest {

    private static final String[] ROLE_PERMS = {
            "factions.cmd.role", "factions.cmd.role.list", "factions.cmd.role.create",
    };

    private String run(UUID actor, String... args) {
        KernelSnapshot snap = W3cSupport.snapshot();
        IntentBus bus = W3cSupport.newBus();
        CommandContext.Services services = W3cSupport.services(bus, snap, null, null);
        W3cSupport.Actor sender = W3cSupport.player(actor, "T", ROLE_PERMS);
        new CmdRole().execute(W3cSupport.ctx(services, sender.asPlayer(), snap, args));
        return sender.captured.isEmpty() ? "" : sender.captured.get(0);
    }

    @Test
    void listRoutesToChildAndRendersHeader() {
        String first = run(W3cSupport.OFFICER, "list");
        assertTrue(first.startsWith("custom.role.list-header"), first);
    }

    @Test
    void factionlessActorIsRejectedByFactionGate() {
        assertTrue(run(W3cSupport.OUTSIDER, "create", "elite", "20").startsWith("general.not-in-faction"));
    }

    @Test
    void memberIsRejectedByOfficerGate() {
        assertTrue(run(W3cSupport.MEMBER, "create", "elite", "20").startsWith("general.must-be-officer"));
    }

    @Test
    void officerPassesRankGateAndHitsFeatureGate() {
        // roles.overrides.enabled defaults false, so the rank gate passing surfaces the feature gate.
        assertTrue(run(W3cSupport.OFFICER, "create", "elite", "20").startsWith("custom.role.create-disabled"));
    }

    @Test
    void ownerAlsoPassesRankGate() {
        assertTrue(run(W3cSupport.OWNER, "create", "elite", "20").startsWith("custom.role.create-disabled"));
    }
}
