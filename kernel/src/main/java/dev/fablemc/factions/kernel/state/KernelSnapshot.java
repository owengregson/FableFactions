package dev.fablemc.factions.kernel.state;

import java.util.UUID;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.config.PowerConfig;
import dev.fablemc.factions.kernel.ids.FactionHandle;

/**
 * The one type every read path touches — a wait-free, allocation-light query facade over an
 * immutable {@link KernelState} (proposal-C §3.3). No array ever escapes: readers use these
 * typed query methods only.
 *
 * <p><b>Owning thread(s):</b> any reader thread, on whatever region/main thread an event fires.
 * <b>Mutability:</b> immutable — wraps an immutable state. <b>Reducer rule:</b> the wrapped
 * state is replaced whole by the writer; a handler takes one snapshot at entry and uses it for
 * the whole operation (no torn reads across a publish).
 *
 * <p>The load-bearing reads — {@link #claimOwnerAt}, {@link #relationBetween} — are zero
 * allocation and monomorphic (this is the single concrete snapshot class, so the JIT inlines the
 * whole verdict). {@link #powerAt} settles the lazy-accrual power model (§4.5).
 */
public record KernelSnapshot(KernelState state) {

    /** Owning faction handle at a chunk, or {@link FactionHandle#WILDERNESS}. Zero allocation. */
    public int claimOwnerAt(int worldIdx, long chunkKey) {
        return state.claims().ownerAt(worldIdx, chunkKey);
    }

    /** Faction for a generation-tagged handle, or {@code null} if stale/absent. */
    public Faction faction(int handle) {
        return state.factions().resolve(handle);
    }

    /** Faction whose folded name equals {@code nameFolded}, or {@code null}. */
    public Faction factionByName(String nameFolded) {
        int ord = state.factionNames().ordinalOf(nameFolded);
        if (ord == NameIndex.ABSENT) {
            return null;
        }
        return state.factions().at(ord);
    }

    /** Member ordinal for {@code player}, or {@code -1} if unknown. Zero allocation. */
    public int memberOrdinal(UUID player) {
        return state.members().get(player);
    }

    /** Flyweight view of a member ordinal, or {@code null} if absent. Never store the result. */
    public MemberView member(int memberOrdinal) {
        return state.ledger().view(memberOrdinal);
    }

    /**
     * Effective relation kind (a {@link RelationKind} byte) between two faction handles.
     * Same faction ⇒ {@link RelationKind#MEMBER}; no edge / stale handle ⇒ NEUTRAL. Zero
     * allocation — the relation hot path (proposal-C §5b).
     */
    public byte relationBetween(int handleA, int handleB) {
        if (handleA == handleB) {
            return RelationKind.MEMBER;
        }
        Faction a = state.factions().resolve(handleA);
        if (a == null) {
            return RelationKind.NEUTRAL;
        }
        int ordB = FactionHandle.ordinal(handleB);
        if (a.idx() == ordB) {
            return RelationKind.MEMBER;
        }
        return a.relationEffective(ordB);
    }

    /**
     * Settled power for a member at {@code tick} via lazy accrual
     * ({@code clamp(base + rate*(tick - powerAsOfTick), min, max)}, proposal-C §4.5). The regen
     * rate is the online or offline source amount from {@link ConfigImage}, gated by the
     * source-enabled and freeze flags. For a constant rate over a settled base within
     * {@code [min, max]}, this single-clamp is identical to iterating the per-tick clamp — the
     * property the equivalence test pins.
     */
    public double powerAt(int memberOrdinal, int tick) {
        PlayerLedger ledger = state.ledger();
        if (!ledger.has(memberOrdinal)) {
            return 0.0;
        }
        PowerConfig pc = state.config().power();
        double min = pc.minPower();
        double max = pc.maxPower();
        double base = ledger.powerBase(memberOrdinal);
        double clampedBase = clamp(base, min, max);

        if (ledger.powerFrozen(memberOrdinal) && pc.freezeBlocksRegen()) {
            return clampedBase;
        }
        boolean online = state.online().contains(memberOrdinal);
        boolean srcEnabled = online ? pc.sourceRegenOnlineEnabled() : pc.sourceRegenOfflineEnabled();
        if (!srcEnabled) {
            return clampedBase;
        }
        long dt = (long) tick - ledger.powerAsOfTick(memberOrdinal);
        if (dt <= 0) {
            return clampedBase;
        }
        double rate = online ? pc.sourceRegenOnlineAmount() : pc.sourceRegenOfflineAmount();
        return clamp(base + rate * dt, min, max);
    }

    /** The current configuration image (config is state). */
    public ConfigImage config() {
        return state.config();
    }

    /** The monotonically increasing state version (one bump per published batch). */
    public long version() {
        return state.version();
    }

    /** The current power tick. */
    public int tick() {
        return state.tick();
    }

    private static double clamp(double v, double min, double max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }
}
