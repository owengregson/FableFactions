package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * {@code /f help [page]} (alias {@code ?}) — the paged command reference (ref-commands-core.md
 * §7.32). Open and console-safe; renders section headers and one {@code help.entry-line} per
 * command the sender may run, resolved against the live command registry.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread.
 * <b>Mutability:</b> holds the shared (immutable-after-boot) registry; stateless per invocation.
 */
final class CmdHelp extends CommandNode {

    private static final int TOTAL_PAGES = 3;
    private static final String[] PAGE_TOKENS = {"1", "2", "3"};

    private static final MessageKey TITLE = MessageKey.of("help.title");
    private static final MessageKey START_HERE = MessageKey.of("help.start-here");
    private static final MessageKey START_1 = MessageKey.of("help.start-step-1");
    private static final MessageKey START_2 = MessageKey.of("help.start-step-2");
    private static final MessageKey START_3 = MessageKey.of("help.start-step-3");
    private static final MessageKey START_4 = MessageKey.of("help.start-step-4");
    private static final MessageKey SEPARATOR = MessageKey.of("help.separator");
    private static final MessageKey ENTRY_LINE = MessageKey.of("help.entry-line");
    private static final MessageKey OFFICER_TITLE = MessageKey.of("help.officer-title");
    private static final MessageKey OFFICER_1 = MessageKey.of("help.officer-line-1");
    private static final MessageKey OFFICER_2 = MessageKey.of("help.officer-line-2");
    private static final MessageKey OFFICER_3 = MessageKey.of("help.officer-line-3");
    private static final MessageKey ADMIN_TITLE = MessageKey.of("help.admin-title");
    private static final MessageKey TIP_NOTIFY = MessageKey.of("help.tip-notify");

    private static final MessageKey SECTION_CORE = MessageKey.of("help.section.core");
    private static final MessageKey SECTION_SETUP = MessageKey.of("help.section.faction-setup");
    private static final MessageKey SECTION_MEMBERS = MessageKey.of("help.section.members-and-invites");
    private static final MessageKey SECTION_LAND = MessageKey.of("help.section.land-and-navigation");
    private static final MessageKey SECTION_ECONOMY = MessageKey.of("help.section.economy-and-utility");

    private static final String[] CORE = {"help", "info", "list", "map", "top", "gui", "language"};
    private static final String[] SETUP = {"create", "rename", "desc", "disband"};
    private static final String[] MEMBERS = {"invite", "join", "leave", "kick", "promote", "demote", "leader", "role"};
    private static final String[] LAND = {"claim", "unclaim", "home", "sethome", "unsethome", "warp", "fly"};
    private static final String[] ECONOMY = {"bank", "notify", "relation"};

    private final Map<String, CommandNode> registry;

    CmdHelp(Map<String, CommandNode> registry) {
        super("help", "?");
        setDescription("Show the command help");
        setOptionalArgs("page");
        this.registry = registry;
    }

    @Override
    protected void perform(CommandContext ctx) {
        int page = Math.min(TOTAL_PAGES, Math.max(1, ArgParsers.parsePage(ctx.arg(0))));
        ctx.send(TITLE);
        switch (page) {
            case 2 -> {
                section(ctx, SECTION_MEMBERS, MEMBERS);
                section(ctx, SECTION_LAND, LAND);
                section(ctx, SECTION_ECONOMY, ECONOMY);
            }
            case 3 -> {
                if (ctx.sender().hasPermission("factions.cmd.kick")) {
                    ctx.send(OFFICER_TITLE);
                    ctx.send(OFFICER_1);
                    ctx.send(OFFICER_2);
                    ctx.send(OFFICER_3);
                }
                if (ctx.sender().hasPermission("factions.admin")) {
                    ctx.send(ADMIN_TITLE);
                }
                ctx.send(TIP_NOTIFY);
            }
            default -> {
                ctx.send(START_HERE);
                ctx.send(START_1);
                ctx.send(START_2);
                ctx.send(START_3);
                ctx.send(START_4);
                ctx.send(SEPARATOR);
                section(ctx, SECTION_CORE, CORE);
                section(ctx, SECTION_SETUP, SETUP);
            }
        }
    }

    private void section(CommandContext ctx, MessageKey header, String[] names) {
        ctx.send(header);
        for (String name : names) {
            CommandNode node = registry.get(name);
            if (node == null) {
                continue;
            }
            if (node.permission() != null && !ctx.sender().hasPermission(node.permission())) {
                continue;
            }
            ctx.send(ENTRY_LINE, node.getUsage(), node.description());
        }
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0
                ? Completions.matching(PAGE_TOKENS, ctx.argOrEmpty(0).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
