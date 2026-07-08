package dev.fablemc.factions.core.command.admin;

import java.util.List;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * {@code /fa help} — the admin command index (ref-commands-admin.md §2.11). Prints one help line per
 * registered admin command the sender may use (permission-filtered), reading the live root list the
 * {@code /fa} executor maintains.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread. <b>Mutability:</b>
 * a leaf node holding a reference to the executor's (append-only) root list.
 */
public final class CmdAdminHelp extends CommandNode {

    private static final MessageKey HEADER = MessageKey.of("admin.help-header");
    private static final MessageKey ENTRY = MessageKey.of("admin.help-entry");

    private final List<CommandNode> roots;

    /** Builds the help command over the executor's live root list. */
    public CmdAdminHelp(List<CommandNode> roots) {
        super("help");
        this.roots = roots;
        setCommandPath("/fa");
        setPermission("factions.admin");
        setDescription("Show the admin command list.");
    }

    @Override
    protected void perform(CommandContext ctx) {
        ctx.send(HEADER);
        for (CommandNode root : roots) {
            if (root.permission() == null || ctx.sender().hasPermission(root.permission())) {
                ctx.send(ENTRY, "/fa " + root.name(), root.description());
            }
        }
    }
}
