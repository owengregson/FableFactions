package dev.fablemc.factions.platform.text;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import dev.fablemc.factions.platform.probe.ProbeTarget;
import dev.fablemc.factions.platform.resolve.Feedback;

/**
 * The ONE text→Bukkit boundary (CONTRACTS §3, ARCHITECTURE AM-1). Every user-facing
 * Component is rendered here to a universal {@code §}-legacy string and handed to a sink
 * that exists across the whole 1.7.10 → 26.x range: a Bungee component array via
 * {@code player.spigot().sendMessage(...)} where {@code bungeeChat} probes true, otherwise
 * the universal {@code String sendMessage}. Titles / action bars delegate to
 * {@link Feedback}; inventories use the universal {@code createInventory(String)} overload.
 *
 * <p>The relocated Adventure copy (shaded to {@code dev.fablemc.factions.lib.adventure}) is
 * inert on modern Paper — it never touches the server's own Adventure — so there is no
 * dual-Adventure conflict. This is the <b>only</b> class in {@code :platform} permitted to
 * import {@code net.kyori} (AM-1 ArchUnit boundary); {@code Component} here is the shaded
 * relocated type, never the server's.
 *
 * <p>Owning thread(s): the target's region/main thread (delivery reads live player state).
 * Mutability class: static-only; the serializers and reflective handles are immutable.
 */
public final class TextPort {

    /** Hex-emitting serializer for 1.16+ clients (§x sequences survive). */
    private static final LegacyComponentSerializer HEX_SERIALIZER =
            LegacyComponentSerializer.builder().hexColors().build();

    /** Section serializer that downsamples hex to the nearest named colour (below 1.16). */
    private static final LegacyComponentSerializer SECTION_SERIALIZER = LegacyComponentSerializer.legacySection();

    /** Whether this server's clients render hex colours (§x) — the Spigot ChatColor.of marker. */
    private static final boolean HEX_COLORS = ProbeTarget.BUNGEE_CHAT_COLOR.hasMethod("of", String.class);

    // Reflective Bungee sink: fromLegacyText(String) → BaseComponent[]; Player.Spigot#sendMessage(BaseComponent[]).
    private static final @Nullable Method FROM_LEGACY;
    private static final @Nullable Method SPIGOT_SEND_COMPONENTS;
    private static final boolean BUNGEE_CHAT;

    static {
        Method fromLegacy = null;
        Method spigotSend = null;
        try {
            Class<?> textComponent = Class.forName(ProbeTarget.BUNGEE_TEXT_COMPONENT.className());
            fromLegacy = textComponent.getMethod("fromLegacyText", String.class);
            Class<?> baseComponent = Class.forName(ProbeTarget.BUNGEE_BASE_COMPONENT.className());
            Class<?> baseComponentArray = Array.newInstance(baseComponent, 0).getClass();
            Class<?> spigotClass = Class.forName(ProbeTarget.PLAYER_SPIGOT.className());
            spigotSend = spigotClass.getMethod("sendMessage", baseComponentArray);
        } catch (ReflectiveOperationException | LinkageError absent) {
            fromLegacy = null;
            spigotSend = null;
        }
        boolean ready = fromLegacy != null && spigotSend != null;
        FROM_LEGACY = ready ? fromLegacy : null;
        SPIGOT_SEND_COMPONENTS = ready ? spigotSend : null;
        BUNGEE_CHAT = ready;
    }

    private TextPort() {}

    /** Renders a Component to a {@code §}-legacy string, hex-downsampled when {@code !hexColors}. */
    public static @NotNull String legacy(@NotNull Component component) {
        return (HEX_COLORS ? HEX_SERIALIZER : SECTION_SERIALIZER).serialize(component);
    }

    /**
     * Sends {@code msg} to {@code to}: as a Bungee component array through {@code spigot()}
     * when this server supports it and the target is a player (hover/click-capable sink),
     * otherwise the universal {@code String} sink.
     */
    public static void send(@NotNull CommandSender to, @NotNull Component msg) {
        String rendered = legacy(msg);
        if (BUNGEE_CHAT && to instanceof Player player) {
            try {
                Object components = FROM_LEGACY.invoke(null, rendered);
                SPIGOT_SEND_COMPONENTS.invoke(player.spigot(), components);
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // fall through to the universal String sink
            }
        }
        to.sendMessage(rendered);
    }

    /** Shows a timed title, rendered to legacy strings and delegated to {@link Feedback}. */
    public static void title(
            @NotNull Player player, @NotNull Component title, @NotNull Component sub,
            int in, int stay, int out) {
        Feedback.title(player, legacy(title), legacy(sub), in, stay, out);
    }

    /** Shows an action-bar message, rendered to a legacy string and delegated to {@link Feedback}. */
    public static void actionBar(@NotNull Player player, @NotNull Component msg) {
        Feedback.actionBar(player, legacy(msg));
    }

    /** Creates an inventory with a Component title via the universal {@code String} overload. */
    public static @NotNull Inventory createInventory(
            @NotNull InventoryHolder holder, int size, @NotNull Component title) {
        return Bukkit.createInventory(holder, size, legacy(title));
    }

    /** For the boot report: the resolved text-delivery tiers. */
    public static String describe() {
        return "text delivery — hex=" + (HEX_COLORS ? "native" : "downsampled")
                + " send=" + (BUNGEE_CHAT ? "bungee-components" : "string");
    }
}
