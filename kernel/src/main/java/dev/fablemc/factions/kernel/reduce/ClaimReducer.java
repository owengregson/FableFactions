package dev.fablemc.factions.kernel.reduce;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import dev.fablemc.factions.kernel.vocab.FactionAuditAction;
import dev.fablemc.factions.kernel.config.BakedTables;
import dev.fablemc.factions.kernel.config.PowerConfig;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.ChestRules;
import dev.fablemc.factions.kernel.rules.ClaimRules;
import dev.fablemc.factions.kernel.rules.DisbandRules;
import dev.fablemc.factions.kernel.rules.EconomyRules;
import dev.fablemc.factions.kernel.rules.FactionAggregates;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.rules.InviteRules;
import dev.fablemc.factions.kernel.rules.MergeRules;
import dev.fablemc.factions.kernel.rules.MoneyMath;
import dev.fablemc.factions.kernel.rules.NameRules;
import dev.fablemc.factions.kernel.rules.PowerMath;
import dev.fablemc.factions.kernel.rules.PrefRules;
import dev.fablemc.factions.kernel.rules.RelationRules;
import dev.fablemc.factions.kernel.rules.RoleRules;
import dev.fablemc.factions.kernel.rules.TravelRules;
import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.ChestTable;
import dev.fablemc.factions.kernel.state.EscrowTable;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.Home;
import dev.fablemc.factions.kernel.state.InviteTable;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.MergeTable;
import dev.fablemc.factions.kernel.state.NameIndex;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.state.RelationEdges;
import dev.fablemc.factions.kernel.state.RelationKind;
import dev.fablemc.factions.kernel.state.Warp;
import dev.fablemc.factions.kernel.state.WarpTable;
import dev.fablemc.factions.kernel.state.ZoneStats;
import dev.fablemc.factions.kernel.vocab.Relation;
import dev.fablemc.factions.kernel.vocab.PowerSource;
import dev.fablemc.factions.kernel.vocab.PagePhase;
import dev.fablemc.factions.kernel.vocab.NotifyPredicate;
import dev.fablemc.factions.kernel.vocab.InviteRemovalReason;
import dev.fablemc.factions.kernel.vocab.EscrowOutcome;
import dev.fablemc.factions.kernel.vocab.EscrowKind;
import dev.fablemc.factions.kernel.vocab.BroadcastScope;
import dev.fablemc.factions.kernel.vocab.BankTxType;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.kernel.intent.SessionIntent;
import dev.fablemc.factions.kernel.intent.RoleIntent;
import dev.fablemc.factions.kernel.intent.RelationIntent;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.intent.PowerIntent;
import dev.fablemc.factions.kernel.intent.MembershipIntent;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.intent.EconomyIntent;
import dev.fablemc.factions.kernel.intent.ClaimIntent;
import dev.fablemc.factions.kernel.intent.ChestIntent;
import dev.fablemc.factions.kernel.effect.TravelEffect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.effect.SessionEffect;
import dev.fablemc.factions.kernel.effect.RoleEffect;
import dev.fablemc.factions.kernel.effect.RelationEffect;
import dev.fablemc.factions.kernel.effect.PrefEffect;
import dev.fablemc.factions.kernel.effect.PowerEffect;
import dev.fablemc.factions.kernel.effect.MembershipEffect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.effect.ExternalEffect;
import dev.fablemc.factions.kernel.effect.EconomyEffect;
import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.ChestEffect;
import dev.fablemc.factions.kernel.effect.AuditEffect;

/**
 * Claim and zone intents: claim / unclaim / unclaim-all (paged) / admin claim-unclaim / zone set-remove (paged).
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved this code unchanged).
 */
final class ClaimReducer {

    private ClaimReducer() {
    }

    static void reduce(ReduceSupport s, ClaimIntent i) {
        if (i instanceof ClaimIntent.ClaimChunks x) {
            claimChunks(s, x);
        } else if (i instanceof ClaimIntent.UnclaimChunks x) {
            unclaimChunks(s, x);
        } else if (i instanceof ClaimIntent.UnclaimAll x) {
            unclaimAllPage(s, x.faction(), x.actor());
        } else if (i instanceof ClaimIntent.AdminClaimChunks x) {
            adminClaimChunks(s, x);
        } else if (i instanceof ClaimIntent.AdminUnclaimChunks x) {
            adminUnclaimChunks(s, x);
        } else if (i instanceof ClaimIntent.SetZoneChunks x) {
            zonePage(s, x.zoneOrdinal(), x.worldIdx(), x.keys(), 0, x.actor());
        } else if (i instanceof ClaimIntent.RemoveZoneChunk x) {
            removeZoneChunk(s, x);
        } else if (i instanceof ClaimIntent.UnclaimAllPage x) {
            unclaimAllPage(s, x.faction(), x.actor());
        } else if (i instanceof ClaimIntent.ZonePage x) {
            zonePage(s, x.zoneOrdinal(), x.worldIdx(), x.keys(), x.cursor(), x.actor());
        } else {
            throw new IllegalStateException("unhandled claim intent: " + i.getClass().getName());
        }
    }
    static void claimChunks(ReduceSupport s, ClaimIntent.ClaimChunks c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        int actorOrd = s.memberOrd(c.player());
        if (actorOrd < 0 || FactionHandle.ordinal(s.memberFactionHandle(actorOrd)) != f.idx()) {
            s.reject(ReasonCode.NOT_IN_FACTION);
            return;
        }
        int claimed = 0;
        ReasonCode firstErr = null;
        long[] keys = c.keys();
        for (long key : keys) {
            Faction cur = s.resolve(c.faction());
            ClaimRules.ClaimDecision dec = ClaimRules.validateClaim(s.state, c.faction(),
                    c.worldIdx(), key, cur.landCount(), s.tick, s.epochMillis);
            if (!dec.ok()) {
                if (firstErr == null) {
                    firstErr = dec.reason();
                }
                continue;
            }
            applyClaim(s, c.faction(), c.worldIdx(), key, dec.victimHandle());
            claimed++;
        }
        if (claimed == 0 && firstErr != null) {
            s.reject(firstErr);
        }
    }

    /** Applies one validated claim, maintaining atlas + reverse index + landCount + victim. */
    static void applyClaim(ReduceSupport s, int factionHandle, int worldIdx, long key, int victimHandle) {
        int prevOwner = s.state.claims().ownerAt(worldIdx, key);
        if (victimHandle != ReduceSupport.NO_HANDLE) {
            Faction victim = s.resolve(victimHandle);
            if (victim != null) {
                s.replaceFaction(FactionEdit.withLand(victim, victim.landCount() - 1,
                        victim.claims().remove(worldIdx, key)));
            }
            prevOwner = victimHandle;
        }
        s.state = s.state.withClaims(s.state.claims().withClaim(worldIdx, key, factionHandle));
        Faction f = s.resolve(factionHandle);
        s.replaceFaction(FactionEdit.withLand(f, f.landCount() + 1, f.claims().add(worldIdx, key)));
        s.effects.add(new ClaimEffect.ClaimSet(s.seq, s.origin, worldIdx, key, factionHandle, prevOwner));
    }

    static void unclaimChunks(ReduceSupport s, ClaimIntent.UnclaimChunks c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        int unclaimed = 0;
        ReasonCode firstErr = null;
        for (long key : c.keys()) {
            ReasonCode err = ClaimRules.validateUnclaim(s.state, c.faction(), c.worldIdx(), key);
            if (err != null) {
                if (firstErr == null) {
                    firstErr = err;
                }
                continue;
            }
            applyUnclaim(s, c.faction(), c.worldIdx(), key);
            unclaimed++;
        }
        if (unclaimed == 0 && firstErr != null) {
            s.reject(firstErr);
        }
    }

    static void applyUnclaim(ReduceSupport s, int factionHandle, int worldIdx, long key) {
        s.state = s.state.withClaims(s.state.claims().withoutClaim(worldIdx, key));
        Faction f = s.resolve(factionHandle);
        if (f != null) {
            s.replaceFaction(FactionEdit.withLand(f, f.landCount() - 1,
                    f.claims().remove(worldIdx, key)));
        }
        s.effects.add(new ClaimEffect.ClaimRemoved(s.seq, s.origin, worldIdx, key, factionHandle));
    }

    static void adminClaimChunks(ReduceSupport s, ClaimIntent.AdminClaimChunks c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        for (long key : c.keys()) {
            int existing = s.state.claims().ownerAt(c.worldIdx(), key);
            if (existing != ReduceSupport.NO_HANDLE) {
                // Admin claim only fills unclaimed land (parity); skip already-owned.
                continue;
            }
            applyClaim(s, c.faction(), c.worldIdx(), key, ReduceSupport.NO_HANDLE);
        }
    }

    static void adminUnclaimChunks(ReduceSupport s, ClaimIntent.AdminUnclaimChunks c) {
        for (long key : c.keys()) {
            int owner = s.state.claims().ownerAt(c.worldIdx(), key);
            if (owner == ReduceSupport.NO_HANDLE || FactionHandle.ordinal(owner) != FactionHandle.ordinal(c.faction())) {
                continue;
            }
            applyUnclaim(s, c.faction(), c.worldIdx(), key);
        }
    }

    static void unclaimAllPage(ReduceSupport s, int factionHandle, UUID actor) {
        Faction f = s.resolve(factionHandle);
        if (f == null) {
            return;
        }
        s.removeUpToPageClaims(f.idx(), FactionHandle.WILDERNESS);
        Faction after = s.resolve(factionHandle);
        if (after != null && after.landCount() > 0) {
            s.continuation(new ClaimIntent.UnclaimAllPage(factionHandle, 0, actor));
        }
    }

    static void zonePage(ReduceSupport s, int zoneOrdinal, int worldIdx, long[] keys, int cursor, UUID actor) {
        if (zoneOrdinal != FactionHandle.SAFEZONE_ORDINAL
                && zoneOrdinal != FactionHandle.WARZONE_ORDINAL) {
            s.reject(ReasonCode.FLAG_INVALID);
            return;
        }
        int zoneHandle = FactionHandle.handle(s.state.factions().generationAt(zoneOrdinal), zoneOrdinal);
        int end = Math.min(keys.length, cursor + Reducer.PAGE_SIZE);
        for (int i = cursor; i < end; i++) {
            setZoneChunk(s, zoneOrdinal, zoneHandle, worldIdx, keys[i]);
        }
        if (end < keys.length) {
            s.continuation(new ClaimIntent.ZonePage(zoneOrdinal, worldIdx, keys, end, actor));
        }
    }

    static void setZoneChunk(ReduceSupport s, int zoneOrdinal, int zoneHandle, int worldIdx, long key) {
        int prev = s.state.claims().ownerAt(worldIdx, key);
        if (prev == zoneHandle) {
            return;
        }
        if (prev != ReduceSupport.NO_HANDLE) {
            int prevOrd = FactionHandle.ordinal(prev);
            if (FactionHandle.isNormalOrdinal(prevOrd)) {
                Faction victim = s.resolve(prev);
                if (victim != null) {
                    s.replaceFaction(FactionEdit.withLand(victim, victim.landCount() - 1,
                            victim.claims().remove(worldIdx, key)));
                }
            } else {
                // moving from the other zone
                s.state = adjustZoneStats(s, prevOrd, -1);
            }
        }
        s.state = s.state.withClaims(s.state.claims().withClaim(worldIdx, key, zoneHandle));
        s.state = adjustZoneStats(s, zoneOrdinal, +1);
        s.effects.add(new ClaimEffect.ZoneSet(s.seq, s.origin, zoneOrdinal, worldIdx, key, prev));
    }

    static KernelState adjustZoneStats(ReduceSupport s, int zoneOrdinal, int delta) {
        ZoneStats z = s.state.zones();
        if (zoneOrdinal == FactionHandle.SAFEZONE_ORDINAL) {
            return s.state.withZones(z.withSafezoneDelta(delta));
        }
        if (zoneOrdinal == FactionHandle.WARZONE_ORDINAL) {
            return s.state.withZones(z.withWarzoneDelta(delta));
        }
        return s.state;
    }

    static void removeZoneChunk(ReduceSupport s, ClaimIntent.RemoveZoneChunk c) {
        // Only the SAFEZONE/WARZONE sentinels are zones; a normal ordinal here would strip an
        // atlas claim without decrementing that faction's landCount (aggregate desync).
        if (c.zoneOrdinal() != FactionHandle.SAFEZONE_ORDINAL
                && c.zoneOrdinal() != FactionHandle.WARZONE_ORDINAL) {
            s.reject(ReasonCode.FLAG_INVALID);
            return;
        }
        int owner = s.state.claims().ownerAt(c.worldIdx(), c.key());
        if (owner == ReduceSupport.NO_HANDLE || FactionHandle.ordinal(owner) != c.zoneOrdinal()) {
            return;
        }
        s.state = s.state.withClaims(s.state.claims().withoutClaim(c.worldIdx(), c.key()));
        s.state = adjustZoneStats(s, c.zoneOrdinal(), -1);
        s.effects.add(new ClaimEffect.ZoneRemoved(s.seq, s.origin, c.zoneOrdinal(), c.worldIdx(), c.key()));
    }
}
