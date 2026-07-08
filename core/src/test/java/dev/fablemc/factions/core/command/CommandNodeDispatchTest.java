package dev.fablemc.factions.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import dev.fablemc.factions.core.messages.MessageCatalog;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * Unit tests for {@link CommandNode}'s dispatch pipeline (permission → player-only → child routing →
 * arg count → perform) and tab-completion, driven headlessly with a captured-message
 * {@link FakeSender}, an echoing {@link MessageCatalog}, and an inline {@link Scheduling}.
 */
final class CommandNodeDispatchTest {

    private final Messages messages = new Messages(new EchoCatalog(),
            new SnapshotHub(Fixture.snapshot()), new InlineScheduling());
    private final CommandContext.Services services =
            new CommandContext.Services(null, null, messages, null, null, null, null, null);

    private CommandContext ctx(CommandSender sender, String... args) {
        Player player = sender instanceof Player casted ? casted : null;
        return new CommandContext(services, sender, player, List.of(args), Fixture.snapshot());
    }

    @Test
    void permissionDeniedShortCircuits() {
        Recording node = new Recording("test").perm("factions.cmd.test");
        FakeSender sender = new FakeSender("Console");
        node.execute(ctx(sender, "a"));
        assertFalse(node.performed);
        assertEquals(1, sender.captured.size());
        assertTrue(sender.captured.get(0).startsWith("general.no-permission"));
    }

    @Test
    void permissionHeldRunsPerform() {
        Recording node = new Recording("test").perm("factions.cmd.test");
        FakeSender sender = new FakeSender("Alice").grant("factions.cmd.test");
        node.execute(ctx(sender));
        assertTrue(node.performed);
        assertTrue(sender.captured.isEmpty());
    }

    @Test
    void playerOnlyRejectsConsole() {
        Recording node = new Recording("test").playerOnly();
        FakeSender console = new FakeSender("Console");
        node.execute(ctx(console));
        assertFalse(node.performed);
        assertTrue(sole(console).startsWith("general.player-only"));
    }

    @Test
    void childRoutingRunsChildWithShiftedArgs() {
        Recording child = new Recording("sub");
        Recording parent = new Recording("group").withChild(child);
        FakeSender sender = new FakeSender("Alice");
        parent.execute(ctx(sender, "sub", "x"));
        assertTrue(child.performed);
        assertFalse(parent.performed);
        assertEquals(List.of("x"), child.performedArgs);
    }

    @Test
    void childPermissionIsReChecked() {
        Recording child = new Recording("sub").perm("factions.cmd.sub");
        Recording parent = new Recording("group").withChild(child);
        FakeSender sender = new FakeSender("Alice"); // lacks the child's node
        parent.execute(ctx(sender, "sub"));
        assertFalse(child.performed);
        assertFalse(parent.performed);
        assertTrue(sole(sender).startsWith("general.no-permission"));
    }

    @Test
    void unmatchedChildTokenFallsThroughToParentPerform() {
        Recording child = new Recording("sub");
        Recording parent = new Recording("group").withChild(child);
        FakeSender sender = new FakeSender("Alice");
        parent.execute(ctx(sender, "other"));
        assertFalse(child.performed);
        assertTrue(parent.performed);
    }

    @Test
    void tooFewArgsSendsUsage() {
        Recording node = new Recording("test").required("a", "b");
        FakeSender sender = new FakeSender("Alice");
        node.execute(ctx(sender, "onlyone"));
        assertFalse(node.performed);
        assertTrue(sole(sender).startsWith("general.invalid-args"));
    }

    @Test
    void enoughArgsRunsPerform() {
        Recording node = new Recording("test").required("a", "b");
        FakeSender sender = new FakeSender("Alice");
        node.execute(ctx(sender, "a", "b"));
        assertTrue(node.performed);
    }

    @Test
    void permissionIsCheckedBeforePlayerAndArgs() {
        Recording node = new Recording("test").perm("p").playerOnly().required("a");
        FakeSender console = new FakeSender("Console"); // fails perm AND player AND arg count
        node.execute(ctx(console));
        assertEquals(1, console.captured.size());
        assertTrue(console.captured.get(0).startsWith("general.no-permission"));
    }

    @Test
    void groupTabCompleteMergesPermittedChildNamesAndDynamicValues() {
        Recording alpha = new Recording("alpha");
        Recording beta = new Recording("beta").perm("factions.cmd.beta");
        CommandNode group = new CommandNode("g") {
            {
                addChild(alpha);
                addChild(beta);
            }

            @Override
            protected void perform(CommandContext ctx) {
            }

            @Override
            protected List<String> complete(CommandContext ctx, int argIndex) {
                return argIndex == 0 ? List.of("dynamic") : List.of();
            }
        };
        FakeSender sender = new FakeSender("Alice"); // lacks the beta node
        List<String> completions = group.tabComplete(ctx(sender, ""));
        assertTrue(completions.contains("alpha"));
        assertFalse(completions.contains("beta"));
        assertTrue(completions.contains("dynamic"));
    }

    @Test
    void leafTabCompleteFiltersByPrefix() {
        CommandNode leaf = new CommandNode("test") {
            @Override
            protected void perform(CommandContext ctx) {
            }

            @Override
            protected List<String> complete(CommandContext ctx, int argIndex) {
                return List.of("square", "circle", "fill");
            }
        };
        FakeSender sender = new FakeSender("Alice");
        assertEquals(List.of("square"), leaf.tabComplete(ctx(sender, "sq")));
        assertEquals(List.of("square", "circle", "fill"), leaf.tabComplete(ctx(sender, "")));
    }

    private static String sole(FakeSender sender) {
        assertEquals(1, sender.captured.size());
        return sender.captured.get(0);
    }

    // ── test doubles ───────────────────────────────────────────────────────────────────────

    /** A node that records whether {@link #perform} ran and the args it saw. */
    private static final class Recording extends CommandNode {
        boolean performed;
        List<String> performedArgs;

        Recording(String name) {
            super(name);
        }

        Recording perm(String node) {
            setPermission(node);
            return this;
        }

        Recording playerOnly() {
            setRequiresPlayer(true);
            return this;
        }

        Recording required(String... names) {
            setRequiredArgs(names);
            return this;
        }

        Recording withChild(CommandNode child) {
            addChild(child);
            return this;
        }

        @Override
        protected void perform(CommandContext ctx) {
            performed = true;
            performedArgs = ctx.args();
        }
    }

    /** A catalog that echoes {@code key|arg,arg} so the rendered legacy string is assertable. */
    private static final class EchoCatalog implements MessageCatalog {
        @Override
        public Component render(int localeIdx, MessageKey key, String... args) {
            return Component.text(key.key() + "|" + String.join(",", args));
        }

        @Override
        public int localeIndex(String bcp47Tag) {
            return 0;
        }

        @Override
        public int defaultLocale() {
            return 0;
        }
    }

    /** An inline scheduler (unused by {@code Messages.to}, present to satisfy construction). */
    private static final class InlineScheduling implements Scheduling {
        private static final TaskHandle NOOP_HANDLE = new TaskHandle() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean cancelled() {
                return true;
            }
        };

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
            return "inline-test";
        }
    }

    /** A {@link CommandSender} that records {@code sendMessage(String)} and honors a permission set. */
    private static final class FakeSender implements CommandSender {
        final List<String> captured = new ArrayList<>();
        private final String name;
        private final Set<String> granted = new HashSet<>();

        FakeSender(String name) {
            this.name = name;
        }

        FakeSender grant(String node) {
            granted.add(node);
            return this;
        }

        @Override
        public void sendMessage(String message) {
            captured.add(message);
        }

        @Override
        public void sendMessage(String[] messages) {
            Collections.addAll(captured, messages);
        }

        @Override
        public boolean hasPermission(String name) {
            return granted.contains(name);
        }

        @Override
        public boolean hasPermission(Permission perm) {
            return granted.contains(perm.getName());
        }

        @Override
        public boolean isPermissionSet(String name) {
            return granted.contains(name);
        }

        @Override
        public boolean isPermissionSet(Permission perm) {
            return granted.contains(perm.getName());
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Server getServer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Spigot spigot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeAttachment(PermissionAttachment attachment) {
        }

        @Override
        public void recalculatePermissions() {
        }

        @Override
        public Set<PermissionAttachmentInfo> getEffectivePermissions() {
            return Collections.emptySet();
        }

        @Override
        public boolean isOp() {
            return false;
        }

        @Override
        public void setOp(boolean value) {
        }
    }
}
