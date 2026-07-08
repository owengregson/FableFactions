package dev.fablemc.factions.core.integration.ezcountdown;

/**
 * The EzCountdown ephemeral-announcement façade (ref-integrations §8). Relation announcements route
 * through here when EzCountdown is available; otherwise the effect sink broadcasts to chat.
 *
 * <p><b>Owning thread(s):</b> {@link #sendAnnouncement} runs on the main region (the EzCountdown API
 * is service-bound). <b>Mutability:</b> implementations hold at most a resolved API handle.
 */
public interface EzCountdownSender {

    /** {@code true} when the EzCountdown API is registered and usable right now. */
    boolean isEnabled();

    /**
     * Displays {@code message} (MiniMessage-formatted) for {@code durationSeconds} across the given
     * {@code displayTypes} (uppercase tokens; empty ⇒ {@code ACTION_BAR}). No YAML entry is created —
     * the announcement is ephemeral.
     */
    void sendAnnouncement(String message, long durationSeconds, String[] displayTypes);
}
