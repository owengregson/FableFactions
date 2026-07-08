package dev.fablemc.factions.kernel.reduce;

import dev.fablemc.factions.kernel.effect.TravelEffect;
import dev.fablemc.factions.kernel.intent.TravelIntent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.rules.TravelRules;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.Home;
import dev.fablemc.factions.kernel.state.Warp;

/**
 * Travel intents: faction home set-unset / warp set-delete-password-cost.
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved this code unchanged).
 */
final class TravelReducer {

    private TravelReducer() {
    }

    static void reduce(ReduceSupport s, TravelIntent i) {
        if (i instanceof TravelIntent.SetHome x) {
            setHome(s, x);
        } else if (i instanceof TravelIntent.UnsetHome x) {
            unsetHome(s, x);
        } else if (i instanceof TravelIntent.SetWarp x) {
            setWarp(s, x);
        } else if (i instanceof TravelIntent.DeleteWarp x) {
            deleteWarp(s, x);
        } else if (i instanceof TravelIntent.SetWarpPassword x) {
            setWarpPassword(s, x);
        } else if (i instanceof TravelIntent.SetWarpCost x) {
            setWarpCost(s, x);
        } else {
            throw new IllegalStateException("unhandled travel intent: " + i.getClass().getName());
        }
    }
    static void setHome(ReduceSupport s, TravelIntent.SetHome c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        s.replaceFaction(FactionEdit.withHome(f,
                new Home(c.worldIdx(), c.x(), c.y(), c.z(), c.yaw(), c.pitch())));
        s.emit(new TravelEffect.HomeSet(s.seq, s.origin, c.faction()));
    }

    static void unsetHome(ReduceSupport s, TravelIntent.UnsetHome c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        s.replaceFaction(FactionEdit.withHome(f, null));
        s.emit(new TravelEffect.HomeCleared(s.seq, s.origin, c.faction()));
    }

    static void setWarp(ReduceSupport s, TravelIntent.SetWarp c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        ReasonCode err = TravelRules.validateSetWarp(s.state.config(), s.state.warps(), f.idx(), c.name());
        if (err != null) {
            s.reject(err);
            return;
        }
        Warp existing = s.state.warps().get(f.idx(), c.name());
        long createdAt = existing == null ? s.epochMillis : existing.createdAt();
        String password = existing == null ? null : existing.password();
        double useCost = existing == null ? 0.0 : existing.useCost();
        Warp w = new Warp(c.name(), c.worldIdx(), c.x(), c.y(), c.z(), c.yaw(), c.pitch(),
                c.creator(), createdAt, password, useCost);
        s.state = s.state.withWarps(s.state.warps().set(f.idx(), w));
        s.emit(new TravelEffect.WarpSet(s.seq, s.origin, c.faction(), c.name()));
    }

    static void deleteWarp(ReduceSupport s, TravelIntent.DeleteWarp c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        if (TravelRules.requireWarp(s.state.warps(), f.idx(), c.name()) != null) {
            s.reject(ReasonCode.WARP_NOT_FOUND);
            return;
        }
        s.state = s.state.withWarps(s.state.warps().delete(f.idx(), c.name()));
        s.emit(new TravelEffect.WarpDeleted(s.seq, s.origin, c.faction(), c.name()));
    }

    static void setWarpPassword(ReduceSupport s, TravelIntent.SetWarpPassword c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        Warp w = s.state.warps().get(f.idx(), c.name());
        if (w == null) {
            s.reject(ReasonCode.WARP_NOT_FOUND);
            return;
        }
        String pw = (c.password() == null || c.password().isEmpty()) ? null : c.password();
        Warp nw = new Warp(w.name(), w.worldIdx(), w.x(), w.y(), w.z(), w.yaw(), w.pitch(),
                w.creator(), w.createdAt(), pw, w.useCost());
        s.state = s.state.withWarps(s.state.warps().set(f.idx(), nw));
        s.emit(new TravelEffect.WarpPasswordSet(s.seq, s.origin, c.faction(), c.name(), pw == null));
    }

    static void setWarpCost(ReduceSupport s, TravelIntent.SetWarpCost c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        Warp w = s.state.warps().get(f.idx(), c.name());
        if (w == null) {
            s.reject(ReasonCode.WARP_NOT_FOUND);
            return;
        }
        double cost = TravelRules.clampCost(c.cost());
        Warp nw = new Warp(w.name(), w.worldIdx(), w.x(), w.y(), w.z(), w.yaw(), w.pitch(),
                w.creator(), w.createdAt(), w.password(), cost);
        s.state = s.state.withWarps(s.state.warps().set(f.idx(), nw));
        s.emit(new TravelEffect.WarpCostSet(s.seq, s.origin, c.faction(), c.name(), cost));
    }
}
