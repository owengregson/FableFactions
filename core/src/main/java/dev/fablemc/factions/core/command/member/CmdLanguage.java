package dev.fablemc.factions.core.command.member;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.gui.Menus;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * {@code /f language [code|reset]} (aliases {@code lang}, {@code locale}) — opens the language
 * selector GUI through the {@link Menus} seam, or prints the textual language status
 * (ref-commands-core.md §7.31). Per-player locale selection is performed by the GUI's
 * {@code LANGUAGE_SET} action (W3e), which owns catalog-index resolution; the command layer has no
 * catalog handle, so it opens the selector rather than resolving a code itself.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the caller's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdLanguage extends CommandNode {

    private static final MessageKey OVERRIDE_DISABLED = MessageKey.of("language.override-disabled");
    private static final MessageKey DEFAULT = MessageKey.of("language.default");
    private static final MessageKey AVAILABLE = MessageKey.of("language.available");
    private static final MessageKey USAGE = MessageKey.of("language.usage");

    CmdLanguage() {
        super("language", "lang", "locale");
        setPermission("factions.cmd.language");
        setRequiresPlayer(true);
        setDescription("Choose your language");
        setOptionalArgs("code|reset");
    }

    @Override
    protected void perform(CommandContext ctx) {
        ConfigImage.Language language = ctx.snap().config().language();
        if (!language.allowPlayerOverride()) {
            ctx.send(OVERRIDE_DISABLED);
            sendStatus(ctx, language);
            return;
        }
        Menus menus = ctx.services().menus();
        if (language.commandOpensGui() && menus.openLanguage(ctx.player())) {
            return;
        }
        sendStatus(ctx, language);
    }

    private static void sendStatus(CommandContext ctx, ConfigImage.Language language) {
        ctx.send(DEFAULT, language.defaultLocale());
        ctx.send(AVAILABLE, String.join(", ", language.visibleLocales()));
        ctx.send(USAGE);
    }
}
