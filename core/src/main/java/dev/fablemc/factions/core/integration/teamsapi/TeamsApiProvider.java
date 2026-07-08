package dev.fablemc.factions.core.integration.teamsapi;

import java.util.logging.Logger;

import dev.fablemc.factions.core.integration.Reflect;

/**
 * The TeamsAPI provider surface (proposal-C §10.3). TeamsAPI is an in/out integration: it registers
 * FableFactions as a team provider (view + intent-request bridge) and mirrors role changes. Full
 * provider registration wraps the typed {@code com.skyblockexp.teamsapi} service classes and is
 * loaded by FQN only when present.
 *
 * <p><b>Isolation note (W5):</b> this wave is reflection-only with no new compile deps, so this hook
 * ships as the presence probe + wiring anchor; the reflective provider registration (api views,
 * request bridge, {@code RoleChangeNotifierHolder}, and the optional chest/relation/notification/
 * power-history services) lands with the typed provider classes in W5. Absence is always graceful.
 *
 * <p><b>Owning thread(s):</b> the boot thread. <b>Mutability:</b> immutable (holds the active flag).
 */
public final class TeamsApiProvider {

    private final boolean active;

    private TeamsApiProvider(boolean active) {
        this.active = active;
    }

    /** {@code true} when TeamsAPI is present and the provider anchor is live. */
    public boolean active() {
        return active;
    }

    /** Detects TeamsAPI by plugin presence; logs the outcome. Never throws. */
    public static TeamsApiProvider create(Logger logger) {
        if (!Reflect.pluginPresent("TeamsAPI")) {
            return new TeamsApiProvider(false);
        }
        logger.info("TeamsAPI present — provider anchor ready (typed provider registration lands in W5).");
        return new TeamsApiProvider(true);
    }
}
