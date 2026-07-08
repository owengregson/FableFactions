package dev.fablemc.factions.core.command;

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

import dev.fablemc.factions.core.messages.MessageCatalog;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.platform.resolve.Worlds;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.sched.TaskHandle;

/**
 * Shared headless harness for the player command-tree dispatch tests: an echoing catalog, an inline
 * scheduler, a recording console {@link CommandSender}, and a fully-wired {@link FCommandExecutor}
 * over the shared {@link Fixture} snapshot — the fixture-snapshot pattern from the landed
 * {@code CommandNodeDispatchTest}.
 */
final class CommandTestHarness {

    private CommandTestHarness() {
    }

    static FCommandExecutor executor() {
        SnapshotHub hub = new SnapshotHub(Fixture.snapshot());
        Messages messages = new Messages(new EchoCatalog(), hub, new InlineScheduling());
        CommandContext.Services services = new CommandContext.Services(
                null, null, messages, new InlineScheduling(), null, null, null, null);
        return new FCommandExecutor(hub, services, new Worlds(), new StubVault(), List.of());
    }

    /** A no-op Vault wallet bridge (no economy provider). */
    static final class StubVault implements VaultBridge {
        @Override
        public boolean present() {
            return false;
        }

        @Override
        public boolean withdraw(Player player, double amount) {
            return false;
        }

        @Override
        public void deposit(Player player, double amount) {
        }
    }

    /** A catalog that echoes {@code key|arg,arg} so the rendered legacy string is assertable. */
    static final class EchoCatalog implements MessageCatalog {
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

    /** An inline scheduler that runs every task synchronously. */
    static final class InlineScheduling implements Scheduling {
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

    /** A recording console {@link CommandSender} that honours a granted-permission set. */
    static final class Console implements CommandSender {
        final List<String> captured = new ArrayList<>();
        private final Set<String> granted = new HashSet<>();

        Console grant(String node) {
            granted.add(node);
            return this;
        }

        boolean sawKey(String keyPrefix) {
            for (String message : captured) {
                if (message.startsWith(keyPrefix)) {
                    return true;
                }
            }
            return false;
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
            return "Console";
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
