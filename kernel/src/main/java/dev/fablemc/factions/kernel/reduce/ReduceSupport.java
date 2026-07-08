package dev.fablemc.factions.kernel.reduce;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.UUID;

import dev.fablemc.factions.kernel.config.BakedTables;
import dev.fablemc.factions.kernel.config.PowerConfig;
import dev.fablemc.factions.kernel.effect.AuditEffect;
import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.ExternalEffect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.effect.MembershipEffect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.IntentEnvelope;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.rules.PowerMath;
import dev.fablemc.factions.kernel.state.EscrowTable;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelState;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.vocab.EscrowKind;
import dev.fablemc.factions.kernel.vocab.FactionAuditAction;
import dev.fablemc.factions.kernel.vocab.NotifyPredicate;

/**
 * Owns the reducer's per-intent working context: the effect buffer, the envelope rng, and the
 * common-lookup/guard helpers shared by every per-domain reducer (W25-REORG P2a). One instance exists per
 * {@link Reducer#apply} call and is never published while mutable.
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only. <b>Mutability:</b> confined,
 * single-threaded mutation of {@link #state} and the effect buffer during one {@code apply}
 * call; nondeterminism comes only from the envelope ({@code epochMillis}, {@code tick},
 * {@code rngSeed}). New UUIDs are drawn from a per-envelope {@link SplittableRandom} seeded by
 * {@code rngSeed} — never {@code UUID.randomUUID} — which makes replay byte-identical.
 *
 * <p><b>Emission and guards.</b> The effect buffer is private: every effect leaves through
 * {@link #emit} (or a named wrapper such as {@link #reject}, {@link #notify}, {@link #audit},
 * {@link #continuation}), so seq/origin stamping and ordering have exactly one shape. Domain
 * reducers use the standard guard idiom — {@code if (s.rejectIf(rule)) return;} for validator
 * results, {@code factionOrReject}/{@code memberOfOrReject} for lookups — so a rejection is
 * always a single expression at the call site.
 */
final class ReduceSupport {

    static final int NO_HANDLE = FactionHandle.WILDERNESS;
    static final String[] NO_ARGS = new String[0];

    KernelState state;
    final long seq;
    final long epochMillis;
    final int tick;
    final Origin origin;
    private final List<Effect> effects = new ArrayList<>(8);
    private SplittableRandom rng;
    private final long rngSeed;

    ReduceSupport(KernelState state, IntentEnvelope env) {
        this.state = state;
        this.seq = env.seq();
        this.epochMillis = env.epochMillis();
        this.tick = env.tick();
        this.origin = env.origin();
        this.rngSeed = env.rngSeed();
    }

    UUID newUuid() {
        if (rng == null) {
            rng = new SplittableRandom(rngSeed);
        }
        return new UUID(rng.nextLong(), rng.nextLong());
    }

    /** Appends {@code effect} to this intent's ordered effect list — the only way out. */
    void emit(Effect effect) {
        effects.add(effect);
    }

    /** The reducer's result: the state as mutated so far plus the ordered effects. */
    Reducer.Outcome outcome() {
        return new Reducer.Outcome(state, effects);
    }

    void reject(ReasonCode reason) {
        emit(new FeedbackEffect.Rejected(seq, origin, reason, NO_ARGS));
    }

    void reject(ReasonCode reason, String... args) {
        emit(new FeedbackEffect.Rejected(seq, origin, reason, args));
    }

    /**
     * Single-expression guard: emits a rejection when a rule returned a {@code ReasonCode}.
     * Call as {@code if (s.rejectIf(Rule.validate(...))) return;} — {@code true} means rejected.
     */
    boolean rejectIf(ReasonCode reason) {
        if (reason == null) {
            return false;
        }
        reject(reason);
        return true;
    }

    /** Resolves {@code handle} to a live faction, or emits FACTION_NOT_FOUND and returns null. */
    Faction factionOrReject(int handle) {
        Faction f = resolve(handle);
        if (f == null) {
            reject(ReasonCode.FACTION_NOT_FOUND);
        }
        return f;
    }

    /**
     * The member ordinal of {@code player} if that member currently belongs to faction ordinal
     * {@code factionOrd}; otherwise emits {@code ifNot} and returns {@code -1}.
     */
    int memberOfOrReject(UUID player, int factionOrd, ReasonCode ifNot) {
        int ord = memberOrd(player);
        if (ord < 0 || FactionHandle.ordinal(memberFactionHandle(ord)) != factionOrd) {
            reject(ifNot);
            return -1;
        }
        return ord;
    }

    void audit(int factionHandle, UUID actor, FactionAuditAction action, String detail) {
        emit(new AuditEffect.AuditRecorded(seq, origin, factionHandle, actor, action, detail));
    }

    void notify(UUID target, String key, String... args) {
        emit(new FeedbackEffect.Notify(seq, origin, target, MessageKey.of(key), args));
    }

    void notifyFaction(int factionHandle, String key, String... args) {
        emit(new FeedbackEffect.NotifyFaction(seq, origin, factionHandle, NotifyPredicate.MEMBERS_ALL,
                MessageKey.of(key), args));
    }

    void continuation(Intent next) {
        emit(new SystemEffect.ContinuationRequested(seq, origin, next));
    }

    PowerConfig power() {
        return state.config().power();
    }

    BakedTables baked() {
        return state.config().baked();
    }

    Faction resolve(int handle) {
        return state.factions().resolve(handle);
    }

    int memberOrd(UUID player) {
        return state.members().get(player);
    }

    /** Faction handle of a member ordinal, or WILDERNESS. */
    int memberFactionHandle(int ord) {
        return ord < 0 ? NO_HANDLE : state.ledger().factionHandle(ord);
    }

    /** Ensures a ledger row + directory mapping for {@code player}; returns the ordinal. */
    int ensureMember(UUID player, String name) {
        int ord = memberOrd(player);
        if (ord >= 0 && state.ledger().has(ord)) {
            return ord;
        }
        ord = state.ledger().nextOrdinal();
        state = state.withLedger(state.ledger().withNewMember(ord, player, name == null ? "" : name));
        state = state.withMembers(state.members().withMapping(player, ord));
        return ord;
    }

    void replaceFaction(Faction f) {
        state = state.withFactions(state.factions().replace(f.idx(), f));
    }

    Rank rankOf(Faction f, int memberOrdinal) {
        int idx = state.ledger().rankIdx(memberOrdinal);
        Rank[] ranks = f.ranks();
        return (idx >= 0 && idx < ranks.length) ? ranks[idx] : null;
    }

    String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    void settleMember(int ord, boolean online) {
        PowerConfig pc = power();
        double settled = PowerMath.settle(pc, online, state.ledger().powerBase(ord),
                state.ledger().powerAsOfTick(ord), state.ledger().powerFrozen(ord), tick);
        state = state.withLedger(state.ledger().withPower(ord, settled, tick));
    }

    List<long[]> collectFactionClaims(int factionOrd, int limit) {
        ArrayList<long[]> out = new ArrayList<>();
        state.claims().forEachClaim((worldIdx, chunkKey, ownerHandle) -> {
            if (out.size() < limit && FactionHandle.ordinal(ownerHandle) == factionOrd) {
                out.add(new long[] {worldIdx, chunkKey});
            }
        });
        return out;
    }

    /** Removes up to a page of {@code factionOrd}'s claims (via the atlas scan); returns count. */
    int removeUpToPageClaims(int factionOrd) {
        List<long[]> batch = collectFactionClaims(factionOrd, Reducer.PAGE_SIZE);
        for (long[] wk : batch) {
            int world = (int) wk[0];
            long key = wk[1];
            state = state.withClaims(state.claims().withoutClaim(world, key));
            Faction f = state.factions().at(factionOrd);
            if (f != null) {
                state = state.withFactions(state.factions().replace(factionOrd,
                        FactionEdit.withLand(f, f.landCount() - 1, f.claims().remove(world, key))));
            }
            emit(new ClaimEffect.ClaimRemoved(seq, origin, world, key,
                    state.factions().handleOf(factionOrd)));
        }
        return batch.size();
    }

    /** Reassigns up to a page of {@code factionOrd}'s claims to {@code newOwnerHandle}; returns count. */
    int reassignUpToPageClaims(int factionOrd, int newOwnerHandle, int newOwnerOrd) {
        List<long[]> batch = collectFactionClaims(factionOrd, Reducer.PAGE_SIZE);
        for (long[] wk : batch) {
            int world = (int) wk[0];
            long key = wk[1];
            state = state.withClaims(state.claims().withClaim(world, key, newOwnerHandle));
            Faction src = state.factions().at(factionOrd);
            if (src != null) {
                state = state.withFactions(state.factions().replace(factionOrd,
                        FactionEdit.withLand(src, src.landCount() - 1, src.claims().remove(world, key))));
            }
            Faction dst = state.factions().at(newOwnerOrd);
            if (dst != null) {
                state = state.withFactions(state.factions().replace(newOwnerOrd,
                        FactionEdit.withLand(dst, dst.landCount() + 1, dst.claims().add(world, key))));
            }
            emit(new ClaimEffect.ClaimSet(seq, origin, world, key, newOwnerHandle,
                    state.factions().handleOf(factionOrd)));
        }
        return batch.size();
    }

    /** Clears the faction of up to a page of members; returns how many were cleared. */
    int clearMembersPage(int factionOrd) {
        PlayerLedger ledger = state.ledger();
        int highWater = ledger.highWater();
        int cleared = 0;
        for (int i = 0; i < highWater && cleared < Reducer.PAGE_SIZE; i++) {
            if (ledger.has(i) && FactionHandle.ordinal(ledger.factionHandle(i)) == factionOrd) {
                UUID uuid = ledger.uuid(i);
                ledger = ledger.withFactionHandle(i, NO_HANDLE).withRankIdx(i, 0);
                emit(new MembershipEffect.MemberLeft(seq, origin,
                        state.factions().handleOf(factionOrd), uuid, false));
                cleared++;
            }
        }
        state = state.withLedger(ledger);
        return cleared;
    }

    int migrateMembersPage(int fromOrd, int toHandle, int toDefaultRankIdx) {
        PlayerLedger ledger = state.ledger();
        int highWater = ledger.highWater();
        int moved = 0;
        for (int i = 0; i < highWater && moved < Reducer.PAGE_SIZE; i++) {
            if (ledger.has(i) && FactionHandle.ordinal(ledger.factionHandle(i)) == fromOrd) {
                UUID uuid = ledger.uuid(i);
                ledger = ledger.withFactionHandle(i, toHandle).withRankIdx(i, toDefaultRankIdx)
                        .withJoinedAt(i, epochMillis);
                emit(new MembershipEffect.MemberJoined(seq, origin, toHandle, uuid));
                moved++;
            }
        }
        state = state.withLedger(ledger);
        return moved;
    }

    /**
     * Scrubs the disbanding/merging faction's open escrows (AM-6, AM-7). Only INBOUND sagas
     * (DEPOSIT/BUY — the player's money is parked pending a bank/power credit that will now never
     * land) are refunded to the wallet here. A WITHDRAW escrow is left OPEN: its payout is already
     * in flight to the player's wallet (PayoutRequested was emitted when it opened), so a wallet
     * refund here would pay the player twice — the classic disband-double-pay dupe. Its own
     * SettleEscrow closes it: OK simply removes the (now faction-less) row, and FAILED is refunded
     * to the wallet by the handle-checked {@code settleEscrow} path since the faction is gone.
     */
    void scrubFactionEscrows(int factionOrd) {
        EscrowTable escrows = state.escrows();
        ArrayList<EscrowTable.Escrow> toRefund = new ArrayList<>();
        escrows.forEach(e -> {
            if (e.factionOrdinal() == factionOrd && e.kind() != EscrowKind.WITHDRAW) {
                toRefund.add(e);
            }
        });
        for (EscrowTable.Escrow e : toRefund) {
            state = state.withEscrows(state.escrows().settle(e.id()));
            emit(new ExternalEffect.EscrowRefund(seq, origin, e.id(), e.player(), e.amount()));
        }
    }

    String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
