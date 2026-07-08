package dev.fablemc.factions.core.listen;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;

import dev.fablemc.factions.core.session.PlayerSession;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.platform.life.ProbeGated;

/**
 * Elytra glide / faction-fly interaction ({@code EntityToggleGlideEvent}, ~1.10+): when a player who
 * has faction-fly active <em>stops</em> gliding, a short fall-immunity window is granted in their
 * confined {@link PlayerSession} so ending an elytra descent over their own claim does not deal fall
 * damage (proposal-C §8.1 — "interacts with fly-in-territory rules"). Observational only; never
 * cancels the toggle.
 *
 * <p><b>Owning thread(s):</b> the entity's region/main thread — snapshot read plus a write to the
 * entity's own confined session (AM-14). <b>Mutability:</b> immutable. {@code @ProbeGated}:
 * {@code EntityToggleGlideEvent} is absent on 1.7.10, so this class links and registers ONLY behind
 * the {@code toggleGlide} capability via {@link dev.fablemc.factions.platform.life.ListenerGate} (AM-13).
 */
@ProbeGated(capability = "toggleGlide")
public final class GlideListener implements Listener {

    /** Fall-immunity window granted when a faction-flyer ends an elytra glide. */
    private static final long GLIDE_GRACE_MILLIS = 3_000L;

    private final ListenerContext ctx;

    public GlideListener(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** Grants a fall-grace window when a faction-flyer stops gliding (proposal-C §8.1). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() || !(event.getEntity() instanceof Player player)) {
            return; // only a player ending a glide
        }
        KernelSnapshot snap = ctx.snapshots().current();
        int ord = snap.memberOrdinal(player.getUniqueId());
        if (ord < 0 || !PlayerLedger.pref(snap.state().ledger().prefsBits(ord), PlayerLedger.PREF_FLY)) {
            return;
        }
        PlayerSession session = ctx.sessions().get(player.getUniqueId());
        if (session != null) {
            session.grantFlyGrace(System.currentTimeMillis() + GLIDE_GRACE_MILLIS);
        }
    }
}
