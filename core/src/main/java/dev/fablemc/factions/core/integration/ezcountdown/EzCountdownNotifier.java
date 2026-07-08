package dev.fablemc.factions.core.integration.ezcountdown;

import java.util.EnumSet;
import java.util.Locale;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import dev.fablemc.factions.core.integration.Reflect;

/**
 * The reflection-backed {@link EzCountdownSender} (ref-integrations §8.2): resolves the EzCountdown
 * API service and builds a {@code Notification} via its builder entirely by name — no
 * {@code com.skyblockexp.ezcountdown} imports. When the API is absent, {@link #isEnabled()} reports
 * {@code false} and the effect sink falls back to a chat broadcast.
 *
 * <p><b>Owning thread(s):</b> {@link #setup} on the boot thread; {@link #sendAnnouncement} on the
 * main region. <b>Mutability:</b> holds only the presence flag; the API provider is re-resolved per
 * call (services may register late).
 *
 * <p>The provider FQNs below are best-effort against the EzCountdown API surface; any mismatch
 * degrades gracefully to the chat-broadcast fallback (never throws).
 */
public final class EzCountdownNotifier implements EzCountdownSender {

    private static final String EZ_API = "com.skyblockexp.ezcountdown.api.EzCountdownApi";
    private static final String NOTIFICATION = "com.skyblockexp.ezcountdown.api.Notification";
    private static final String DISPLAY_TYPE = "com.skyblockexp.ezcountdown.api.DisplayType";
    private static final String DEFAULT_DISPLAY = "ACTION_BAR";

    private final Logger logger;
    private boolean present;

    /** Constructor injection: the plugin logger. */
    public EzCountdownNotifier(Logger logger) {
        this.logger = logger;
    }

    /** {@code true} when the EzCountdown plugin and its API class are both on the classpath. */
    public boolean setup() {
        present = Reflect.pluginPresent("EzCountdown") && Reflect.classPresent(EZ_API);
        return present;
    }

    @Override
    public boolean isEnabled() {
        return present && api() != null;
    }

    @Override
    public void sendAnnouncement(String message, long durationSeconds, String[] displayTypes) {
        try {
            Object api = api();
            if (api == null) {
                return;
            }
            Object builder = Reflect.callStatic(Reflect.findClass(NOTIFICATION), "builder");
            Reflect.call(builder, "duration", Reflect.sig(long.class), durationSeconds);
            Reflect.call1(builder, "displays", parseDisplayTypes(displayTypes));
            Reflect.call(builder, "message", Reflect.sig(String.class), message);
            Object notification = Reflect.call(builder, "build");
            Reflect.call1(api, "sendNotification", notification);
        } catch (RuntimeException | LinkageError failed) {
            logger.warning("EzCountdown sendNotification failed: " + failed.getMessage());
        }
    }

    /** Re-resolves the registered EzCountdown API provider, or {@code null}. */
    private Object api() {
        Class<?> apiClass = Reflect.findClass(EZ_API);
        if (apiClass == null) {
            return null;
        }
        RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(apiClass);
        return rsp != null ? rsp.getProvider() : null;
    }

    /** Builds the {@code EnumSet<DisplayType>}; unknown tokens warn-and-skip; empty ⇒ {@code ACTION_BAR}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private EnumSet parseDisplayTypes(String[] names) {
        Class<?> displayType = Reflect.findClass(DISPLAY_TYPE);
        EnumSet set = EnumSet.noneOf((Class) displayType);
        if (names != null) {
            for (String name : names) {
                Object constant = enumConstant(displayType, name.toUpperCase(Locale.ROOT));
                if (constant != null) {
                    set.add(constant);
                } else {
                    logger.warning("EzCountdown: unknown display type '" + name + "' — skipping.");
                }
            }
        }
        if (set.isEmpty()) {
            Object actionBar = enumConstant(displayType, DEFAULT_DISPLAY);
            if (actionBar != null) {
                set.add(actionBar);
            }
        }
        return set;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> enumClass, String name) {
        try {
            return Enum.valueOf((Class) enumClass, name);
        } catch (RuntimeException absent) {
            return null;
        }
    }
}
