package dev.fablemc.factions.kernel.reduce;

import dev.fablemc.factions.kernel.effect.ChestEffect;
import dev.fablemc.factions.kernel.intent.ChestIntent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.ChestRules;
import dev.fablemc.factions.kernel.state.ChestRef;
import dev.fablemc.factions.kernel.state.Faction;

/**
 * Chest intents: create / delete / commit contents blob.
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved this code unchanged).
 */
final class ChestReducer {

    private ChestReducer() {
    }

    static void reduce(ReduceSupport s, ChestIntent i) {
        if (i instanceof ChestIntent.CreateChest x) {
            createChest(s, x);
        } else if (i instanceof ChestIntent.DeleteChest x) {
            deleteChest(s, x);
        } else if (i instanceof ChestIntent.CommitChestContents x) {
            commitChestContents(s, x);
        } else {
            throw new IllegalStateException("unhandled chest intent: " + i.getClass().getName());
        }
    }
    static void createChest(ReduceSupport s, ChestIntent.CreateChest c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        ReasonCode err = ChestRules.validateCreate(s.state.config(), s.state.chests(), f.idx(), c.name());
        if (err != null) {
            s.reject(err);
            return;
        }
        s.state = s.state.withChests(s.state.chests().set(f.idx(),
                new ChestRef(c.name(), ChestRef.EMPTY_BLOB, s.epochMillis)));
        s.emit(new ChestEffect.ChestCreated(s.seq, s.origin, c.faction(), c.name()));
    }

    static void deleteChest(ReduceSupport s, ChestIntent.DeleteChest c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        if (!ChestRules.exists(s.state.chests(), f.idx(), c.name())) {
            return;
        }
        s.state = s.state.withChests(s.state.chests().delete(f.idx(), c.name()));
        s.emit(new ChestEffect.ChestDeleted(s.seq, s.origin, c.faction(), c.name()));
    }

    static void commitChestContents(ReduceSupport s, ChestIntent.CommitChestContents c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        ChestRef existing = s.state.chests().get(f.idx(), c.name());
        if (existing == null) {
            return;
        }
        s.state = s.state.withChests(s.state.chests().set(f.idx(),
                new ChestRef(existing.name(), c.blobRef(), existing.createdAt())));
        s.emit(new ChestEffect.ChestContentsChanged(s.seq, s.origin, c.faction(), c.name(), c.blobRef()));
    }
}
