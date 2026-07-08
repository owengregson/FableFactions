package dev.fablemc.factions.core.listen;

import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * The interned protection / movement / join-delivery message keys the listeners render (house
 * style §8c — a key is interned ONCE into a final field, never per-call {@code MessageKey.of}).
 * Keys reuse the exact reference strings where the reference implementation had them
 * (ref-engines.md §7) and add the D-4 grief-matrix keys under the same {@code custom.protection.*}
 * section (proposal-C §9.2 / D-9).
 *
 * <p><b>Owning thread(s):</b> none — static interned handles, read from any listener thread.
 * <b>Mutability:</b> stateless constant holder.
 */
final class ProtectionText {

    // ── build / interact / grief denials ──────────────────────────────────────────────────
    static final MessageKey NO_BREAK = MessageKey.of("custom.protection.no-break");
    static final MessageKey NO_PLACE = MessageKey.of("custom.protection.no-place");
    static final MessageKey NO_INTERACT = MessageKey.of("custom.protection.no-interact");
    static final MessageKey NO_CONTAINER = MessageKey.of("custom.protection.no-container");
    static final MessageKey NO_BUILD = MessageKey.of("custom.protection.no-build");

    // ── combat denials ─────────────────────────────────────────────────────────────────────
    static final MessageKey FRIENDLY_FIRE = MessageKey.of("custom.protection.friendly-fire-disabled");
    static final MessageKey PVP_SAFEZONE = MessageKey.of("custom.protection.pvp-safezone");
    static final MessageKey PVP_TERRITORY = MessageKey.of("custom.protection.pvp-territory");

    // ── movement notices ─────────────────────────────────────────────────────────────────────
    static final MessageKey ENTER_CLAIMED = MessageKey.of("custom.move.enter-claimed");
    static final MessageKey ENTER = MessageKey.of("custom.move.enter");

    // ── join-time deliveries ─────────────────────────────────────────────────────────────────
    static final MessageKey INVITE_SUMMARY = MessageKey.of("invite.summary");
    static final MessageKey INVITE_ENTRY = MessageKey.of("invite.summary-entry");
    static final MessageKey INBOX_HEADER = MessageKey.of("notifications.inbox-header");
    static final MessageKey MOTD_HEADER = MessageKey.of("motd.header");
    static final MessageKey MOTD_DISPLAY = MessageKey.of("motd.display");

    private ProtectionText() {
    }
}
