package dev.fablemc.factions.platform.resolve;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import dev.fablemc.factions.platform.probe.ProbeTarget;
import dev.fablemc.factions.platform.probe.Probes;

/**
 * Title / action-bar / boss-bar delivery via era-correct probe chains, with a chat-line
 * fallback and <b>NO NMS anywhere</b> (CONTRACTS §3, proposal-C §7.2 D-8, version-deltas
 * §3.5). The 1.8–1.10 NMS title packet is deliberately skipped in favour of the chat
 * fallback so the platform never touches {@code net.minecraft.server}.
 *
 * <ul>
 *   <li><b>Title</b>: 5-arg {@code sendTitle} → 2-arg {@code sendTitle} → chat lines.</li>
 *   <li><b>Action bar</b>: Spigot {@code ChatMessageType.ACTION_BAR} (reflected, so the
 *       {@code net.md_5.bungee} descriptor never links) → chat line.</li>
 *   <li><b>Boss bar</b>: {@code Bukkit.createBossBar} (reflected, so no {@code BossBar}
 *       descriptor) → skip.</li>
 * </ul>
 *
 * <p>Owning thread(s): the target player's region/main thread. Mutability class:
 * static-only, immutable handles.
 */
public final class Feedback {

    // Title: 5-arg then 2-arg sendTitle.
    private static final @Nullable MethodHandle TITLE_5;
    private static final @Nullable MethodHandle TITLE_2;

    // Action bar: reflected Spigot ChatMessageType.ACTION_BAR path.
    private static final @Nullable Method SPIGOT_SEND;
    private static final @Nullable Object ACTION_BAR;
    private static final @Nullable Constructor<?> TEXT_COMPONENT;

    // Boss bar: reflected Bukkit.createBossBar.
    private static final @Nullable Method CREATE_BOSS_BAR;
    private static final @Nullable Object BAR_COLOR;
    private static final @Nullable Object BAR_STYLE;
    private static final @Nullable Object EMPTY_BAR_FLAGS;

    static {
        TITLE_5 = Probes.virtualHandle(Player.class, "sendTitle", MethodType.methodType(
                void.class, String.class, String.class, int.class, int.class, int.class));
        TITLE_2 = Probes.virtualHandle(Player.class, "sendTitle",
                MethodType.methodType(void.class, String.class, String.class));

        Method spigotSend = null;
        Object actionBar = null;
        Constructor<?> textCtor = null;
        try {
            Class<?> spigotClass = Class.forName(ProbeTarget.PLAYER_SPIGOT.className());
            Class<?> chatMessageType = Class.forName(ProbeTarget.BUNGEE_CHAT_MESSAGE_TYPE.className());
            Class<?> baseComponent = Class.forName(ProbeTarget.BUNGEE_BASE_COMPONENT.className());
            spigotSend = spigotClass.getMethod("sendMessage", chatMessageType, baseComponent);
            actionBar = enumValue(chatMessageType, "ACTION_BAR");
            textCtor = Class.forName(ProbeTarget.BUNGEE_TEXT_COMPONENT.className())
                    .getConstructor(String.class);
        } catch (ReflectiveOperationException | LinkageError absent) {
            spigotSend = null;
            actionBar = null;
            textCtor = null;
        }
        SPIGOT_SEND = (spigotSend != null && actionBar != null && textCtor != null) ? spigotSend : null;
        ACTION_BAR = SPIGOT_SEND != null ? actionBar : null;
        TEXT_COMPONENT = SPIGOT_SEND != null ? textCtor : null;

        Method createBossBar = null;
        Object barColor = null;
        Object barStyle = null;
        Object emptyFlags = null;
        try {
            Class<?> barColorClass = Class.forName(ProbeTarget.BOSS_BAR_COLOR.className());
            Class<?> barStyleClass = Class.forName(ProbeTarget.BOSS_BAR_STYLE.className());
            Class<?> barFlagClass = Class.forName(ProbeTarget.BOSS_BAR_FLAG.className());
            Object flagArray = Array.newInstance(barFlagClass, 0);
            createBossBar = Bukkit.class.getMethod(
                    "createBossBar", String.class, barColorClass, barStyleClass, flagArray.getClass());
            barColor = enumValue(barColorClass, "PURPLE");
            barStyle = enumValue(barStyleClass, "SOLID");
            emptyFlags = flagArray;
        } catch (ReflectiveOperationException | LinkageError absent) {
            createBossBar = null;
        }
        boolean bossReady = createBossBar != null && barColor != null && barStyle != null && emptyFlags != null;
        CREATE_BOSS_BAR = bossReady ? createBossBar : null;
        BAR_COLOR = bossReady ? barColor : null;
        BAR_STYLE = bossReady ? barStyle : null;
        EMPTY_BAR_FLAGS = bossReady ? emptyFlags : null;
    }

    private Feedback() {}

    /** Sends a timed title, degrading 5-arg → 2-arg → chat lines (no NMS). */
    public static void title(
            @NotNull Player player, @Nullable String title, @Nullable String subtitle,
            int fadeIn, int stay, int fadeOut) {
        String main = title == null ? "" : title;
        String sub = subtitle == null ? "" : subtitle;
        if (TITLE_5 != null) {
            try {
                TITLE_5.invoke(player, main, sub, fadeIn, stay, fadeOut);
                return;
            } catch (Throwable ignored) {
                // fall through
            }
        }
        if (TITLE_2 != null) {
            try {
                TITLE_2.invoke(player, main, sub);
                return;
            } catch (Throwable ignored) {
                // fall through
            }
        }
        // 1.7 clients have no title element — the chat fallback (never NMS).
        if (!main.isEmpty()) {
            player.sendMessage(main);
        }
        if (!sub.isEmpty()) {
            player.sendMessage(sub);
        }
    }

    /** Sends an action-bar message via the Spigot path, degrading to a chat line. */
    public static void actionBar(@NotNull Player player, @NotNull String message) {
        if (SPIGOT_SEND != null) {
            try {
                Object component = TEXT_COMPONENT.newInstance(message);
                SPIGOT_SEND.invoke(player.spigot(), ACTION_BAR, component);
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // fall through to chat
            }
        }
        player.sendMessage(message);
    }

    /** Whether the boss-bar API is available on this server. */
    public static boolean bossBarSupported() {
        return CREATE_BOSS_BAR != null;
    }

    /**
     * Creates a boss bar with {@code title} (returned as {@code Object} so no
     * {@code org.bukkit.boss.BossBar} descriptor exists on this always-loaded class), or
     * {@code null} when the API is absent (pre-1.9 → skip).
     */
    public static @Nullable Object createBossBar(@NotNull String title) {
        if (CREATE_BOSS_BAR == null) {
            return null;
        }
        try {
            return CREATE_BOSS_BAR.invoke(null, title, BAR_COLOR, BAR_STYLE, EMPTY_BAR_FLAGS);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /** For the boot report: the resolved title / action-bar / boss-bar tiers. */
    public static String describe() {
        String titleTier = TITLE_5 != null ? "sendTitle-5" : TITLE_2 != null ? "sendTitle-2" : "chat";
        String actionTier = SPIGOT_SEND != null ? "spigot" : "chat";
        String bossTier = CREATE_BOSS_BAR != null ? "createBossBar" : "skip";
        return "title=" + titleTier + " actionBar=" + actionTier + " bossBar=" + bossTier;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @Nullable Object enumValue(@NotNull Class<?> enumType, @NotNull String name) {
        try {
            return Enum.valueOf((Class) enumType, name);
        } catch (IllegalArgumentException | LinkageError absent) {
            return null;
        }
    }
}
