package dev.fablemc.factions.probe;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * The FableFactions live-matrix self-test probe — a second, independent plugin jar that boots inside
 * real matrix servers to assert the loaded Multi-Release tier and cross-version behavior (Mental
 * tester pattern, its own jvmdg prefix {@code dev/fablemc/factions/probe/lib/jvmdg/}).
 *
 * <p>On enable it runs the boot-safety suite (plugin enabled, bytecode tier vs
 * {@code -Dfablefactions.probe.tier}, capabilities line printed) and the requested
 * {@code -Dfablefactions.probe.suites} smoke suites (claim/protect/power/chat), then writes the
 * nonce-tagged outcomes to {@code probe-results.txt} for the CI matrix to verify. The probe links no
 * FableFactions class at compile time (the mega jar provides them at runtime), so it touches them
 * only reflectively.
 *
 * <p>Owning thread(s): the Bukkit plugin-lifecycle thread only (suites run inline in {@code onEnable}
 * so no server scheduler is needed — Folia-safe). Mutability class: none.
 */
public final class FableFactionsProbe extends JavaPlugin {

    private static final String DEFAULT_SUITES = "all";

    @Override
    public void onEnable() {
        String nonce = property("nonce", "unknown");
        int tier = intProperty("tier");
        Set<String> suites = parseSuites(property("suites", DEFAULT_SUITES));

        ProbeReport report = new ProbeReport(nonce, getLogger());
        try {
            new ProbeSuites(this, report, tier).run(suites);
        } catch (RuntimeException | LinkageError failed) {
            report.add("probe.error", false, failed.toString());
        }
        report.flush();
        getLogger().info("FableFactionsProbe finished: allPassed=" + report.allPassed()
                + " nonce=" + nonce + " tier=" + tier);
        // Harness-driven runs set this so the lane terminates on its own once the
        // results are on disk; a manually installed probe never stops the server.
        if (Boolean.getBoolean("fablefactions.probe.shutdown")) {
            getLogger().info("FableFactionsProbe requesting server shutdown (-Dfablefactions.probe.shutdown=true)");
            try {
                getServer().shutdown();
            } catch (RuntimeException ex) {
                getLogger().warning("server shutdown request failed: " + ex);
            }
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("FableFactionsProbe disabled");
    }

    private static Set<String> parseSuites(String raw) {
        Set<String> suites = new HashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                suites.add(trimmed);
            }
        }
        if (suites.isEmpty()) {
            suites.add(DEFAULT_SUITES);
        }
        return suites;
    }

    /** Reads {@code fablefactions.probe.<key>}, falling back to {@code fable.probe.<key>} then {@code def}. */
    private static String property(String key, String def) {
        String primary = System.getProperty("fablefactions.probe." + key);
        if (primary != null) {
            return primary;
        }
        return System.getProperty("fable.probe." + key, def);
    }

    private static int intProperty(String key) {
        try {
            return Integer.parseInt(property(key, "-1").trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }
}
