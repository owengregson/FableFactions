package dev.fablemc.factions.core.listen;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import dev.fablemc.factions.core.session.PlayerSession;
import dev.fablemc.factions.kernel.config.FlyConfig;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.ClaimIntent;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.vocab.ClaimMode;
import dev.fablemc.factions.platform.resolve.Feedback;

/**
 * THE one consolidated movement handler (proposal-C §8.1): chunk-crossing detection drives territory
 * titles, auto-claim / auto-unclaim, and the fly-on-threat re-check, at {@code MONITOR}/{@code
 * ignoreCancelled=true} for both {@code PlayerMoveEvent} and {@code PlayerTeleportEvent}. It also
 * owns the fly-revocation fall grace: {@link #flyRecheck} grants it when it cuts a flyer's flight,
 * and {@link #onFallDamage} consumes it so a revoked flyer does not die on landing. The
 * expensive work runs only on an actual chunk change — the first lines are a null-{@code getTo} guard
 * and a packed-long same-chunk fast exit (proposal-C §8.2), so the overwhelmingly common
 * same-chunk move returns in a couple of instructions.
 *
 * <p><b>Owning thread(s):</b> the moving player's own region/main thread — so the player's confined
 * {@link PlayerSession} (AM-14) is mutated directly here, snapshot reads are wait-free, and the
 * auto-territory work is a plain {@code IntentBus.submit} (the reducer does every validation).
 * <b>Mutability:</b> immutable (injected collaborators only). Floor-safe baseline listener — the
 * only damage-cause getstatic is the floor-present {@code DamageCause.FALL} and no
 * {@code TeleportCause} constant is referenced (AM-13 {@code verifyNoStickyGetstatic}).
 */
public final class MoveListener implements Listener {

    private static final int TITLE_FADE_IN = 5;
    private static final int TITLE_STAY = 30;
    private static final int TITLE_FADE_OUT = 5;

    /** Fall-immunity window granted when faction-fly is revoked mid-air (drops the fall damage). */
    private static final long FLY_GRACE_MILLIS = 5_000L;

    private static final String NAME_WILDERNESS = "Wilderness";
    private static final String NAME_SAFEZONE = "Safezone";
    private static final String NAME_WARZONE = "Warzone";

    private final ListenerContext ctx;

    public MoveListener(ListenerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /** The walking path: titles + auto-territory + fly re-check on a chunk crossing. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        handleCrossing(event.getPlayer(), event.getFrom(), event.getTo(), true);
    }

    /** The teleport path: titles + fly re-check on a chunk crossing (no auto-territory). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        handleCrossing(event.getPlayer(), event.getFrom(), event.getTo(), false);
    }

    /**
     * Consumes the fly-revocation fall grace granted by {@link #flyRecheck}: when faction-fly is
     * revoked mid-air the player would otherwise take full fall damage and die on landing. While the
     * player's confined {@link PlayerSession} reports it is still inside the grace window, the fall
     * damage is cancelled ({@link PlayerSession#grantFlyGrace} is written on revocation by
     * {@link #flyRecheck} and, on modern servers, by the probe-gated {@link GlideListener}).
     * {@code EntityDamageEvent} and {@code DamageCause.FALL} are floor-present, so this stays a
     * baseline handler; the session is read on the victim's own region thread (AM-14).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return; // only players taking fall damage
        }
        PlayerSession session = ctx.sessions().get(player.getUniqueId());
        if (session != null && session.inFlyGrace(System.currentTimeMillis())) {
            event.setCancelled(true);
        }
    }

    private void handleCrossing(Player player, Location from, Location to, boolean autoTerritory) {
        if (to == null) {
            return; // teleport cancelled / destination unresolved
        }
        boolean sameWorld = from.getWorld() == to.getWorld();
        long toKey = ChunkKeys.fromBlock(to.getBlockX(), to.getBlockZ());
        long fromKey = ChunkKeys.fromBlock(from.getBlockX(), from.getBlockZ());
        if (sameWorld && fromKey == toKey) {
            return; // same chunk — the hot fast exit
        }

        UUID id = player.getUniqueId();
        KernelSnapshot snap = ctx.snapshots().current();
        int toWorldIdx = ctx.worlds().indexOf(to.getWorld());
        int toOwner = snap.claimOwnerAt(toWorldIdx, toKey);
        int fromOwner = sameWorld
                ? snap.claimOwnerAt(ctx.worlds().indexOf(from.getWorld()), fromKey)
                : FactionHandle.WILDERNESS;

        int ord = snap.memberOrdinal(id);
        PlayerLedger ledger = snap.state().ledger();
        int prefs = ord >= 0 ? ledger.prefsBits(ord) : 0;
        int factionHandle = ord >= 0 ? ledger.factionHandle(ord) : FactionHandle.WILDERNESS;

        if (toOwner != fromOwner && PlayerLedger.pref(prefs, PlayerLedger.PREF_TERRITORY_TITLES)) {
            announceTerritory(player, snap, toOwner);
        }
        if (autoTerritory) {
            autoTerritory(id, prefs, factionHandle, toWorldIdx, toKey);
        }
        flyRecheck(player, snap, id, prefs, factionHandle, toOwner);

        PlayerSession session = ctx.sessions().get(id);
        if (session != null) {
            session.updateChunk(toKey);
        }
    }

    private void announceTerritory(Player player, KernelSnapshot snap, int owner) {
        String name;
        boolean claimed = false;
        if (owner == FactionHandle.WILDERNESS) {
            name = NAME_WILDERNESS;
        } else {
            int ownerOrd = FactionHandle.ordinal(owner);
            if (ownerOrd == FactionHandle.SAFEZONE_ORDINAL) {
                name = snap.config().zones().safeZoneEnabled() ? NAME_SAFEZONE : NAME_WILDERNESS;
            } else if (ownerOrd == FactionHandle.WARZONE_ORDINAL) {
                name = snap.config().zones().warZoneEnabled() ? NAME_WARZONE : NAME_WILDERNESS;
            } else {
                Faction faction = snap.faction(owner);
                if (faction != null && faction.isNormal()) {
                    name = faction.name();
                    claimed = true;
                } else {
                    name = NAME_WILDERNESS;
                }
            }
        }
        ctx.messages().to(player, claimed ? ProtectionText.ENTER_CLAIMED : ProtectionText.ENTER, name);
        Feedback.title(player, name, null, TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
    }

    private void autoTerritory(UUID id, int prefs, int factionHandle, int toWorldIdx, long toKey) {
        if (factionHandle == FactionHandle.WILDERNESS
                || !FactionHandle.isNormalOrdinal(FactionHandle.ordinal(factionHandle))) {
            return; // not in a normal faction — nothing to auto-claim for
        }
        int mode = PlayerLedger.autoMode(prefs);
        if (mode == PlayerLedger.AUTO_MODE_OFF) {
            return;
        }
        // Per-crossing single-key array: allocated ONLY when auto-mode is on (opt-in, human-rate).
        long[] keys = {toKey};
        if (mode == PlayerLedger.AUTO_MODE_CLAIM) {
            ctx.bus().submit(new ClaimIntent.ClaimChunks(id, factionHandle, toWorldIdx, keys,
                    ClaimMode.AUTO), Origin.player(id));
        } else if (mode == PlayerLedger.AUTO_MODE_UNCLAIM) {
            ctx.bus().submit(new ClaimIntent.UnclaimChunks(id, factionHandle, toWorldIdx, keys),
                    Origin.player(id));
        }
    }

    private void flyRecheck(Player player, KernelSnapshot snap, UUID id, int prefs, int factionHandle,
                            int toOwner) {
        if (!PlayerLedger.pref(prefs, PlayerLedger.PREF_FLY)) {
            return; // faction-fly not active for this player
        }
        FlyConfig fly = snap.config().fly();
        if (!fly.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        PlayerSession session = ctx.sessions().get(id);
        boolean threat = fly.disableOnThreat() && session != null && session.inCombat(now);
        if (!threat && fly.requireOwnTerritory() && !isOwnLand(toOwner, factionHandle)) {
            threat = true;
        }
        if (!threat) {
            return;
        }
        // Faction-fly is survival-only; never steal creative/spectator flight (both !SURVIVAL).
        if (player.getAllowFlight() && player.getGameMode() == GameMode.SURVIVAL) {
            player.setFlying(false);
            player.setAllowFlight(false);
            if (session != null) {
                session.grantFlyGrace(now + FLY_GRACE_MILLIS);
            }
        }
    }

    private static boolean isOwnLand(int owner, int factionHandle) {
        return owner != FactionHandle.WILDERNESS && factionHandle != FactionHandle.WILDERNESS
                && FactionHandle.ordinal(owner) == FactionHandle.ordinal(factionHandle);
    }
}
