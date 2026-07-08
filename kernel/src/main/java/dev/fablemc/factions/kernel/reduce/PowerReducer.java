package dev.fablemc.factions.kernel.reduce;

import java.util.UUID;

import dev.fablemc.factions.kernel.config.PowerConfig;
import dev.fablemc.factions.kernel.effect.ExternalEffect;
import dev.fablemc.factions.kernel.effect.PowerEffect;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.PowerIntent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.ClaimRules;
import dev.fablemc.factions.kernel.rules.FactionAggregates;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.rules.MoneyMath;
import dev.fablemc.factions.kernel.rules.PowerMath;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.vocab.PowerSource;

/**
 * Power intents: death loss and kill gain / power tick raidable recompute / buy / admin set-add-remove-reset / freeze.
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved this code unchanged).
 */
final class PowerReducer {

    private PowerReducer() {
    }

    static void reduce(ReduceSupport s, PowerIntent i) {
        if (i instanceof PowerIntent.RecordDeath x) {
            recordDeath(s, x);
        } else if (i instanceof PowerIntent.PowerTick x) {
            powerTick(s, x);
        } else if (i instanceof PowerIntent.BuyPower x) {
            buyPower(s, x);
        } else if (i instanceof PowerIntent.AdminPowerSet x) {
            adminPower(s, x.target(), PowerSource.ADMIN_SET, x.amount(), x.actor(), x.reason());
        } else if (i instanceof PowerIntent.AdminPowerAdd x) {
            adminPower(s, x.target(), PowerSource.ADMIN_ADD, x.amount(), x.actor(), x.reason());
        } else if (i instanceof PowerIntent.AdminPowerRemove x) {
            adminPower(s, x.target(), PowerSource.ADMIN_REMOVE, x.amount(), x.actor(), x.reason());
        } else if (i instanceof PowerIntent.AdminPowerReset x) {
            adminPower(s, x.target(), PowerSource.ADMIN_RESET, 0.0, x.actor(), x.reason());
        } else if (i instanceof PowerIntent.SetPowerFrozen x) {
            setPowerFrozen(s, x);
        } else {
            throw new IllegalStateException("unhandled power intent: " + i.getClass().getName());
        }
    }
    static PowerMath.PowerResult applyPower(ReduceSupport s, int ord, PowerSource source, double baseDelta,
                                             boolean bypassFreeze, int worldIdx, int zoneCtx,
                                             String reason) {
        boolean online = s.state.online().contains(ord);
        s.settleMember(ord, online);
        double before = s.state.ledger().powerBase(ord);
        boolean frozen = s.state.ledger().powerFrozen(ord);
        PowerMath.PowerResult r = PowerMath.apply(s.power(), s.baked(), before, frozen, source,
                baseDelta, bypassFreeze, worldIdx, zoneCtx, reason);
        if (r.changed()) {
            s.state = s.state.withLedger(s.state.ledger().withPower(ord, r.after(), s.tick));
        }
        return r;
    }

    static int zoneCtx(ReduceSupport s, int worldIdx, long key, int deadFactionHandle) {
        int owner = s.state.claims().ownerAt(worldIdx, key);
        if (owner == ReduceSupport.NO_HANDLE) {
            return PowerMath.ZONE_WILDERNESS;
        }
        int oOrd = FactionHandle.ordinal(owner);
        if (oOrd == FactionHandle.SAFEZONE_ORDINAL) {
            return PowerMath.ZONE_SAFEZONE;
        }
        if (oOrd == FactionHandle.WARZONE_ORDINAL) {
            return PowerMath.ZONE_WARZONE;
        }
        if (deadFactionHandle != ReduceSupport.NO_HANDLE && oOrd == FactionHandle.ordinal(deadFactionHandle)) {
            return PowerMath.ZONE_OWN_CLAIMED;
        }
        return PowerMath.ZONE_ENEMY_CLAIMED;
    }

    static void recordDeath(ReduceSupport s, PowerIntent.RecordDeath d) {
        PowerConfig pc = s.power();
        int deadOrd = s.memberOrd(d.dead());
        int deadFactionHandle = deadOrd >= 0 && s.state.ledger().has(deadOrd)
                ? s.memberFactionHandle(deadOrd) : ReduceSupport.NO_HANDLE;
        int zone = zoneCtx(s, d.worldIdx(), d.chunkKey(), deadFactionHandle);
        if (s.state.config().zones().safeZoneEnabled() && zone == PowerMath.ZONE_SAFEZONE) {
            return; // no power change in safezone
        }
        // Grace: the kernel has no server-start clock, so referenceStart = 0 (grace never
        // blocks — documented deviation). The formula itself is pinned in PowerMathTest.
        if (PowerMath.inGracePeriod(pc, s.epochMillis, 0L)) {
            return;
        }
        double victimBefore = 0.0;
        if (deadOrd >= 0 && s.state.ledger().has(deadOrd)) {
            int prevStreak = s.state.ledger().deathStreak(deadOrd);
            long lastDeath = s.state.ledger().lastDeathAt(deadOrd);
            int streak = PowerMath.nextStreak(pc, prevStreak, lastDeath, s.epochMillis);
            double loss = PowerMath.deathLoss(pc, streak);
            s.state = s.state.withLedger(s.state.ledger().withDeath(deadOrd, streak, s.epochMillis));
            PowerMath.PowerResult r = applyPower(s, deadOrd, PowerSource.DEATH, loss, false,
                    d.worldIdx(), zone, "DEATH");
            victimBefore = r.before();
            if (r.changed()) {
                s.emit(new PowerEffect.PowerChanged(s.seq, s.origin, d.dead(), r.before(), r.after(),
                        PowerSource.DEATH, r.reasonCode()));
            }
            s.emit(new PowerEffect.DeathStreakAdvanced(s.seq, s.origin, d.dead(), streak));
            if (streak > 0) {
                s.notify(d.dead(), "power.death-streak-penalty",
                        Integer.toString(streak + 1), s.fmt1(Math.abs(r.effectiveDelta())));
            } else {
                s.notify(d.dead(), "power.lost-on-death", s.fmt1(Math.abs(r.effectiveDelta())));
            }
        }
        UUID killer = d.killer();
        if (killer != null && pc.gainOnKillEnabled()) {
            int killerOrd = s.memberOrd(killer);
            if (killerOrd >= 0 && s.state.ledger().has(killerOrd)) {
                boolean online = s.state.online().contains(killerOrd);
                s.settleMember(killerOrd, online);
                double killerPower = s.state.ledger().powerBase(killerOrd);
                double gain = PowerMath.killGain(pc, victimBefore, killerPower);
                PowerMath.PowerResult kr = applyPower(s, killerOrd, PowerSource.KILL, gain, false,
                        d.worldIdx(), zone, "KILL");
                if (kr.changed()) {
                    s.emit(new PowerEffect.PowerChanged(s.seq, s.origin, killer, kr.before(),
                            kr.after(), PowerSource.KILL, kr.reasonCode()));
                    s.notify(killer, "power.kill-gained", s.fmt1(Math.abs(kr.effectiveDelta())));
                }
            }
        }
    }

    static void powerTick(ReduceSupport s, PowerIntent.PowerTick t) {
        // Lazy accrual means online members regen continuously via powerAt; the tick advances
        // the state tick (done in apply()) and recomputes raidable on the (here: all-normal)
        // faction set — the reference's O(dirty) raidable pass.
        recomputeRaidableAll(s);
    }

    static void recomputeRaidableAll(ReduceSupport s) {
        FactionArena arena = s.state.factions();
        int hw = arena.highWater();
        for (int ord = FactionHandle.FIRST_NORMAL_ORDINAL; ord < hw; ord++) {
            Faction f = arena.at(ord);
            if (f == null || !f.isNormal()) {
                continue;
            }
            double totalPower = FactionAggregates.totalPower(s.state, f, s.tick, s.epochMillis);
            int maxLand = ClaimRules.computeMaxLand(s.state.config().land(), totalPower);
            boolean nowRaidable = f.landCount() > maxLand;
            if (nowRaidable != f.raidable()) {
                Faction nf = FactionEdit.withRaidable(f, nowRaidable);
                s.state = s.state.withFactions(s.state.factions().replace(ord, nf));
                arena = s.state.factions();
                s.emit(new PowerEffect.RaidableChanged(s.seq, s.origin,
                        s.state.factions().handleOf(ord), nowRaidable));
                s.notifyFaction(s.state.factions().handleOf(ord),
                        nowRaidable ? "raidable.became-raidable" : "raidable.no-longer-raidable");
            }
        }
    }

    static void buyPower(ReduceSupport s, PowerIntent.BuyPower c) {
        PowerConfig pc = s.power();
        if (!pc.buyEnabled()) {
            s.reject(ReasonCode.POWER_BUY_DISABLED);
            return;
        }
        if (!(c.points() > 0.0) || c.points() > pc.buyMaxPerPurchase()) {
            s.reject(ReasonCode.POWER_BUY_INVALID_AMOUNT);
            return;
        }
        int ord = s.ensureMember(c.player(), "");
        PowerMath.PowerResult r = applyPower(s, ord, PowerSource.BUY, c.points(), false, -1,
                PowerMath.ZONE_WILDERNESS, "BUY");
        if (r.blockedByFreeze()) {
            s.reject(ReasonCode.POWER_FROZEN);
            s.emit(new ExternalEffect.EscrowRefund(s.seq, s.origin, c.escrowId(), c.player(), c.cost()));
            return;
        }
        double delivered = Math.max(0.0, r.effectiveDelta());
        if (r.changed()) {
            s.emit(new PowerEffect.PowerChanged(s.seq, s.origin, c.player(), r.before(), r.after(),
                    PowerSource.BUY, r.reasonCode()));
        }
        if (delivered + PowerMath.NO_CHANGE_EPSILON < c.points()) {
            double refund = MoneyMath.round2((c.points() - delivered) * pc.buyCostPerPoint());
            if (refund > 0.0) {
                s.emit(new ExternalEffect.EscrowRefund(s.seq, s.origin, c.escrowId(), c.player(), refund));
            }
        }
    }

    static void adminPower(ReduceSupport s, UUID target, PowerSource source, double amount, UUID actor, String reason) {
        int ord = s.ensureMember(target, "");
        double baseDelta;
        if (source == PowerSource.ADMIN_SET) {
            s.settleMember(ord, s.state.online().contains(ord));
            baseDelta = amount - s.state.ledger().powerBase(ord);
        } else if (source == PowerSource.ADMIN_REMOVE) {
            baseDelta = -Math.abs(amount);
        } else if (source == PowerSource.ADMIN_RESET) {
            s.settleMember(ord, s.state.online().contains(ord));
            baseDelta = s.power().maxPower() - s.state.ledger().powerBase(ord);
        } else { // ADMIN_ADD
            baseDelta = amount;
        }
        boolean bypass = s.power().freezeAllowAdminBypass();
        PowerMath.PowerResult r = applyPower(s, ord, source, baseDelta, bypass, -1,
                PowerMath.ZONE_WILDERNESS, source.sourceName()
                        + (reason == null || reason.isEmpty() ? "" : ":" + reason));
        if (r.changed()) {
            s.emit(new PowerEffect.PowerChanged(s.seq, s.origin, target, r.before(), r.after(),
                    source, r.reasonCode()));
        }
    }

    static void setPowerFrozen(ReduceSupport s, PowerIntent.SetPowerFrozen c) {
        int ord = s.ensureMember(c.target(), "");
        // Freeze toggle is an epoch boundary: settle before flipping the flag.
        s.settleMember(ord, s.state.online().contains(ord));
        s.state = s.state.withLedger(s.state.ledger().withPowerFrozen(ord, c.frozen()));
        s.emit(new PowerEffect.PowerFrozenChanged(s.seq, s.origin, c.target(), c.frozen()));
    }
}
