package dev.fablemc.factions.kernel.reduce;

import java.util.UUID;

import dev.fablemc.factions.kernel.effect.RoleEffect;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.RoleIntent;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.FactionEdit;
import dev.fablemc.factions.kernel.rules.RoleRules;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.kernel.state.Rank;
import dev.fablemc.factions.kernel.vocab.FactionAuditAction;

/**
 * Role and rank intents: promote / demote / custom role create-rename-reprioritize-prefix-delete-assign.
 *
 * <p><b>Owning thread:</b> the {@code fable-kernel} writer only (via {@link Reducer#apply}).
 * <b>Mutability:</b> pure static functions over a confined {@link ReduceSupport} context; no
 * shared mutable state, no IO, no clock, no Bukkit. Behavior is byte-identical to the pre-split
 * monolithic {@code Reducer} (W25-REORG P2a moved this code unchanged).
 */
final class RoleReducer {

    private RoleReducer() {
    }

    static void reduce(ReduceSupport s, RoleIntent i) {
        if (i instanceof RoleIntent.PromoteMember x) {
            changeRank(s, x.faction(), x.actor(), x.target(), true);
        } else if (i instanceof RoleIntent.DemoteMember x) {
            changeRank(s, x.faction(), x.actor(), x.target(), false);
        } else if (i instanceof RoleIntent.CreateRole x) {
            createRole(s, x);
        } else if (i instanceof RoleIntent.RenameRole x) {
            renameRole(s, x);
        } else if (i instanceof RoleIntent.SetRolePriority x) {
            setRolePriority(s, x);
        } else if (i instanceof RoleIntent.SetRolePrefix x) {
            setRolePrefix(s, x);
        } else if (i instanceof RoleIntent.DeleteRole x) {
            deleteRole(s, x);
        } else if (i instanceof RoleIntent.AssignRole x) {
            assignRole(s, x);
        } else {
            throw new IllegalStateException("unhandled role intent: " + i.getClass().getName());
        }
    }
    static void changeRank(ReduceSupport s, int factionHandle, UUID actor, UUID target, boolean promote) {
        Faction f = s.resolve(factionHandle);
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        int actorOrd = s.memberOrd(actor);
        int targetOrd = s.memberOrd(target);
        if (actorOrd < 0 || FactionHandle.ordinal(s.memberFactionHandle(actorOrd)) != f.idx()) {
            s.reject(ReasonCode.NOT_IN_FACTION);
            return;
        }
        if (targetOrd < 0 || FactionHandle.ordinal(s.memberFactionHandle(targetOrd)) != f.idx()) {
            s.reject(ReasonCode.NOT_MEMBER);
            return;
        }
        Rank actorRank = s.rankOf(f, actorOrd);
        Rank targetRank = s.rankOf(f, targetOrd);
        if (!RoleRules.canManage(actorRank, targetRank)) {
            s.reject(ReasonCode.MUST_BE_OFFICER);
            return;
        }
        int newIdx = RoleRules.stepRank(f.ranks(), s.state.ledger().rankIdx(targetOrd), promote);
        if (newIdx < 0 || !RoleRules.canManage(actorRank, f.ranks()[newIdx])) {
            s.reject(ReasonCode.ROLE_FAILED);
            return;
        }
        s.state = s.state.withLedger(s.state.ledger().withRankIdx(targetOrd, newIdx));
        s.emit(new RoleEffect.RankChanged(s.seq, s.origin, factionHandle, target, newIdx));
        s.audit(factionHandle, actor,
                promote ? FactionAuditAction.MEMBER_PROMOTE : FactionAuditAction.MEMBER_DEMOTE,
                target.toString());
    }

    static void createRole(ReduceSupport s, RoleIntent.CreateRole c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        if (!s.state.config().role().customEnabled()) {
            s.reject(ReasonCode.ROLE_FEATURE_DISABLED);
            return;
        }
        if (Rank.isProtectedBuiltin(c.name()) || RoleRules.indexByName(f.ranks(), c.name()) >= 0) {
            s.reject(ReasonCode.ROLE_NAME_TAKEN);
            return;
        }
        if (!RoleRules.validCustomPriority(s.state.config().role(), c.priority())) {
            s.reject(ReasonCode.ROLE_PRIORITY_OUT_OF_RANGE);
            return;
        }
        int actorOrd = s.memberOrd(c.actor());
        Rank actorRank = actorOrd >= 0 ? s.rankOf(f, actorOrd) : null;
        if (actorRank == null || actorRank.priority() <= c.priority()) {
            s.reject(ReasonCode.ROLE_ACTOR_RANK_INSUFFICIENT);
            return;
        }
        if (!RoleRules.withinCustomRoleLimit(s.state.config().role(), f.ranks())) {
            s.reject(ReasonCode.ROLE_LIMIT_REACHED);
            return;
        }
        String prefix = c.prefix();
        if (prefix != null && !prefix.isEmpty() && !s.state.config().role().prefixesEnabled()) {
            s.reject(ReasonCode.ROLE_PREFIX_DISABLED);
            return;
        }
        String roleId = s.newUuid().toString();
        Rank[] ranks = append(f.ranks(), new Rank(roleId, c.name(), s.emptyToNull(prefix), c.priority()));
        s.replaceFaction(FactionEdit.withRanks(f, ranks));
        s.emit(new RoleEffect.RoleCreated(s.seq, s.origin, c.faction(), roleId, c.name(), c.priority()));
        s.audit(c.faction(), c.actor(), FactionAuditAction.ROLE_CREATE, c.name());
    }

    static void renameRole(ReduceSupport s, RoleIntent.RenameRole c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        if (!s.state.config().role().overridesEnabled() && !s.state.config().role().customEnabled()) {
            s.reject(ReasonCode.ROLE_FEATURE_DISABLED);
            return;
        }
        int idx = RoleRules.indexByName(f.ranks(), c.oldName());
        if (idx < 0 || Rank.isProtectedBuiltin(c.oldName())) {
            s.reject(ReasonCode.ROLE_FAILED);
            return;
        }
        if (RoleRules.indexByName(f.ranks(), c.newName()) >= 0) {
            s.reject(ReasonCode.ROLE_NAME_TAKEN);
            return;
        }
        Rank[] ranks = f.ranks().clone();
        Rank old = ranks[idx];
        ranks[idx] = new Rank(old.id(), c.newName(), old.prefix(), old.priority());
        s.replaceFaction(FactionEdit.withRanks(f, ranks));
        s.emit(new RoleEffect.RoleRenamed(s.seq, s.origin, c.faction(), old.id(), c.oldName(),
                c.newName()));
        s.audit(c.faction(), c.actor(), FactionAuditAction.ROLE_RENAME, c.newName());
    }

    static void setRolePriority(ReduceSupport s, RoleIntent.SetRolePriority c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        int idx = RoleRules.indexByName(f.ranks(), c.roleName());
        if (idx < 0 || Rank.isProtectedBuiltin(c.roleName())) {
            s.reject(ReasonCode.ROLE_FAILED);
            return;
        }
        if (!RoleRules.validCustomPriority(s.state.config().role(), c.priority())) {
            s.reject(ReasonCode.ROLE_PRIORITY_OUT_OF_RANGE);
            return;
        }
        Rank[] ranks = f.ranks().clone();
        Rank old = ranks[idx];
        ranks[idx] = new Rank(old.id(), old.name(), old.prefix(), c.priority());
        s.replaceFaction(FactionEdit.withRanks(f, ranks));
        s.emit(new RoleEffect.RoleRePrioritized(s.seq, s.origin, c.faction(), old.id(), c.priority()));
        s.audit(c.faction(), c.actor(), FactionAuditAction.ROLE_PRIORITY_SET, c.roleName());
    }

    static void setRolePrefix(ReduceSupport s, RoleIntent.SetRolePrefix c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        if (!s.state.config().role().prefixesEnabled()) {
            s.reject(ReasonCode.ROLE_PREFIX_DISABLED);
            return;
        }
        int idx = RoleRules.indexByName(f.ranks(), c.roleName());
        if (idx < 0) {
            s.reject(ReasonCode.ROLE_FAILED);
            return;
        }
        int maxLen = s.state.config().role().maxPrefixLength();
        String prefix = s.emptyToNull(c.prefix());
        if (prefix != null && maxLen > 0 && prefix.length() > maxLen) {
            s.reject(ReasonCode.ROLE_FAILED);
            return;
        }
        Rank[] ranks = f.ranks().clone();
        Rank old = ranks[idx];
        ranks[idx] = new Rank(old.id(), old.name(), prefix, old.priority());
        s.replaceFaction(FactionEdit.withRanks(f, ranks));
        s.emit(new RoleEffect.RolePrefixSet(s.seq, s.origin, c.faction(), old.id(),
                prefix == null ? "" : prefix));
        s.audit(c.faction(), c.actor(), FactionAuditAction.ROLE_PREFIX_SET, c.roleName());
    }

    static void deleteRole(ReduceSupport s, RoleIntent.DeleteRole c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        int idx = RoleRules.indexByName(f.ranks(), c.roleName());
        if (idx < 0 || Rank.isProtectedBuiltin(c.roleName())) {
            s.reject(ReasonCode.ROLE_FAILED);
            return;
        }
        // Refuse if any member currently uses the rank.
        if (rankInUse(s, f.idx(), idx)) {
            s.reject(ReasonCode.ROLE_FAILED);
            return;
        }
        String roleId = f.ranks()[idx].id();
        Rank[] ranks = removeAt(f.ranks(), idx);
        // Shift down any member rankIdx above the removed slot.
        reindexRanksAfterRemoval(s, f.idx(), idx);
        s.replaceFaction(FactionEdit.withRanks(f, ranks));
        s.emit(new RoleEffect.RoleDeleted(s.seq, s.origin, c.faction(), roleId));
        s.audit(c.faction(), c.actor(), FactionAuditAction.ROLE_DELETE, c.roleName());
    }

    static void assignRole(ReduceSupport s, RoleIntent.AssignRole c) {
        Faction f = s.resolve(c.faction());
        if (f == null) {
            s.reject(ReasonCode.FACTION_NOT_FOUND);
            return;
        }
        int targetOrd = s.memberOrd(c.target());
        if (targetOrd < 0 || FactionHandle.ordinal(s.memberFactionHandle(targetOrd)) != f.idx()) {
            s.reject(ReasonCode.NOT_MEMBER);
            return;
        }
        int roleIdx = RoleRules.indexByName(f.ranks(), c.roleName());
        if (roleIdx < 0) {
            s.reject(ReasonCode.ROLE_FAILED);
            return;
        }
        int actorOrd = s.memberOrd(c.actor());
        Rank actorRank = actorOrd >= 0 ? s.rankOf(f, actorOrd) : null;
        if (!RoleRules.canManage(actorRank, f.ranks()[roleIdx])) {
            s.reject(ReasonCode.ROLE_ACTOR_RANK_INSUFFICIENT);
            return;
        }
        s.state = s.state.withLedger(s.state.ledger().withRankIdx(targetOrd, roleIdx));
        s.emit(new RoleEffect.RoleAssigned(s.seq, s.origin, c.faction(), c.target(),
                f.ranks()[roleIdx].id()));
        s.audit(c.faction(), c.actor(), FactionAuditAction.ROLE_ASSIGN, c.target().toString());
    }

    static boolean rankInUse(ReduceSupport s, int factionOrd, int rankIdx) {
        PlayerLedger l = s.state.ledger();
        int hw = l.highWater();
        for (int i = 0; i < hw; i++) {
            if (l.has(i) && FactionHandle.ordinal(l.factionHandle(i)) == factionOrd
                    && l.rankIdx(i) == rankIdx) {
                return true;
            }
        }
        return false;
    }

    static void reindexRanksAfterRemoval(ReduceSupport s, int factionOrd, int removedIdx) {
        PlayerLedger l = s.state.ledger();
        int hw = l.highWater();
        for (int i = 0; i < hw; i++) {
            if (l.has(i) && FactionHandle.ordinal(l.factionHandle(i)) == factionOrd
                    && l.rankIdx(i) > removedIdx) {
                l = l.withRankIdx(i, l.rankIdx(i) - 1);
            }
        }
        s.state = s.state.withLedger(l);
    }

    static Rank[] append(Rank[] ranks, Rank r) {
        Rank[] out = new Rank[ranks.length + 1];
        System.arraycopy(ranks, 0, out, 0, ranks.length);
        out[ranks.length] = r;
        return out;
    }

    static Rank[] removeAt(Rank[] ranks, int idx) {
        Rank[] out = new Rank[ranks.length - 1];
        System.arraycopy(ranks, 0, out, 0, idx);
        System.arraycopy(ranks, idx + 1, out, idx, ranks.length - idx - 1);
        return out;
    }
}
