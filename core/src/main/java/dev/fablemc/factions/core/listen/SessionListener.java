package dev.fablemc.factions.core.listen;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.core.session.PlayerSession;
import dev.fablemc.factions.kernel.config.NotificationRouting;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.SessionIntent;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.InboxTable;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.PlayerLedger;

/**
 * The connection lifecycle listener ({@code PlayerJoinEvent}/{@code PlayerQuitEvent} at
 * {@code MONITOR}): emits {@code PlayerConnected}/{@code PlayerDisconnected} intents, opens/closes the
 * confined {@link PlayerSession}, and runs the join-time deliveries — pending invites, the offline
 * inbox (acknowledged via {@code AckInbox} so nothing is double-delivered and overflow past
 * {@code max-per-login} stays queued — the inbox-loss fix, AM), the faction MOTD, and the op update
 * notice hook (ref-engines.md §3.9/§3.13, proposal-C §8.1).
 *
 * <p><b>Owning thread(s):</b> the joining/leaving player's region/main thread — so
 * {@link dev.fablemc.factions.core.session.SessionRegistry#open}/{@code close} run on the owning
 * thread (AM-14). Deliveries are snapshot reads that hand off to {@code Messages.toPlayer}, which
 * hops each send to the recipient's region; the two lifecycle intents go on the unbounded system
 * lane (a connect/disconnect must never be dropped). <b>Mutability:</b> immutable. Floor-safe
 * baseline listener.
 */
public final class SessionListener implements Listener {

    private final ListenerContext ctx;
    private final @Nullable Consumer<Player> updateNotice;

    /**
     * @param updateNotice the op-join update-notice hook (Wave 4 wires the update checker here);
     *                     {@code null} disables the notice (the reference-off default until wired)
     */
    public SessionListener(ListenerContext ctx, @Nullable Consumer<Player> updateNotice) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.updateNotice = updateNotice;
    }

    /** Establishes the session + online epoch, then delivers queued join notices. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        ctx.sessions().open(player);
        // Re-arm a combat tag that was still ticking when the player logged out, so a relog can't
        // be used to escape combat and immediately /f home (finding #28).
        ctx.sessions().restoreCombatTag(id, System.currentTimeMillis());
        ctx.bus().submitSystem(new SessionIntent.PlayerConnected(id, player.getName(), null));
        KernelSnapshot snap = ctx.snapshots().current();
        int ord = snap.memberOrdinal(id);
        int prefs = ord >= 0 ? snap.state().ledger().prefsBits(ord) : PlayerLedger.DEFAULT_PREFS;
        deliverInvites(id, snap, prefs);
        deliverInbox(id, snap);
        deliverMotd(id, snap, ord, prefs);
        deliverUpdateNotice(player, snap);
    }

    /** Settles the offline epoch and tears the session down on quit. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        ctx.bus().submitSystem(new SessionIntent.PlayerDisconnected(id));
        ctx.sessions().close(id);
    }

    private void deliverInvites(UUID id, KernelSnapshot snap, int prefs) {
        InviteTable.Invite[] invites = snap.state().invites().forInvitee(id);
        if (invites.length == 0 || !PlayerLedger.pref(prefs, PlayerLedger.PREF_NOTIFY_INVITES)) {
            return;
        }
        ctx.messages().toPlayer(id, ProtectionText.INVITE_SUMMARY, Integer.toString(invites.length));
        for (InviteTable.Invite invite : invites) {
            Faction faction = snap.state().factions().at(invite.factionOrdinal());
            String factionName = faction != null ? faction.name() : "?";
            ctx.messages().toPlayer(id, ProtectionText.INVITE_ENTRY, factionName,
                    resolveName(snap, invite.inviter()));
        }
    }

    private void deliverInbox(UUID id, KernelSnapshot snap) {
        InboxTable.InboxEntry[] entries = snap.state().inbox().forPlayer(id);
        if (entries.length == 0) {
            return;
        }
        NotificationRouting routing = snap.config().notifications();
        if (!routing.inboxEnabled()) {
            return;
        }
        int cap = routing.inboxMaxPerLogin() > 0 ? routing.inboxMaxPerLogin() : entries.length;
        int count = Math.min(entries.length, cap);
        ctx.messages().toPlayer(id, ProtectionText.INBOX_HEADER, Integer.toString(count));
        long[] delivered = new long[count];
        for (int i = 0; i < count; i++) {
            InboxTable.InboxEntry entry = entries[i];
            ctx.messages().toPlayer(id, entry.key(), entry.args());
            delivered[i] = entry.id();
        }
        // Acknowledge ONLY the delivered ids: overflow beyond max-per-login stays queued (AM).
        ctx.bus().submitSystem(new SessionIntent.AckInbox(id, delivered));
    }

    private void deliverMotd(UUID id, KernelSnapshot snap, int ord, int prefs) {
        if (ord < 0 || !PlayerLedger.pref(prefs, PlayerLedger.PREF_NOTIFY_STATUS)) {
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
        String motd = faction.motd();
        if (motd == null || motd.isEmpty()) {
            return;
        }
        ctx.messages().toPlayer(id, ProtectionText.MOTD_HEADER);
        ctx.messages().toPlayer(id, ProtectionText.MOTD_DISPLAY, motd);
    }

    private void deliverUpdateNotice(Player player, KernelSnapshot snap) {
        if (updateNotice == null || !snap.config().updates().notifyOpsOnJoin() || !player.isOp()) {
            return;
        }
        updateNotice.accept(player);
    }

    private static String resolveName(KernelSnapshot snap, UUID id) {
        int ord = snap.memberOrdinal(id);
        if (ord < 0) {
            return "?";
        }
        String name = snap.state().ledger().nameLast(ord);
        return name != null ? name : "?";
    }
}
