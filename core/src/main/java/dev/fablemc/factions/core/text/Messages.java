package dev.fablemc.factions.core.text;

import java.util.Objects;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import dev.fablemc.factions.core.messages.MessageCatalog;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;
import dev.fablemc.factions.platform.resolve.Players;
import dev.fablemc.factions.platform.sched.Scheduling;
import dev.fablemc.factions.platform.text.TextPort;

/**
 * The one runtime consumer of {@link MessageCatalog} (CONTRACTS §4): renders a {@link MessageKey}
 * plus args into a locale-resolved (shaded) Adventure {@link Component} and delivers it through the
 * {@link TextPort} boundary. Every user-facing string the command / feedback layers produce flows
 * through here, so locale resolution and text delivery have exactly one shape.
 *
 * <p><b>Owning thread(s):</b> {@link #to} delivers on the caller thread (the sender's region/main
 * thread — command bodies call it there); {@link #toPlayer} hops to the target's region via
 * {@link Scheduling#runOn} and is safe to call from the writer's feedback fan-out. {@link #render}
 * is pure. <b>Mutability:</b> immutable (holds only the injected collaborators).
 *
 * <p>The recipient's locale is the {@code localeIdx} on their snapshot member record; an unknown
 * recipient (console / not-yet-seen player) uses the catalog's {@linkplain
 * MessageCatalog#defaultLocale() default locale}.
 */
public final class Messages {

    private static final Runnable NO_RETIRED = () -> { };

    private final MessageCatalog catalog;
    private final SnapshotHub snapshots;
    private final Scheduling scheduling;

    /** Constructor injection (CONTRACTS §4): the catalog, the snapshot source, and the scheduler. */
    public Messages(MessageCatalog catalog, SnapshotHub snapshots, Scheduling scheduling) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
    }

    /**
     * Renders {@code key} in {@code sender}'s locale and delivers it on the CALLER thread through
     * {@link TextPort#send} (the caller must already be on the sender's region/main thread).
     */
    public void to(@NotNull CommandSender sender, @NotNull MessageKey key, String... args) {
        Component rendered = render(localeOf(sender), key, args);
        TextPort.send(sender, rendered);
    }

    /**
     * Renders {@code key} in the target's locale and delivers it on the target's region thread via
     * {@link Scheduling#runOn}. A no-op when the player is offline. Safe to call from any thread —
     * this is the delivery path for the writer's committed-effect feedback fan-out.
     */
    public void toPlayer(@NotNull UUID id, @NotNull MessageKey key, String... args) {
        Player player = Players.get(id);
        if (player == null) {
            return;
        }
        Component rendered = render(localeOf(id), key, args);
        scheduling.runOn(player, () -> TextPort.send(player, rendered), NO_RETIRED);
    }

    /** Renders {@code key} with {@code args} in the locale interned at {@code localeIdx}. Pure. */
    public @NotNull Component render(byte localeIdx, @NotNull MessageKey key, String... args) {
        return catalog.render(localeIdx & 0xFF, key, args);
    }

    // ── locale resolution ──────────────────────────────────────────────────────────────────

    private byte localeOf(CommandSender sender) {
        if (sender instanceof Player player) {
            return localeOf(player.getUniqueId());
        }
        return (byte) catalog.defaultLocale();
    }

    private byte localeOf(UUID id) {
        KernelSnapshot snapshot = snapshots.current();
        int ordinal = snapshot.memberOrdinal(id);
        if (ordinal < 0) {
            return (byte) catalog.defaultLocale();
        }
        MemberView member = snapshot.member(ordinal);
        return member != null ? member.localeIdx() : (byte) catalog.defaultLocale();
    }
}
