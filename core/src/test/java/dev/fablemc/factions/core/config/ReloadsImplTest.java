package dev.fablemc.factions.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * End-to-end reload pin: {@link ReloadsImpl} extracts the bundled defaults, parses the shipped files
 * (validating {@code config.yml}/{@code roles.yml} against the kernel defaults + shipped overrides),
 * and submits exactly one {@code SwapConfig} carrying the parsed image.
 */
final class ReloadsImplTest {

    @Test
    void reloadExtractsParsesAndSubmitsSwapConfig(@TempDir File dataFolder) {
        ConfigFiles files = new ConfigFiles(dataFolder,
                name -> ReloadsImplTest.class.getResourceAsStream("/" + name));
        IntentBus bus = new IntentBus(() -> 0L, () -> 0, () -> { });
        ReloadsImpl reloads = new ReloadsImpl(files, bus, new InlineScheduling(), null,
                UnaryOperator.identity());

        List<String> issues = reloads.reload().toCompletableFuture().join();
        assertTrue(issues.isEmpty(), () -> "the shipped defaults must parse cleanly: " + issues);
        assertTrue(new File(dataFolder, ConfigFiles.FILE_CONFIG).isFile(), "config.yml was extracted");

        ConfigImage image = drainSwapConfig(bus);
        assertNotNull(image, "reload must submit a SwapConfig");
        // config.yml ships the kernel defaults …
        assertEquals(50, image.limits().maxMembers());
        assertEquals(10.0, image.power().perPlayerMax(), 0.0);
        // … while roles.yml ships values that override the code defaults.
        assertEquals(8, image.role().maxCustomRolesPerFaction());
        assertEquals("<gold>[Owner]</gold>", image.role().defaultOwnerPrefix());
    }

    private static ConfigImage drainSwapConfig(IntentBus bus) {
        List<IntentEnvelope> drained = new ArrayList<>();
        bus.drain(drained, 16);
        for (IntentEnvelope envelope : drained) {
            if (envelope.intent() instanceof SystemIntent.SwapConfig swap) {
                return swap.config();
            }
        }
        return null;
    }

    /** Runs {@link #runAsync} inline; every other surface is unused by the reload path. */
    private static final class InlineScheduling implements Scheduling {
        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public void runGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void runAt(Location location, Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void runOn(Entity entity, Runnable task, Runnable retired) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void runOnLater(Entity entity, long delayTicks, Runnable task, Runnable retired) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOwnedByCurrentRegion(Entity entity) {
            return true;
        }

        @Override
        public TaskHandle repeatGlobal(long initialTicks, long periodTicks, Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskHandle repeatOn(Entity entity, long initialTicks, long periodTicks,
                                   Runnable task, Runnable retired) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskHandle repeatAsync(Duration initial, Duration period, Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String describe() {
            return "inline-test";
        }
    }
}
