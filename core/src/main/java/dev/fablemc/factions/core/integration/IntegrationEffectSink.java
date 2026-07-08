package dev.fablemc.factions.core.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import dev.fablemc.factions.core.integration.discordsrv.DiscordNotifier;
import dev.fablemc.factions.core.integration.dynmap.DynmapLayer;
import dev.fablemc.factions.core.integration.ezcountdown.EzCountdownSender;
import dev.fablemc.factions.core.integration.lwc.LwcInterop;
import dev.fablemc.factions.core.integration.worldguard.WgRegionMirror;
import dev.fablemc.factions.core.pipeline.EffectSink;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.kernel.config.NotificationRouting;
import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.ExternalEffect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.effect.RelationEffect;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.vocab.Relation;
import dev.fablemc.factions.platform.resolve.Players;
import dev.fablemc.factions.platform.resolve.Worlds;
import dev.fablemc.factions.platform.sched.Scheduling;

/**
 * The single {@link EffectSink} that fans committed kernel effects out to the enabled OUT-adapters
 * (proposal-C §10.3, work order W3f). It consumes claim/lifecycle/relation domain effects plus the
 * dedicated {@code ExternalEffect} requests and routes each side effect onto the correct thread: the
 * dynmap layer coalesces on its 2-second flush; DiscordSRV posts async; WorldGuard region mirroring
 * and LWC purges are location-bound and route through {@link Scheduling#runAt} (AM-12); relation
 * announcements go to EzCountdown (or a chat broadcast fallback).
 *
 * <p><b>Owning thread(s):</b> {@link #accept} runs on the single writer thread's fan-out — it only
 * reads the snapshot and hands work to the scheduler / lock-free queues; it never blocks and never
 * touches Bukkit inline. <b>Mutability:</b> holds immutable collaborators; each per-effect dispatch
 * is isolated in a try/catch so a misbehaving adapter can never break the writer fan-out.
 */
public final class IntegrationEffectSink implements EffectSink {

    private static final MessageKey RELATION_ALLY = MessageKey.of("ezcountdown.relation-ally");
    private static final MessageKey RELATION_TRUCE = MessageKey.of("ezcountdown.relation-truce");
    private static final MessageKey RELATION_ENEMY = MessageKey.of("ezcountdown.relation-enemy");
    private static final double CHUNK_CENTER_Y = 64.0;

    private final SnapshotHub snapshots;
    private final Scheduling scheduling;
    private final Worlds worlds;
    private final Messages messages;
    private final IntegrationSettings settings;
    private final Logger logger;

    private final DynmapLayer dynmap;                 // nullable — only when dynmap enabled
    private final DiscordNotifier discord;            // nullable — only when DiscordSRV enabled
    private final EzCountdownSender ezCountdown;       // nullable — only when EzCountdown present
    private final WgRegionMirror wgMirror;            // nullable — only in WG region-sync mode
    private final LwcInterop lwc;                     // non-null (Noop when LWC absent)
    private final AnnouncementText announcementText;  // nullable — Wave 4 renderer for EzCountdown text

    /** Constructor injection of the collaborators and the (nullable) enabled adapters. */
    public IntegrationEffectSink(SnapshotHub snapshots, Scheduling scheduling, Worlds worlds,
                                 Messages messages, IntegrationSettings settings, Logger logger,
                                 DynmapLayer dynmap, DiscordNotifier discord, EzCountdownSender ezCountdown,
                                 WgRegionMirror wgMirror, LwcInterop lwc, AnnouncementText announcementText) {
        this.snapshots = snapshots;
        this.scheduling = scheduling;
        this.worlds = worlds;
        this.messages = messages;
        this.settings = settings;
        this.logger = logger;
        this.dynmap = dynmap;
        this.discord = discord;
        this.ezCountdown = ezCountdown;
        this.wgMirror = wgMirror;
        this.lwc = lwc;
        this.announcementText = announcementText;
    }

    @Override
    public void accept(List<Effect> batch, long lastSeq) {
        KernelSnapshot snapshot = snapshots.current();
        for (int i = 0; i < batch.size(); i++) {
            try {
                dispatch(snapshot, batch.get(i));
            } catch (RuntimeException | LinkageError isolated) {
                logger.log(Level.WARNING, "integration effect dispatch failed", isolated);
            }
        }
    }

    // A selective filter (NOT an exhaustive dispatcher): only integration-relevant effects are
    // handled; everything else is intentionally ignored, so there is no fail-safe throw here.
    private void dispatch(KernelSnapshot snapshot, Effect effect) {
        if (effect instanceof ClaimEffect.ClaimSet e) {
            onClaimSet(snapshot, e.worldIdx(), e.key(), e.faction());
        } else if (effect instanceof ClaimEffect.ClaimRemoved e) {
            onClaimRemoved(e.worldIdx(), e.key());
        } else if (effect instanceof ClaimEffect.ZoneSet e) {
            onClaimSet(snapshot, e.worldIdx(), e.key(), e.zoneOrdinal());
        } else if (effect instanceof ClaimEffect.ZoneRemoved e) {
            onClaimRemoved(e.worldIdx(), e.key());
        } else if (effect instanceof LifecycleEffect.FactionCreated e) {
            discordBroadcast(settings.discord().factionCreatedEnabled(),
                    settings.discord().factionCreatedMessage(), "{faction}", e.name());
        } else if (effect instanceof LifecycleEffect.FactionDisbanded e) {
            onFactionDisbanded(e.faction(), e.name());
        } else if (effect instanceof LifecycleEffect.FactionRenamed e) {
            if (dynmap != null) {
                dynmap.enqueueRename(Integer.toString(FactionHandle.ordinal(e.faction())), e.newName());
            }
        } else if (effect instanceof RelationEffect.RelationEffective e) {
            onRelationEffective(snapshot, e.a(), e.b(), e.kind());
        } else if (effect instanceof ExternalEffect.WgRegionUpsert e) {
            onWgUpsert(snapshot, e.worldIdx(), e.key(), e.faction());
        } else if (effect instanceof ExternalEffect.WgRegionRemove e) {
            onWgRemove(e.worldIdx(), e.key());
        } else if (effect instanceof ExternalEffect.LwcPurgeRequested e) {
            onLwcPurge(e.worldIdx(), e.key(), e.newOwner());
        }
    }

    // ── dynmap ───────────────────────────────────────────────────────────────────────────────

    private void onClaimSet(KernelSnapshot snapshot, int worldIdx, long key, int factionRef) {
        if (dynmap == null) {
            return;
        }
        UUID worldUid = worlds.uid(worldIdx);
        if (worldUid == null) {
            return;
        }
        Faction faction = factionOf(snapshot, factionRef);
        String name = faction != null ? faction.name() : "";
        dynmap.enqueueClaim(Integer.toString(FactionHandle.ordinal(factionRef)), name,
                worldUid, ChunkKeys.x(key), ChunkKeys.z(key));
    }

    private void onClaimRemoved(int worldIdx, long key) {
        if (dynmap == null) {
            return;
        }
        UUID worldUid = worlds.uid(worldIdx);
        if (worldUid != null) {
            dynmap.enqueueUnclaim(worldUid, ChunkKeys.x(key), ChunkKeys.z(key));
        }
    }

    private void onFactionDisbanded(int factionRef, String name) {
        if (dynmap != null) {
            dynmap.enqueueDisband(Integer.toString(FactionHandle.ordinal(factionRef)));
        }
        discordBroadcast(settings.discord().factionDisbandedEnabled(),
                settings.discord().factionDisbandedMessage(), "{faction}", name);
    }

    // ── WorldGuard region mirror + LWC purge (location-bound, AM-12) ──────────────────────────

    private void onWgUpsert(KernelSnapshot snapshot, int worldIdx, long key, int factionRef) {
        if (wgMirror == null) {
            return;
        }
        UUID worldUid = worlds.uid(worldIdx);
        if (worldUid == null) {
            return;
        }
        int cx = ChunkKeys.x(key);
        int cz = ChunkKeys.z(key);
        List<UUID> members = memberUuids(snapshot, factionRef);
        runAtChunk(worldUid, cx, cz, world -> wgMirror.upsert(world, cx, cz, members));
    }

    private void onWgRemove(int worldIdx, long key) {
        if (wgMirror == null) {
            return;
        }
        UUID worldUid = worlds.uid(worldIdx);
        if (worldUid == null) {
            return;
        }
        int cx = ChunkKeys.x(key);
        int cz = ChunkKeys.z(key);
        runAtChunk(worldUid, cx, cz, world -> wgMirror.remove(world, cx, cz));
    }

    private void onLwcPurge(int worldIdx, long key, int newOwner) {
        if (!settings.lwcRemoveOnClaimChange()) {
            return;
        }
        UUID worldUid = worlds.uid(worldIdx);
        if (worldUid == null) {
            return;
        }
        int cx = ChunkKeys.x(key);
        int cz = ChunkKeys.z(key);
        int newOwnerOrd = FactionHandle.ordinal(newOwner);
        runAtChunk(worldUid, cx, cz, world -> lwc.purge(world, cx, cz, newOwnerOrd));
    }

    /**
     * Resolves the Bukkit {@link World} on the global thread and hops to the chunk's owning region
     * (AM-12) before running {@code action}; a no-op when the world is unloaded.
     */
    private void runAtChunk(UUID worldUid, int chunkX, int chunkZ, Consumer<World> action) {
        scheduling.runGlobal(() -> {
            World world = Bukkit.getWorld(worldUid);
            if (world == null) {
                return;
            }
            Location location = new Location(world, (chunkX << 4) + 8, CHUNK_CENTER_Y, (chunkZ << 4) + 8);
            scheduling.runAt(location, () -> action.accept(world));
        });
    }

    // ── relation announcements (Discord + EzCountdown/chat) ───────────────────────────────────

    private void onRelationEffective(KernelSnapshot snapshot, int a, int b, Relation kind) {
        if (kind == Relation.NEUTRAL || kind == Relation.MEMBER) {
            return;   // NEUTRAL/MEMBER never announce
        }
        String source = factionName(snapshot, a);
        String target = factionName(snapshot, b);
        discordRelation(kind, source, target);
        announceRelation(snapshot, kind, source, target);
    }

    private void discordRelation(Relation kind, String source, String target) {
        boolean enabled = switch (kind) {
            case ALLY -> settings.discord().relationAllyEnabled();
            case TRUCE -> settings.discord().relationTruceEnabled();
            case ENEMY -> settings.discord().relationEnemyEnabled();
            case NEUTRAL, MEMBER -> false;
        };
        String template = switch (kind) {
            case ALLY -> settings.discord().relationAllyMessage();
            case TRUCE -> settings.discord().relationTruceMessage();
            case ENEMY -> settings.discord().relationEnemyMessage();
            case NEUTRAL, MEMBER -> "";
        };
        if (!enabled || template.isEmpty()) {
            return;
        }
        String rendered = template.replace("{source}", source).replace("{target}", target);
        postDiscord(rendered);
    }

    private void announceRelation(KernelSnapshot snapshot, Relation kind, String source, String target) {
        MessageKey key = switch (kind) {
            case ALLY -> RELATION_ALLY;
            case TRUCE -> RELATION_TRUCE;
            case ENEMY -> RELATION_ENEMY;
            case NEUTRAL, MEMBER -> null;
        };
        if (key == null) {
            return;
        }
        String[] args = {source, target};
        NotificationRouting routing = snapshot.config().notifications();
        boolean useEzCountdown = ezCountdown != null && announcementText != null
                && routing.ezCountdownEnabled() && ezCountdown.isEnabled();
        if (useEzCountdown) {
            long duration = routing.ezCountdownDurationSeconds();
            String[] displays = routing.ezCountdownDisplayTypes();
            scheduling.runGlobal(() ->
                    ezCountdown.sendAnnouncement(announcementText.render(key, args), duration, displays));
        } else {
            broadcastToChat(key, args);
        }
    }

    private void broadcastToChat(MessageKey key, String[] args) {
        scheduling.runGlobal(() -> {
            for (Player player : Players.online()) {
                messages.toPlayer(player.getUniqueId(), key, args);
            }
        });
    }

    // ── shared helpers ─────────────────────────────────────────────────────────────────────

    private void discordBroadcast(boolean enabled, String template, String token, String value) {
        if (!enabled || template == null || template.isEmpty()) {
            return;
        }
        postDiscord(template.replace(token, value));
    }

    private void postDiscord(String message) {
        if (discord == null || !discord.isEnabled()) {
            return;
        }
        scheduling.runAsync(() -> discord.sendMessage(message));   // JDA RestAction is async/network
    }

    /** Resolves a faction reference that may be a generation-tagged handle or a bare ordinal. */
    private static Faction factionOf(KernelSnapshot snapshot, int ref) {
        Faction faction = snapshot.faction(ref);
        return faction != null ? faction : snapshot.state().factions().at(FactionHandle.ordinal(ref));
    }

    private static String factionName(KernelSnapshot snapshot, int ref) {
        Faction faction = factionOf(snapshot, ref);
        return faction != null ? faction.name() : "";
    }

    /** The live member UUIDs of {@code factionRef}; empty for SAFEZONE/WARZONE (no WG build domain). */
    private static List<UUID> memberUuids(KernelSnapshot snapshot, int factionRef) {
        int ord = FactionHandle.ordinal(factionRef);
        List<UUID> uuids = new ArrayList<>();
        if (ord == FactionHandle.SAFEZONE_ORDINAL || ord == FactionHandle.WARZONE_ORDINAL) {
            return uuids;
        }
        PlayerLedger ledger = snapshot.state().ledger();
        int hw = ledger.highWater();
        for (int i = 0; i < hw; i++) {
            if (ledger.has(i) && FactionHandle.ordinal(ledger.factionHandle(i)) == ord) {
                uuids.add(ledger.uuid(i));
            }
        }
        return uuids;
    }
}
