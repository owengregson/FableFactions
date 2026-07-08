package dev.fablemc.factions.core.listen;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * Faction chat-tag injection on the universal legacy path (AM-1): {@code AsyncPlayerChatEvent} at
 * {@code HIGHEST}/{@code ignoreCancelled=true}, on EVERY supported server (it still fires on Paper
 * 26.x). The tag is the faction's pre-rendered {@code tagLegacy} — a {@code §}-encoded string the
 * reducer re-renders once on any name/format change (proposal-C §5d) — prepended to the event
 * format, so the chat hot path never parses MiniMessage and this class never touches (relocated)
 * Adventure (AM-1 boundary: no {@code net.kyori} outside {@code core.text}/{@code core.messages}).
 *
 * <p><b>Owning thread(s):</b> the async chat thread — a wait-free snapshot read plus
 * {@code event.setFormat} only (no JDBC, no kernel-state construction; CONTRACTS §4).
 * <b>Mutability:</b> immutable. Floor-safe baseline listener: the modern {@code AsyncChatEvent}
 * renderer is deliberately NOT used (AM-1 — a relocated Component sink is an {@code AbstractMethodError}).
 */
public final class ChatListener implements Listener {

    private final ListenerContext ctx;

    public ChatListener(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Prepends the mover's faction tag to the chat format (ref-engines.md §3.6 legacy path). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        KernelSnapshot snap = ctx.snapshots().current();
        if (!snap.config().chat().showTag()) {
            return;
        }
        int ord = snap.memberOrdinal(event.getPlayer().getUniqueId());
        if (ord < 0) {
            return;
        }
        int handle = snap.state().ledger().factionHandle(ord);
        if (handle == FactionHandle.WILDERNESS) {
            return;
        }
        Faction faction = snap.faction(handle);
        if (faction == null || !faction.isNormal()) {
            return;
        }
        String tag = faction.tagLegacy();
        if (tag == null || tag.isEmpty()) {
            return;
        }
        event.setFormat(tag + event.getFormat());
    }
}
