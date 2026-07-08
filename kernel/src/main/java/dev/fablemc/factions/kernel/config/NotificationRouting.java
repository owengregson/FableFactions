package dev.fablemc.factions.kernel.config;

/**
 * Typed {@code notifications.yml} routing (pvp-resources.md §3, pvp-services.md §2.3).
 *
 * <p><b>Owning thread(s):</b> parsed in {@code :core}, read on any thread. <b>Mutability:</b>
 * immutable value. <b>Reducer rule:</b> swapped whole via {@code SwapConfig}.
 *
 * <p>{@code inboxMaxPerLogin} caps entries delivered per login; excess stays queued. EzCountdown
 * announcement display types are kept as raw uppercase tokens
 * ({@code ACTION_BAR/BOSS_BAR/TITLE/CHAT/SCOREBOARD}); an empty configured list defaults to
 * {@code [ACTION_BAR]}.
 */
public record NotificationRouting(
        boolean inboxEnabled,
        int inboxMaxPerLogin,
        boolean notifyPlayerJoined,
        boolean taxNotifyMembers,
        boolean ezCountdownEnabled,
        long ezCountdownDurationSeconds,
        String[] ezCountdownDisplayTypes) {

    /** The reference-default notification routing. */
    public static NotificationRouting defaults() {
        return new NotificationRouting(
                true,   // inboxEnabled
                20,     // inboxMaxPerLogin
                true,   // notifyPlayerJoined
                true,   // taxNotifyMembers
                true,   // ezCountdownEnabled
                8L,     // ezCountdownDurationSeconds
                new String[] {"ACTION_BAR"});
    }
}
