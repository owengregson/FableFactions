package dev.fablemc.factions.probe;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The probe's boot-safety and headless smoke suites (work order W3f). The probe jar links no
 * FableFactions class at compile time (the mega jar provides them at runtime), so every FableFactions
 * touch here is reflective through the target plugin's own class loader.
 *
 * <p>Boot safety asserts the plugin enabled, the loaded bytecode tier matches the matrix-declared
 * {@code -Dfablefactions.probe.tier}, and the capabilities line is printed. The claim/protect/power/
 * chat smoke suites are deliberately lean (class-resolution + key-constant assertions) — the full
 * gameplay matrix comes later.
 *
 * <p>Owning thread(s): the plugin-lifecycle thread. Mutability class: stateless (results accumulate
 * in the passed {@link ProbeReport}).
 */
final class ProbeSuites {

    private static final String TARGET_PLUGIN = "FableFactions";
    private static final String SENTINEL_RESOURCE = "dev/fablemc/factions/core/boot/FableFactionsPlugin.class";
    private static final String CAPABILITIES = "dev.fablemc.factions.platform.probe.Capabilities";
    private static final String VERDICT = "dev.fablemc.factions.kernel.rules.Verdict";
    private static final String VERDICTS = "dev.fablemc.factions.kernel.rules.Verdicts";
    private static final String ACTION = "dev.fablemc.factions.kernel.rules.Action";
    private static final String POWER_MATH = "dev.fablemc.factions.kernel.rules.PowerMath";
    private static final String POWER_CONFIG = "dev.fablemc.factions.kernel.config.PowerConfig";
    private static final String TEXT_PORT = "dev.fablemc.factions.platform.text.TextPort";
    private static final String MESSAGES = "dev.fablemc.factions.core.text.Messages";

    private final JavaPlugin probe;
    private final ProbeReport report;
    private final int expectedTier;

    ProbeSuites(JavaPlugin probe, ProbeReport report, int expectedTier) {
        this.probe = probe;
        this.report = report;
        this.expectedTier = expectedTier;
    }

    /** Runs the boot suite (always) plus each requested smoke suite. */
    void run(Set<String> suites) {
        Plugin target = probe.getServer().getPluginManager().getPlugin(TARGET_PLUGIN);
        ClassLoader loader = target != null ? target.getClass().getClassLoader() : null;
        bootSafety(target, loader);
        if (wants(suites, "claim")) {
            claim(loader);
        }
        if (wants(suites, "protect")) {
            protect(loader);
        }
        if (wants(suites, "power")) {
            power(loader);
        }
        if (wants(suites, "chat")) {
            chat(loader);
        }
    }

    // ── boot safety ────────────────────────────────────────────────────────────────────────

    private void bootSafety(Plugin target, ClassLoader loader) {
        boolean enabled = target != null && target.isEnabled();
        report.add("boot.plugin-enabled", enabled,
                enabled ? "FableFactions enabled" : "FableFactions missing or not enabled");
        if (!enabled) {
            report.add("boot.bytecode-tier", false, "skipped — plugin not enabled");
            report.add("boot.capabilities", false, "skipped — plugin not enabled");
            return;
        }
        int observed = classMajor(loader, SENTINEL_RESOURCE);
        report.add("boot.bytecode-tier", observed == expectedTier,
                "expected=" + expectedTier + " observed=" + observed
                        + " java=" + System.getProperty("java.specification.version"));
        String capabilities = capabilities(loader);
        report.add("boot.capabilities", capabilities != null && !capabilities.isEmpty(),
                capabilities != null ? capabilities : "Capabilities.detect() unavailable");
    }

    private String capabilities(ClassLoader loader) {
        Class<?> caps = load(loader, CAPABILITIES);
        Object detected = staticCall(caps, "detect");
        Object described = instanceCall(detected, "describe");
        return described instanceof String s ? s : null;
    }

    // ── lean smoke suites ──────────────────────────────────────────────────────────────────

    private void claim(ClassLoader loader) {
        boolean command = commandRegistered("f");
        boolean verdicts = load(loader, VERDICTS) != null;
        boolean allowZero = staticInt(load(loader, VERDICT), "ALLOW") == 0;
        report.add("smoke.claim", command && verdicts && allowZero,
                "cmd/f=" + command + " Verdicts=" + verdicts + " ALLOW==0=" + allowZero);
    }

    private void protect(ClassLoader loader) {
        boolean verdicts = load(loader, VERDICTS) != null;
        boolean action = load(loader, ACTION) != null;
        boolean denyWilderness = staticInt(load(loader, VERDICT), "DENY_WILDERNESS") == 1;
        report.add("smoke.protect", verdicts && action && denyWilderness,
                "Verdicts=" + verdicts + " Action=" + action + " DENY_WILDERNESS==1=" + denyWilderness);
    }

    private void power(ClassLoader loader) {
        boolean math = load(loader, POWER_MATH) != null;
        boolean config = load(loader, POWER_CONFIG) != null;
        report.add("smoke.power", math && config, "PowerMath=" + math + " PowerConfig=" + config);
    }

    private void chat(ClassLoader loader) {
        boolean textPort = load(loader, TEXT_PORT) != null;
        boolean messages = load(loader, MESSAGES) != null;
        report.add("smoke.chat", textPort && messages, "TextPort=" + textPort + " Messages=" + messages);
    }

    // ── reflection helpers (into the target plugin's class loader) ───────────────────────────

    private boolean commandRegistered(String name) {
        Plugin target = probe.getServer().getPluginManager().getPlugin(TARGET_PLUGIN);
        return target instanceof JavaPlugin java && java.getCommand(name) != null;
    }

    private static Class<?> load(ClassLoader loader, String fqn) {
        if (loader == null) {
            return null;
        }
        try {
            return Class.forName(fqn, false, loader);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    private static Object staticCall(Class<?> owner, String method) {
        if (owner == null) {
            return null;
        }
        try {
            Method m = owner.getMethod(method);
            return m.invoke(null);
        } catch (ReflectiveOperationException | LinkageError failed) {
            return null;
        }
    }

    private static Object instanceCall(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (ReflectiveOperationException | LinkageError failed) {
            return null;
        }
    }

    private static int staticInt(Class<?> owner, String fieldName) {
        if (owner == null) {
            return Integer.MIN_VALUE;
        }
        try {
            Field field = owner.getField(fieldName);
            return field.getInt(null);
        } catch (ReflectiveOperationException | LinkageError failed) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Reads the class-file major version of {@code resource} as the target plugin's class loader
     * resolves it — on a multi-release jar under Java 9+ this returns the versioned (v61) entry, on
     * Java 8 the base (v52) entry, so it equals the tier the loader×JVM actually executes.
     */
    private static int classMajor(ClassLoader loader, String resource) {
        if (loader == null) {
            return -1;
        }
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return -1;
            }
            byte[] header = new byte[8];
            int read = 0;
            while (read < header.length) {
                int n = in.read(header, read, header.length - read);
                if (n < 0) {
                    return -1;
                }
                read += n;
            }
            return ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
        } catch (Exception failed) {
            return -1;
        }
    }

    private static boolean wants(Set<String> suites, String name) {
        return suites.contains("all") || suites.contains(name);
    }
}
