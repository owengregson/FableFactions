package dev.fablemc.factions.core.command.admin;

import java.util.List;
import java.util.logging.Level;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * {@code /fa reload} — reparse every configuration file and swap the new image into the kernel
 * (ref-commands-admin.md §2.7). The command never parses YAML on the server thread: it calls the
 * {@link dev.fablemc.factions.core.config.Reloads} seam (which parses off-thread and submits a
 * {@code SwapConfig} intent) and, when the returned stage completes, reports success or a warning
 * summary to the sender, logging the individual parse issues.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread; the stage
 * completion hops back to the sender's thread via {@link CommandKit#deliver}. <b>Mutability:</b> a
 * leaf node configured once at construction.
 */
public final class CmdAdminReload extends CommandNode {

    private static final MessageKey RELOADED = MessageKey.of("admin.reload");
    private static final MessageKey RELOAD_WARNINGS = MessageKey.of("admin.reload-warnings");

    /** Builds the reload command. */
    public CmdAdminReload() {
        super("reload");
        setCommandPath("/fa");
        setPermission("factions.admin");
        setDescription("Reload the configuration.");
    }

    @Override
    protected void perform(CommandContext ctx) {
        ctx.services().reloads().reload().whenComplete((issues, error) ->
                CommandKit.deliver(ctx, () -> report(ctx, issues, error)));
    }

    private void report(CommandContext ctx, List<String> issues, Throwable error) {
        if (error != null) {
            log(ctx, "Configuration reload failed", error);
            ctx.send(RELOAD_WARNINGS, "1");
            return;
        }
        if (issues == null || issues.isEmpty()) {
            ctx.send(RELOADED);
            return;
        }
        for (String issue : issues) {
            log(ctx, "Config reload issue: " + issue, null);
        }
        ctx.send(RELOAD_WARNINGS, Integer.toString(issues.size()));
    }

    private static void log(CommandContext ctx, String message, Throwable error) {
        if (ctx.services().plugin() == null) {
            return;
        }
        if (error != null) {
            ctx.services().plugin().getLogger().log(Level.WARNING, message, error);
        } else {
            ctx.services().plugin().getLogger().warning(message);
        }
    }
}
