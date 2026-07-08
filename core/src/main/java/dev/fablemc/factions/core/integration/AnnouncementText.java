package dev.fablemc.factions.core.integration;

import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * Renders a {@link MessageKey} to a single plain string in the server's default locale — the seam
 * that lets the EzCountdown ephemeral display carry catalog text without the integration package
 * ever touching the shaded Adventure {@code Component} (AM-1 ArchUnit gate: the relocated text
 * library may be referenced only from {@code core.text}/{@code core.messages}).
 *
 * <p>Wave 4 supplies the implementation from {@code core.text}/{@code core.messages} (where the
 * text library is permitted). When no renderer is wired, the effect sink falls back to the
 * per-locale chat broadcast for relation announcements (both are the reference's announcement path).
 *
 * <p><b>Owning thread(s):</b> called on the main region by the effect sink. <b>Mutability:</b>
 * implementations are immutable.
 */
@FunctionalInterface
public interface AnnouncementText {

    /** The rendered default-locale string for {@code key} with {@code args}. */
    String render(MessageKey key, String[] args);
}
