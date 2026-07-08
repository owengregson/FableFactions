package dev.fablemc.factions.core.boot;

import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * W0 boot stub — the plugin entry point Bukkit instantiates.
 *
 * <p>Owning thread(s): the Bukkit main / plugin-lifecycle thread only.
 * Mutability class: none (no fields; Wave 4 rewrites this to construct and own the
 * kernel pipeline, storage projector, listeners and command trees).
 *
 * <p>This is also the build's {@code verifyDowngrade} SENTINEL
 * ({@code dev/fablemc/factions/core/boot/FableFactionsPlugin}). The gate asserts it is
 * class-file major 52 in the mega jar's base tree AND major 61 under
 * {@code META-INF/versions/17}. To guarantee the class actually forks into the modern
 * overlay, {@link #onEnable()} touches {@link java.util.List#of} — a Java-9+ API that
 * JVMDowngrader shims in the base tree while keeping the original call under
 * {@code versions/17}, so the class legitimately exists in both tiers.
 */
public final class FableFactionsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // List.of is post-Java-8: jvmdg rewrites this call to a shim in the v52 base
        // tree and preserves the original under versions/17, forking the sentinel.
        List<String> banner = List.of("FableFactions", getDescription().getVersion(), "enabled");
        getLogger().info(String.join(" ", banner));
    }

    @Override
    public void onDisable() {
        getLogger().info("FableFactions disabled");
    }
}
