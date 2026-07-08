package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.Map;

import dev.fablemc.factions.core.command.CommandNode;

/**
 * Builds the player-facing member / directory / utility command nodes for the {@code /f} tree
 * (ref-commands-core.md §7): create, disband, rename, desc, motd, leave, kick, promote, demote,
 * leader, join, invite (+children), info, list, top, language, notify, gui — plus the paged
 * {@code /f help} which is created separately since it needs the fully-populated registry.
 *
 * <p><b>Owning thread(s):</b> {@link #create()} runs once at boot (Wave 4 wiring); the returned
 * nodes are effectively immutable and dispatched on the sender's region/main thread.
 * <b>Mutability:</b> stateless factory.
 */
public final class MemberCommands {

    private MemberCommands() {
    }

    /** The member / directory / utility top-level nodes (help excluded — see {@link #createHelp}). */
    public static List<CommandNode> create() {
        return List.of(
                new CmdCreate(),
                new CmdDisband(),
                new CmdRename(),
                new CmdDesc(),
                new CmdMotd(),
                new CmdLeave(),
                new CmdKick(),
                new CmdPromote(),
                new CmdDemote(),
                new CmdLeader(),
                new CmdJoin(),
                new CmdInvite(),
                new CmdInfo(),
                new CmdList(),
                new CmdTop(),
                new CmdLanguage(),
                new CmdNotify(),
                new CmdGui());
    }

    /** The paged {@code /f help}, bound to the fully-populated command {@code registry}. */
    public static CommandNode createHelp(Map<String, CommandNode> registry) {
        return new CmdHelp(registry);
    }
}
