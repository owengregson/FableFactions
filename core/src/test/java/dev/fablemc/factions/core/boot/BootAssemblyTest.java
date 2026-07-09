package dev.fablemc.factions.core.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.pipeline.FailureHandler;
import dev.fablemc.factions.core.pipeline.FeedbackRouter;
import dev.fablemc.factions.kernel.config.StorageConfigView;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * The headless boot-order smoke: {@link BootAssembly} constructs the whole write plane over an
 * in-memory HSQLDB and a fake {@link Scheduling} (no Bukkit server), then it is asserted that
 *
 * <ul>
 *   <li>snapshot v0 is published (and reported, B10);</li>
 *   <li>the {@code fable-kernel} writer takes one {@code CreateFaction} intent end to end — reduced
 *       into an effect, journaled to the WAL, and projected into a {@code factions} row;</li>
 *   <li>the ordered shutdown drains + flushes the projection and advances the checkpoint.</li>
 * </ul>
 *
 * This exercises the exact wiring graph {@code onEnable} builds, minus the Bukkit-only feature
 * reconciler (listeners/commands/GUI), so a boot-ordering regression is caught without a live server.
 */
final class BootAssemblyTest {

    private static final Logger QUIET = quiet();

    private static Logger quiet() {
        Logger l = Logger.getAnonymousLogger();
        l.setLevel(Level.OFF);
        return l;
    }

    @Test
    void bootProcessesIntentEndToEndAndShutsDownOrdered() throws Exception {
        Path tmp = Files.createTempDirectory("ff-boot-");
        File dataFolder = tmp.toFile();
        String mem = "boot_" + UUID.randomUUID().toString().replace('-', '_');

        // A keep-alive connection pins the in-memory DB for the whole test so the test's own queries
        // see the same database the boot pool + projector wrote (named mem DB, AM-10).
        JDBCDataSource probe = new JDBCDataSource();
        probe.setUrl("jdbc:hsqldb:mem:" + mem + ";sql.syntax_mys=true");
        probe.setUser("SA");
        probe.setPassword("");
        try (Connection pin = probe.getConnection()) {
            StorageConfigView memView = new StorageConfigView(
                    "hsqldb", "mem:" + mem, "localhost", 3306, "factions", "root", 1, false);
            List<String> report = new ArrayList<>();
            Function<String, InputStream> resources = name -> BootAssemblyTest.class.getResourceAsStream("/" + name);

            BootAssembly.Deps deps = new BootAssembly.Deps(
                    QUIET, dataFolder, resources,
                    () -> 1_000L, () -> 0, new FakeScheduling(),
                    name -> "world".equals(name) ? 0 : -1, idx -> idx == 0 ? "world" : null,
                    new UUID(9, 9), FailureHandler.IGNORE, report::add,
                    FeedbackRouter.NOOP, memView, "", null);   // configBaker null → headless (empty bitsets)

            BootAssembly boot = new BootAssembly(deps);

            // snapshot v0 published + reported.
            assertNotNull(boot.snapshots().current(), "snapshot v0 present");
            assertTrue(report.stream().anyMatch(l -> l.contains("snapshot") && l.contains("v0 published")),
                    "boot report records the snapshot v0 publish");
            assertTrue(report.stream().anyMatch(l -> l.contains("advisory lock acquired")),
                    "boot report records the AM-11 lock acquisition");
            assertEquals(-1L, boot.journalSeq(), "fresh DB → last committed seq is -1 before any intent");

            boot.start();

            // one CreateFaction intent, end to end.
            UUID owner = new UUID(1, 2);
            boot.bus().submit(new LifecycleIntent.CreateFaction("Alpha", owner), Origin.player(owner));
            awaitTrue(() -> boot.snapshots().current().factionByName(NameIndex.fold("Alpha")) != null, 5_000,
                    "writer reduced CreateFaction into the published snapshot");

            // ordered shutdown drains the writer, fsyncs the WAL, and flushes + checkpoints the projection.
            boot.shutdown(() -> { });
            assertTrue(report.stream().anyMatch(l -> l.contains("ordered disable complete")),
                    "boot report records the ordered disable");

            // effect journaled: a WAL segment exists and is non-empty (deterministic post-shutdown).
            Path journalDir = tmp.resolve("data/journal");
            assertTrue(Files.isDirectory(journalDir), "journal directory created");
            long journalBytes = 0;
            try (var stream = Files.newDirectoryStream(journalDir, "seg-*.fj")) {
                for (Path seg : stream) {
                    journalBytes += Files.size(seg);
                }
            }
            assertTrue(journalBytes > 0, "the FactionCreated effect was appended to the WAL");

            try (Connection c = probe.getConnection();
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT `name` FROM `factions` WHERE `name_folded`='alpha'")) {
                assertTrue(rs.next(), "CreateFaction projected a factions row after the shutdown flush");
                assertEquals("Alpha", rs.getString(1));
            }
            try (Connection c = probe.getConnection();
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT `journal_seq` FROM `ff_meta` WHERE `id`=0")) {
                assertTrue(rs.next());
                assertTrue(rs.getLong(1) >= 0L, "the projection checkpoint advanced past the empty -1");
            }
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier cond, long timeoutMillis, String what)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    /** A no-Bukkit scheduler: global/async work runs inline; entity/repeat work is inert. */
    private static final class FakeScheduling implements Scheduling {
        @Override
        public void runGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void runAt(Location location, Runnable task) {
            task.run();
        }

        @Override
        public void runOn(Entity entity, Runnable task, Runnable retired) {
            task.run();
        }

        @Override
        public void runOnLater(Entity entity, long delayTicks, Runnable task, Runnable retired) {
            task.run();
        }

        @Override
        public boolean isOwnedByCurrentRegion(Entity entity) {
            return true;
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public TaskHandle repeatGlobal(long initialTicks, long periodTicks, Runnable task) {
            return NOOP_HANDLE;
        }

        @Override
        public TaskHandle repeatOn(Entity entity, long initialTicks, long periodTicks, Runnable task,
                                   Runnable retired) {
            return NOOP_HANDLE;
        }

        @Override
        public TaskHandle repeatAsync(Duration initial, Duration period, Runnable task) {
            return NOOP_HANDLE;
        }

        @Override
        public String describe() {
            return "fake";
        }

        private static final TaskHandle NOOP_HANDLE = new TaskHandle() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }
}
