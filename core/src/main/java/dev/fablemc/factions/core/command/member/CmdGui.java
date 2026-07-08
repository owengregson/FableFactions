package dev.fablemc.factions.core.command.member;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.gui.Menus;
import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * {@code /f gui [menu]} (alias {@code menu}) — opens a config-driven GUI menu through the
 * {@link Menus} seam (ref-commands-core.md §7.16). A blank menu opens the default; an unknown menu
 * reports {@code custom.gui.menu-not-found}. No intent is submitted (the menu render is a pure seam
 * call).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdGui extends CommandNode {

    private static final MessageKey NOT_FOUND = MessageKey.of("custom.gui.menu-not-found");

    CmdGui() {
        super("gui", "menu");
        setPermission("factions.cmd.gui");
        setRequiresPlayer(true);
        setDescription("Open the faction GUI");
        setOptionalArgs("menu");
    }

    @Override
    protected void perform(CommandContext ctx) {
        Menus menus = ctx.services().menus();
        String menu = ctx.argOrEmpty(0);
        boolean opened = menu.isBlank() ? menus.openDefault(ctx.player()) : menus.open(ctx.player(), menu);
        if (!opened) {
            ctx.send(NOT_FOUND, menu.isBlank() ? "default" : menu);
        }
    }
}
