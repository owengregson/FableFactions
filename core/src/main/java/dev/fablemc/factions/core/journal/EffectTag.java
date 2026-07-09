package dev.fablemc.factions.core.journal;

import java.util.HashMap;
import java.util.Map;

import dev.fablemc.factions.kernel.effect.AuditEffect;
import dev.fablemc.factions.kernel.effect.ChestEffect;
import dev.fablemc.factions.kernel.effect.ClaimEffect;
import dev.fablemc.factions.kernel.effect.EconomyEffect;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.ExternalEffect;
import dev.fablemc.factions.kernel.effect.FeedbackEffect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.effect.MembershipEffect;
import dev.fablemc.factions.kernel.effect.PowerEffect;
import dev.fablemc.factions.kernel.effect.PrefEffect;
import dev.fablemc.factions.kernel.effect.RelationEffect;
import dev.fablemc.factions.kernel.effect.RoleEffect;
import dev.fablemc.factions.kernel.effect.SessionEffect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.effect.TravelEffect;

/**
 * The stable, never-reused {@code u16} tag for every journaled {@link Effect} record — the
 * enum-first vocabulary for the WAL framing's {@code type} field (W25-REORG §P2b). Codes are
 * grouped in per-domain ranges so a new effect gets a code next to its siblings and the wire
 * format stays self-describing:
 *
 * <ul>
 *   <li>{@code 0x0100} lifecycle, {@code 0x0200} membership, {@code 0x0300} role,
 *       {@code 0x0400} claim, {@code 0x0500} relation, {@code 0x0600} power,
 *       {@code 0x0700} economy, {@code 0x0800} travel, {@code 0x0900} chest,
 *       {@code 0x0A00} pref, {@code 0x0B00} session, {@code 0x0C00} audit/system,
 *       {@code 0x0D00} feedback, {@code 0x0E00} external.</li>
 * </ul>
 *
 * <p>These codes were renumbered from the pre-beta sequential registry (1..62) into these ranges
 * per W25-REORG §P2b: no journals had been deployed, and no journal-format test pins the old tag
 * numbers, so the renumbering is safe. The {@link #code()} is what the framing persists; it is a
 * hard compatibility surface once beta ships — never renumber, append only.
 *
 * <p>{@link SystemEffect.ContinuationRequested} is the sole permitted {@link Effect} subtype that
 * carries no tag: it is a pipeline control effect stripped by the writer before fan-out, so it
 * never reaches the journal (see {@link JournalCodec#CONTROL_EFFECTS}).
 *
 * <p><b>Owning thread(s):</b> stateless enum; read on the writer (encode) and the storage/replay
 * thread (decode). <b>Mutability:</b> immutable; the lookup tables are shared immutable maps.
 */
public enum EffectTag {

    // ── lifecycle (0x0100) ──────────────────────────────────────────────────────────────────
    FACTION_CREATED(0x0101, LifecycleEffect.FactionCreated.class),
    FACTION_DISBANDED(0x0102, LifecycleEffect.FactionDisbanded.class),
    FACTION_RENAMED(0x0103, LifecycleEffect.FactionRenamed.class),
    DESCRIPTION_CHANGED(0x0104, LifecycleEffect.DescriptionChanged.class),
    MOTD_CHANGED(0x0105, LifecycleEffect.MotdChanged.class),
    OWNERSHIP_TRANSFERRED(0x0106, LifecycleEffect.OwnershipTransferred.class),
    MERGE_REQUESTED(0x0107, LifecycleEffect.MergeRequested.class),
    MERGE_COMPLETED(0x0108, LifecycleEffect.MergeCompleted.class),

    // ── membership (0x0200) ─────────────────────────────────────────────────────────────────
    MEMBER_JOINED(0x0201, MembershipEffect.MemberJoined.class),
    MEMBER_LEFT(0x0202, MembershipEffect.MemberLeft.class),
    INVITE_CREATED(0x0203, MembershipEffect.InviteCreated.class),
    INVITE_REMOVED(0x0204, MembershipEffect.InviteRemoved.class),

    // ── role (0x0300) ───────────────────────────────────────────────────────────────────────
    RANK_CHANGED(0x0301, RoleEffect.RankChanged.class),
    ROLE_CREATED(0x0302, RoleEffect.RoleCreated.class),
    ROLE_RENAMED(0x0303, RoleEffect.RoleRenamed.class),
    ROLE_REPRIORITIZED(0x0304, RoleEffect.RoleRePrioritized.class),
    ROLE_PREFIX_SET(0x0305, RoleEffect.RolePrefixSet.class),
    ROLE_DELETED(0x0306, RoleEffect.RoleDeleted.class),
    ROLE_ASSIGNED(0x0307, RoleEffect.RoleAssigned.class),

    // ── claim (0x0400) ──────────────────────────────────────────────────────────────────────
    CLAIM_SET(0x0401, ClaimEffect.ClaimSet.class),
    CLAIM_REMOVED(0x0402, ClaimEffect.ClaimRemoved.class),
    ZONE_SET(0x0403, ClaimEffect.ZoneSet.class),
    ZONE_REMOVED(0x0404, ClaimEffect.ZoneRemoved.class),

    // ── relation (0x0500) ───────────────────────────────────────────────────────────────────
    RELATION_DECLARED(0x0501, RelationEffect.RelationDeclared.class),
    RELATION_EFFECTIVE(0x0502, RelationEffect.RelationEffective.class),

    // ── power (0x0600) ──────────────────────────────────────────────────────────────────────
    POWER_CHANGED(0x0601, PowerEffect.PowerChanged.class),
    POWER_FROZEN_CHANGED(0x0602, PowerEffect.PowerFrozenChanged.class),
    DEATH_STREAK_ADVANCED(0x0603, PowerEffect.DeathStreakAdvanced.class),
    RAIDABLE_CHANGED(0x0604, PowerEffect.RaidableChanged.class),

    // ── economy (0x0700) ────────────────────────────────────────────────────────────────────
    BANK_CHANGED(0x0701, EconomyEffect.BankChanged.class),
    TAX_CHARGED(0x0702, EconomyEffect.TaxCharged.class),

    // ── travel (0x0800) ─────────────────────────────────────────────────────────────────────
    HOME_SET(0x0801, TravelEffect.HomeSet.class),
    HOME_CLEARED(0x0802, TravelEffect.HomeCleared.class),
    WARP_SET(0x0803, TravelEffect.WarpSet.class),
    WARP_DELETED(0x0804, TravelEffect.WarpDeleted.class),
    WARP_PASSWORD_SET(0x0805, TravelEffect.WarpPasswordSet.class),
    WARP_COST_SET(0x0806, TravelEffect.WarpCostSet.class),

    // ── chest (0x0900) ──────────────────────────────────────────────────────────────────────
    CHEST_CREATED(0x0901, ChestEffect.ChestCreated.class),
    CHEST_DELETED(0x0902, ChestEffect.ChestDeleted.class),
    CHEST_CONTENTS_CHANGED(0x0903, ChestEffect.ChestContentsChanged.class),

    // ── pref (0x0A00) ───────────────────────────────────────────────────────────────────────
    FLAG_CHANGED(0x0A01, PrefEffect.FlagChanged.class),
    PREF_CHANGED(0x0A02, PrefEffect.PrefChanged.class),
    LOCALE_CHANGED(0x0A03, PrefEffect.LocaleChanged.class),
    AUTO_MODE_CHANGED(0x0A04, PrefEffect.AutoModeChanged.class),
    FLY_CHANGED(0x0A05, PrefEffect.FlyChanged.class),
    OVERRIDE_CHANGED(0x0A06, PrefEffect.OverrideChanged.class),
    SHIELD_CHANGED(0x0A07, PrefEffect.ShieldChanged.class),

    // ── session (0x0B00) ────────────────────────────────────────────────────────────────────
    SESSION_STARTED(0x0B01, SessionEffect.SessionStarted.class),
    SESSION_ENDED(0x0B02, SessionEffect.SessionEnded.class),
    INBOX_QUEUED(0x0B03, SessionEffect.InboxQueued.class),
    INBOX_DELIVERED(0x0B04, SessionEffect.InboxDelivered.class),

    // ── audit / system (0x0C00) ─────────────────────────────────────────────────────────────
    AUDIT_RECORDED(0x0C01, AuditEffect.AuditRecorded.class),
    CONFIG_SWAPPED(0x0C02, SystemEffect.ConfigSwapped.class),
    AGGREGATE_DRIFT_DETECTED(0x0C03, SystemEffect.AggregateDriftDetected.class),

    // ── feedback (0x0D00) ───────────────────────────────────────────────────────────────────
    NOTIFY(0x0D01, FeedbackEffect.Notify.class),
    NOTIFY_FACTION(0x0D02, FeedbackEffect.NotifyFaction.class),
    BROADCAST(0x0D03, FeedbackEffect.Broadcast.class),
    REJECTED(0x0D04, FeedbackEffect.Rejected.class),

    // ── external (0x0E00) ───────────────────────────────────────────────────────────────────
    PAYOUT_REQUESTED(0x0E01, ExternalEffect.PayoutRequested.class),
    ESCROW_REFUND(0x0E02, ExternalEffect.EscrowRefund.class),
    WG_REGION_UPSERT(0x0E03, ExternalEffect.WgRegionUpsert.class),
    WG_REGION_REMOVE(0x0E04, ExternalEffect.WgRegionRemove.class),
    LWC_PURGE_REQUESTED(0x0E05, ExternalEffect.LwcPurgeRequested.class);

    /**
     * The journaled effect domains, one per {@code 0xNN00} tag range. The framing dispatches
     * encode/decode to a per-domain codec (in {@code journal.codec}) by this domain, so no single
     * class carries the whole effect vocabulary.
     */
    public enum Domain {
        LIFECYCLE(0x0100), MEMBERSHIP(0x0200), ROLE(0x0300), CLAIM(0x0400),
        RELATION(0x0500), POWER(0x0600), ECONOMY(0x0700), TRAVEL(0x0800),
        CHEST(0x0900), PREF(0x0A00), SESSION(0x0B00), SYSTEM(0x0C00),
        FEEDBACK(0x0D00), EXTERNAL(0x0E00);

        private final int base;

        Domain(int base) {
            this.base = base;
        }

        /** The {@code 0xNN00} range base this domain owns. */
        public int base() {
            return base;
        }

        static Domain of(int code) {
            int b = code & 0xFF00;
            for (Domain d : values()) {
                if (d.base == b) {
                    return d;
                }
            }
            throw new IllegalStateException("no effect domain for code 0x" + Integer.toHexString(code));
        }
    }

    private static final Map<Integer, EffectTag> BY_CODE = buildByCode();
    private static final Map<Class<? extends Effect>, EffectTag> BY_CLASS = buildByClass();

    private final int code;
    private final Class<? extends Effect> type;
    private final Domain domain;   // resolved eagerly: a code outside every range fails class init

    EffectTag(int code, Class<? extends Effect> type) {
        this.code = code;
        this.type = type;
        this.domain = Domain.of(code);
    }

    /** The stable {@code u16} wire tag persisted in the WAL framing. */
    public int code() {
        return code;
    }

    /** The effect record class this tag encodes. */
    public Class<? extends Effect> type() {
        return type;
    }

    /** The per-domain range this tag falls in — the framing's encode/decode dispatch key. */
    public Domain domain() {
        return domain;
    }

    /**
     * The standardized rejection a per-domain codec throws when routed a tag outside its range
     * (its switch {@code default} arm — unreachable unless the framing dispatch drifts).
     */
    public IllegalStateException outside(Domain expected) {
        return new IllegalStateException("tag " + this + " is " + domain + ", not " + expected);
    }

    /** The tag for a wire {@code code}, or {@code null} if the code is unknown (corrupt/newer). */
    public static EffectTag fromCode(int code) {
        return BY_CODE.get(code);
    }

    /** The tag registered for an effect record class, or {@code null} if the class is untagged. */
    public static EffectTag forClass(Class<? extends Effect> type) {
        return BY_CLASS.get(type);
    }

    /** The tag for an effect that MUST journal; throws for an untagged (control) record class. */
    public static EffectTag require(Effect e) {
        EffectTag tag = BY_CLASS.get(e.getClass());
        if (tag == null) {
            throw new IllegalStateException("no journal tag registered for effect "
                    + e.getClass().getName());
        }
        return tag;
    }

    /** An immutable view of every tag's record class (package-visible for the codec + its tests). */
    static Map<Class<? extends Effect>, EffectTag> byClass() {
        return BY_CLASS;
    }

    private static Map<Integer, EffectTag> buildByCode() {
        Map<Integer, EffectTag> m = new HashMap<>();
        for (EffectTag t : values()) {
            EffectTag prior = m.put(t.code, t);
            if (prior != null) {
                throw new IllegalStateException("duplicate EffectTag code 0x" + Integer.toHexString(t.code)
                        + " for " + prior + " and " + t);
            }
        }
        return m;
    }

    private static Map<Class<? extends Effect>, EffectTag> buildByClass() {
        Map<Class<? extends Effect>, EffectTag> m = new HashMap<>();
        for (EffectTag t : values()) {
            m.put(t.type, t);
        }
        return m;
    }
}
