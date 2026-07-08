package dev.fablemc.factions.core.integration.discordsrv;

import java.util.logging.Logger;

import dev.fablemc.factions.core.integration.Reflect;

/**
 * The DiscordSRV broadcast notifier (ref-integrations §7), fully reflection-based — no imports of
 * {@code github.scarsz.discordsrv}. Faction lifecycle and effective-relation effects render a
 * config template and post it to Discord via JDA's async {@code RestAction.queue()}.
 *
 * <p><b>Owning thread(s):</b> {@link #setup} on the boot thread; {@link #sendMessage} on the async
 * thread the effect sink hops to (network IO). <b>Mutability:</b> holds only the availability flag
 * and target channel id.
 *
 * <p>Messages are plain Discord markdown (emoji shortcodes, {@code **bold**}) — never MiniMessage.
 */
public final class DiscordNotifier {

    /** The DiscordSRV main class (exact — note there is no leading {@code com.}). */
    private static final String DSRV_CLASS = "github.scarsz.discordsrv.DiscordSRV";

    private final Logger logger;
    private final String channelId;
    private volatile boolean available;

    /** Constructor injection: the plugin logger and the configured target channel id (may be blank). */
    public DiscordNotifier(Logger logger, String channelId) {
        this.logger = logger;
        this.channelId = channelId == null ? "" : channelId;
    }

    /** {@code true} when DiscordSRV is present and its main class is loadable. */
    public boolean setup() {
        if (!Reflect.pluginPresent("DiscordSRV")) {
            return false;
        }
        if (!Reflect.classPresent(DSRV_CLASS)) {
            logger.warning("DiscordSRV plugin found but class not loadable.");
            return false;
        }
        available = true;
        return true;
    }

    /** {@code true} once {@link #setup()} confirmed DiscordSRV. */
    public boolean isEnabled() {
        return available;
    }

    /** Posts {@code message} to the target (or main) Discord channel; a no-op when unavailable. */
    public void sendMessage(String message) {
        if (!available) {
            return;
        }
        try {
            Object dsrv = Reflect.callStatic(Reflect.findClass(DSRV_CLASS), "getPlugin");
            Object channel = resolveChannel(dsrv);
            if (channel == null) {
                return;
            }
            Object restAction = Reflect.call(channel, "sendMessage",
                    Reflect.sig(CharSequence.class), message);
            Reflect.call(restAction, "queue");   // fire-and-forget
        } catch (RuntimeException | LinkageError failed) {
            logger.warning("DiscordSRV message send failed: " + failed.getMessage());
        }
    }

    private Object resolveChannel(Object dsrv) {
        if (!channelId.isEmpty()) {
            Object jda = Reflect.call(dsrv, "getJda");
            if (jda != null) {
                Object channel = Reflect.call1(jda, "getTextChannelById", channelId);
                if (channel != null) {
                    return channel;
                }
                logger.warning("DiscordSRV: channel '" + channelId + "' not found — using main channel.");
            }
        }
        return Reflect.call(dsrv, "getMainTextChannel");
    }
}
