package dev.fablemc.factions.probe;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * W0 self-test probe stub — a second, independent plugin jar that boots inside real
 * matrix servers to assert the loaded Multi-Release tier and cross-version behavior
 * (Mental tester pattern, its own jvmdg prefix {@code dev/fablemc/factions/probe/lib/jvmdg/}).
 *
 * <p>Owning thread(s): the Bukkit main / plugin-lifecycle thread only.
 * Mutability class: none. Kept deliberately minimal in W0 — it touches no post-Java-8
 * API, so its downgrade is a no-op and the isolation gates verify it carries neither
 * FableFactions' own jvmdg runtime prefix nor an un-relocated jvmdg stub descriptor.
 */
public final class FableFactionsProbe extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("FableFactionsProbe enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("FableFactionsProbe disabled");
    }
}
