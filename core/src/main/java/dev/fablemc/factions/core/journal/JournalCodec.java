package dev.fablemc.factions.core.journal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.fablemc.factions.kernel.audit.FactionAuditAction;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;

/**
 * The stable binary codec between {@link Effect} records and journal payloads (proposal-C §6.1).
 * Every effect record has an explicit, never-reused {@code u16} tag (the record's {@code type}
 * field in the WAL framing); the {@link #verifyComplete()} boot check fails loudly if the sealed
 * {@link Effect} hierarchy ever grows a record without a tag here.
 *
 * <p>{@link Effect.ContinuationRequested} is the sole <b>control effect</b>: it carries an
 * {@link Intent} back into the pipeline (AM-5) and is stripped by the writer before fan-out, so
 * it never reaches the journal and deliberately has no tag. {@link #CONTROL_EFFECTS} records that
 * exclusion, and {@link #verifyComplete()} skips it — every <em>other</em> permitted subtype must
 * have a stable tag.
 *
 * <p><b>Owning thread(s):</b> stateless static codec; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> the tag tables are shared immutable maps.
 *
 * <p>The framing seq is authoritative and is stored once by the journal, so a payload carries
 * every field of its record <em>except</em> {@code seq} (re-supplied at decode). {@link Origin}
 * is written first for every effect. Strings are length-prefixed UTF-8 (nullable), so
 * {@code TEXT}-sized descriptions/notes are not bound by {@code DataOutputStream.writeUTF}'s
 * 64&nbsp;KB limit.
 */
public final class JournalCodec {

    // ── Stable tag registry (never renumber; append only) ─────────────────────────────────
    static final int FACTION_CREATED = 1;
    static final int FACTION_DISBANDED = 2;
    static final int FACTION_RENAMED = 3;
    static final int DESCRIPTION_CHANGED = 4;
    static final int MOTD_CHANGED = 5;
    static final int OWNERSHIP_TRANSFERRED = 6;
    static final int MERGE_REQUESTED = 7;
    static final int MERGE_COMPLETED = 8;
    static final int MEMBER_JOINED = 9;
    static final int MEMBER_LEFT = 10;
    static final int INVITE_CREATED = 11;
    static final int INVITE_REMOVED = 12;
    static final int RANK_CHANGED = 13;
    static final int ROLE_CREATED = 14;
    static final int ROLE_RENAMED = 15;
    static final int ROLE_REPRIORITIZED = 16;
    static final int ROLE_PREFIX_SET = 17;
    static final int ROLE_DELETED = 18;
    static final int ROLE_ASSIGNED = 19;
    static final int CLAIM_SET = 20;
    static final int CLAIM_REMOVED = 21;
    static final int ZONE_SET = 22;
    static final int ZONE_REMOVED = 23;
    static final int RELATION_DECLARED = 24;
    static final int RELATION_EFFECTIVE = 25;
    static final int POWER_CHANGED = 26;
    static final int POWER_FROZEN_CHANGED = 27;
    static final int DEATH_STREAK_ADVANCED = 28;
    static final int RAIDABLE_CHANGED = 29;
    static final int BANK_CHANGED = 30;
    static final int TAX_CHARGED = 31;
    static final int HOME_SET = 32;
    static final int HOME_CLEARED = 33;
    static final int WARP_SET = 34;
    static final int WARP_DELETED = 35;
    static final int WARP_PASSWORD_SET = 36;
    static final int WARP_COST_SET = 37;
    static final int CHEST_CREATED = 38;
    static final int CHEST_DELETED = 39;
    static final int CHEST_CONTENTS_CHANGED = 40;
    static final int FLAG_CHANGED = 41;
    static final int PREF_CHANGED = 42;
    static final int LOCALE_CHANGED = 43;
    static final int AUTO_MODE_CHANGED = 44;
    static final int FLY_CHANGED = 45;
    static final int OVERRIDE_CHANGED = 46;
    static final int SHIELD_CHANGED = 47;
    static final int SESSION_STARTED = 48;
    static final int SESSION_ENDED = 49;
    static final int INBOX_QUEUED = 50;
    static final int INBOX_DELIVERED = 51;
    static final int AUDIT_RECORDED = 52;
    static final int CONFIG_SWAPPED = 53;
    static final int NOTIFY = 54;
    static final int NOTIFY_FACTION = 55;
    static final int BROADCAST = 56;
    static final int REJECTED = 57;
    static final int PAYOUT_REQUESTED = 58;
    static final int ESCROW_REFUND = 59;
    static final int WG_REGION_UPSERT = 60;
    static final int WG_REGION_REMOVE = 61;
    static final int LWC_PURGE_REQUESTED = 62;

    private static final Map<Class<? extends Effect>, Integer> TAGS = buildTagTable();

    /**
     * The permitted {@link Effect} subtypes that are intentionally NOT journaled: pipeline
     * control effects stripped by the writer before fan-out. They carry no persistable domain
     * delta, so they have no tag and are excluded from {@link #verifyComplete()}.
     */
    static final Set<Class<? extends Effect>> CONTROL_EFFECTS =
            Collections.unmodifiableSet(new HashSet<>(java.util.List.of(
                    Effect.ContinuationRequested.class)));

    private JournalCodec() {
    }

    /** The stable {@code u16} tag for an effect's record class. */
    public static int tagOf(Effect e) {
        Integer tag = TAGS.get(e.getClass());
        if (tag == null) {
            throw new IllegalStateException("no journal tag registered for effect "
                    + e.getClass().getName());
        }
        return tag;
    }

    /** Encodes an effect's payload (origin + fields, excluding seq). */
    public static byte[] encode(Effect e) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        DataOutputStream out = new DataOutputStream(bos);
        try {
            writeOrigin(out, e.origin());
            writeBody(out, e);
            out.flush();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory encode failed", impossible);
        }
        return bos.toByteArray();
    }

    /** Decodes an effect from its tag, framing seq, and payload bytes. */
    public static Effect decode(int tag, long seq, byte[] payload) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            Origin origin = readOrigin(in);
            return readBody(tag, seq, origin, in);
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory decode failed", impossible);
        }
    }

    /**
     * Boot completeness gate: asserts every permitted {@link Effect} subtype has a tag and every
     * tag is unique. Throws {@link IllegalStateException} loudly if the vocabulary drifted.
     */
    public static void verifyComplete() {
        Class<?>[] permitted = Effect.class.getPermittedSubclasses();
        if (permitted == null) {
            throw new IllegalStateException("Effect is not a sealed interface");
        }
        StringBuilder missing = new StringBuilder();
        for (Class<?> sub : permitted) {
            if (CONTROL_EFFECTS.contains(sub)) {
                continue;   // control effect: stripped before journaling, deliberately untagged
            }
            if (!TAGS.containsKey(sub)) {
                if (missing.length() > 0) {
                    missing.append(", ");
                }
                missing.append(sub.getSimpleName());
            }
        }
        if (missing.length() > 0) {
            throw new IllegalStateException(
                    "JournalCodec is missing tags for effect record(s): " + missing);
        }
        // uniqueness of tag values
        Map<Integer, Class<?>> seen = new HashMap<>();
        for (Map.Entry<Class<? extends Effect>, Integer> en : TAGS.entrySet()) {
            Class<?> prior = seen.put(en.getValue(), en.getKey());
            if (prior != null) {
                throw new IllegalStateException("duplicate journal tag " + en.getValue()
                        + " for " + prior.getSimpleName() + " and " + en.getKey().getSimpleName());
            }
        }
    }

    /** The set of effect record classes that carry a stable tag (package-visible for tests). */
    static Set<Class<? extends Effect>> registeredEffectClasses() {
        return Collections.unmodifiableSet(new HashSet<>(TAGS.keySet()));
    }

    // ── Encode bodies ─────────────────────────────────────────────────────────────────────

    private static void writeBody(DataOutputStream o, Effect e) throws IOException {
        int tag = tagOf(e);
        switch (tag) {
            case FACTION_CREATED -> {
                Effect.FactionCreated x = (Effect.FactionCreated) e;
                o.writeInt(x.faction());
                writeUuid(o, x.id());
                writeString(o, x.name());
            }
            case FACTION_DISBANDED -> {
                Effect.FactionDisbanded x = (Effect.FactionDisbanded) e;
                o.writeInt(x.faction());
                writeString(o, x.name());
            }
            case FACTION_RENAMED -> {
                Effect.FactionRenamed x = (Effect.FactionRenamed) e;
                o.writeInt(x.faction());
                writeString(o, x.oldName());
                writeString(o, x.newName());
            }
            case DESCRIPTION_CHANGED -> {
                Effect.DescriptionChanged x = (Effect.DescriptionChanged) e;
                o.writeInt(x.faction());
                writeString(o, x.description());
            }
            case MOTD_CHANGED -> {
                Effect.MotdChanged x = (Effect.MotdChanged) e;
                o.writeInt(x.faction());
                writeString(o, x.motd());
            }
            case OWNERSHIP_TRANSFERRED -> {
                Effect.OwnershipTransferred x = (Effect.OwnershipTransferred) e;
                o.writeInt(x.faction());
                writeUuid(o, x.oldOwner());
                writeUuid(o, x.newOwner());
            }
            case MERGE_REQUESTED -> {
                Effect.MergeRequested x = (Effect.MergeRequested) e;
                o.writeInt(x.sender());
                o.writeInt(x.target());
            }
            case MERGE_COMPLETED -> {
                Effect.MergeCompleted x = (Effect.MergeCompleted) e;
                o.writeInt(x.sender());
                o.writeInt(x.target());
                o.writeInt(x.memberMoves());
                o.writeInt(x.claimMoves());
                o.writeDouble(x.bankMoved());
            }
            case MEMBER_JOINED -> {
                Effect.MemberJoined x = (Effect.MemberJoined) e;
                o.writeInt(x.faction());
                writeUuid(o, x.player());
            }
            case MEMBER_LEFT -> {
                Effect.MemberLeft x = (Effect.MemberLeft) e;
                o.writeInt(x.faction());
                writeUuid(o, x.player());
                o.writeBoolean(x.kicked());
            }
            case INVITE_CREATED -> {
                Effect.InviteCreated x = (Effect.InviteCreated) e;
                o.writeInt(x.faction());
                writeUuid(o, x.invitee());
                o.writeLong(x.inviteId());
            }
            case INVITE_REMOVED -> {
                Effect.InviteRemoved x = (Effect.InviteRemoved) e;
                o.writeInt(x.faction());
                writeUuid(o, x.invitee());
                o.writeInt(x.reason());
            }
            case RANK_CHANGED -> {
                Effect.RankChanged x = (Effect.RankChanged) e;
                o.writeInt(x.faction());
                writeUuid(o, x.player());
                o.writeInt(x.newRankIdx());
            }
            case ROLE_CREATED -> {
                Effect.RoleCreated x = (Effect.RoleCreated) e;
                o.writeInt(x.faction());
                writeString(o, x.roleId());
                writeString(o, x.name());
                o.writeInt(x.priority());
            }
            case ROLE_RENAMED -> {
                Effect.RoleRenamed x = (Effect.RoleRenamed) e;
                o.writeInt(x.faction());
                writeString(o, x.roleId());
                writeString(o, x.oldName());
                writeString(o, x.newName());
            }
            case ROLE_REPRIORITIZED -> {
                Effect.RoleRePrioritized x = (Effect.RoleRePrioritized) e;
                o.writeInt(x.faction());
                writeString(o, x.roleId());
                o.writeInt(x.priority());
            }
            case ROLE_PREFIX_SET -> {
                Effect.RolePrefixSet x = (Effect.RolePrefixSet) e;
                o.writeInt(x.faction());
                writeString(o, x.roleId());
                writeString(o, x.prefix());
            }
            case ROLE_DELETED -> {
                Effect.RoleDeleted x = (Effect.RoleDeleted) e;
                o.writeInt(x.faction());
                writeString(o, x.roleId());
            }
            case ROLE_ASSIGNED -> {
                Effect.RoleAssigned x = (Effect.RoleAssigned) e;
                o.writeInt(x.faction());
                writeUuid(o, x.player());
                writeString(o, x.roleId());
            }
            case CLAIM_SET -> {
                Effect.ClaimSet x = (Effect.ClaimSet) e;
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
                o.writeInt(x.faction());
                o.writeInt(x.prevOwner());
            }
            case CLAIM_REMOVED -> {
                Effect.ClaimRemoved x = (Effect.ClaimRemoved) e;
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
                o.writeInt(x.prevOwner());
            }
            case ZONE_SET -> {
                Effect.ZoneSet x = (Effect.ZoneSet) e;
                o.writeInt(x.zoneOrdinal());
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
                o.writeInt(x.prevOwner());
            }
            case ZONE_REMOVED -> {
                Effect.ZoneRemoved x = (Effect.ZoneRemoved) e;
                o.writeInt(x.zoneOrdinal());
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
            }
            case RELATION_DECLARED -> {
                Effect.RelationDeclared x = (Effect.RelationDeclared) e;
                o.writeInt(x.a());
                o.writeInt(x.b());
                o.writeInt(x.kind());
            }
            case RELATION_EFFECTIVE -> {
                Effect.RelationEffective x = (Effect.RelationEffective) e;
                o.writeInt(x.a());
                o.writeInt(x.b());
                o.writeInt(x.kind());
                o.writeInt(x.prevKind());
            }
            case POWER_CHANGED -> {
                Effect.PowerChanged x = (Effect.PowerChanged) e;
                writeUuid(o, x.player());
                o.writeDouble(x.before());
                o.writeDouble(x.after());
                o.writeInt(x.source());
                writeString(o, x.reasonCode());
            }
            case POWER_FROZEN_CHANGED -> {
                Effect.PowerFrozenChanged x = (Effect.PowerFrozenChanged) e;
                writeUuid(o, x.player());
                o.writeBoolean(x.frozen());
            }
            case DEATH_STREAK_ADVANCED -> {
                Effect.DeathStreakAdvanced x = (Effect.DeathStreakAdvanced) e;
                writeUuid(o, x.player());
                o.writeInt(x.streak());
            }
            case RAIDABLE_CHANGED -> {
                Effect.RaidableChanged x = (Effect.RaidableChanged) e;
                o.writeInt(x.faction());
                o.writeBoolean(x.nowRaidable());
            }
            case BANK_CHANGED -> {
                Effect.BankChanged x = (Effect.BankChanged) e;
                o.writeInt(x.faction());
                o.writeDouble(x.delta());
                o.writeDouble(x.balance());
                o.writeInt(x.txType());
                writeUuid(o, x.actor());
                o.writeInt(x.counterparty());
                writeString(o, x.note());
            }
            case TAX_CHARGED -> {
                Effect.TaxCharged x = (Effect.TaxCharged) e;
                o.writeInt(x.faction());
                o.writeDouble(x.amount());
                o.writeDouble(x.balance());
            }
            case HOME_SET -> o.writeInt(((Effect.HomeSet) e).faction());
            case HOME_CLEARED -> o.writeInt(((Effect.HomeCleared) e).faction());
            case WARP_SET -> {
                Effect.WarpSet x = (Effect.WarpSet) e;
                o.writeInt(x.faction());
                writeString(o, x.name());
            }
            case WARP_DELETED -> {
                Effect.WarpDeleted x = (Effect.WarpDeleted) e;
                o.writeInt(x.faction());
                writeString(o, x.name());
            }
            case WARP_PASSWORD_SET -> {
                Effect.WarpPasswordSet x = (Effect.WarpPasswordSet) e;
                o.writeInt(x.faction());
                writeString(o, x.name());
                o.writeBoolean(x.cleared());
            }
            case WARP_COST_SET -> {
                Effect.WarpCostSet x = (Effect.WarpCostSet) e;
                o.writeInt(x.faction());
                writeString(o, x.name());
                o.writeDouble(x.cost());
            }
            case CHEST_CREATED -> {
                Effect.ChestCreated x = (Effect.ChestCreated) e;
                o.writeInt(x.faction());
                writeString(o, x.name());
            }
            case CHEST_DELETED -> {
                Effect.ChestDeleted x = (Effect.ChestDeleted) e;
                o.writeInt(x.faction());
                writeString(o, x.name());
            }
            case CHEST_CONTENTS_CHANGED -> {
                Effect.ChestContentsChanged x = (Effect.ChestContentsChanged) e;
                o.writeInt(x.faction());
                writeString(o, x.name());
                o.writeLong(x.blobRef());
            }
            case FLAG_CHANGED -> {
                Effect.FlagChanged x = (Effect.FlagChanged) e;
                o.writeInt(x.faction());
                o.writeInt(x.flag());
                o.writeBoolean(x.value());
            }
            case PREF_CHANGED -> {
                Effect.PrefChanged x = (Effect.PrefChanged) e;
                writeUuid(o, x.player());
                o.writeInt(x.prefBit());
                o.writeBoolean(x.value());
            }
            case LOCALE_CHANGED -> {
                Effect.LocaleChanged x = (Effect.LocaleChanged) e;
                writeUuid(o, x.player());
                o.writeInt(x.localeIdx());
            }
            case AUTO_MODE_CHANGED -> {
                Effect.AutoModeChanged x = (Effect.AutoModeChanged) e;
                writeUuid(o, x.player());
                o.writeInt(x.mode());
            }
            case FLY_CHANGED -> {
                Effect.FlyChanged x = (Effect.FlyChanged) e;
                writeUuid(o, x.player());
                o.writeBoolean(x.on());
            }
            case OVERRIDE_CHANGED -> {
                Effect.OverrideChanged x = (Effect.OverrideChanged) e;
                writeUuid(o, x.player());
                o.writeBoolean(x.on());
            }
            case SHIELD_CHANGED -> {
                Effect.ShieldChanged x = (Effect.ShieldChanged) e;
                o.writeInt(x.faction());
                o.writeInt(x.startHour());
                o.writeInt(x.durationHours());
            }
            case SESSION_STARTED -> {
                Effect.SessionStarted x = (Effect.SessionStarted) e;
                writeUuid(o, x.player());
                o.writeLong(x.lastActivity());
            }
            case SESSION_ENDED -> {
                Effect.SessionEnded x = (Effect.SessionEnded) e;
                writeUuid(o, x.player());
                o.writeLong(x.lastActivity());
            }
            case INBOX_QUEUED -> {
                Effect.InboxQueued x = (Effect.InboxQueued) e;
                writeUuid(o, x.player());
                writeMessageKey(o, x.key());
                writeStringArray(o, x.args());
            }
            case INBOX_DELIVERED -> {
                Effect.InboxDelivered x = (Effect.InboxDelivered) e;
                writeUuid(o, x.player());
                writeLongArray(o, x.ids());
            }
            case AUDIT_RECORDED -> {
                Effect.AuditRecorded x = (Effect.AuditRecorded) e;
                o.writeInt(x.faction());
                writeUuid(o, x.actor());
                writeString(o, x.action() == null ? null : x.action().id());
                writeString(o, x.detail());
            }
            case CONFIG_SWAPPED -> writeString(o, ((Effect.ConfigSwapped) e).diffSummary());
            case NOTIFY -> {
                Effect.Notify x = (Effect.Notify) e;
                writeUuid(o, x.target());
                writeMessageKey(o, x.key());
                writeStringArray(o, x.args());
            }
            case NOTIFY_FACTION -> {
                Effect.NotifyFaction x = (Effect.NotifyFaction) e;
                o.writeInt(x.faction());
                o.writeInt(x.predicate());
                writeMessageKey(o, x.key());
                writeStringArray(o, x.args());
            }
            case BROADCAST -> {
                Effect.Broadcast x = (Effect.Broadcast) e;
                o.writeInt(x.scope());
                writeMessageKey(o, x.key());
                writeStringArray(o, x.args());
            }
            case REJECTED -> {
                Effect.Rejected x = (Effect.Rejected) e;
                writeString(o, x.reason() == null ? null : x.reason().name());
                writeStringArray(o, x.args());
            }
            case PAYOUT_REQUESTED -> {
                Effect.PayoutRequested x = (Effect.PayoutRequested) e;
                o.writeLong(x.escrowId());
                writeUuid(o, x.player());
                o.writeDouble(x.amount());
            }
            case ESCROW_REFUND -> {
                Effect.EscrowRefund x = (Effect.EscrowRefund) e;
                o.writeLong(x.escrowId());
                writeUuid(o, x.player());
                o.writeDouble(x.amount());
            }
            case WG_REGION_UPSERT -> {
                Effect.WgRegionUpsert x = (Effect.WgRegionUpsert) e;
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
                o.writeInt(x.faction());
            }
            case WG_REGION_REMOVE -> {
                Effect.WgRegionRemove x = (Effect.WgRegionRemove) e;
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
            }
            case LWC_PURGE_REQUESTED -> {
                Effect.LwcPurgeRequested x = (Effect.LwcPurgeRequested) e;
                o.writeInt(x.worldIdx());
                o.writeLong(x.key());
                o.writeInt(x.newOwner());
            }
            default -> throw new IllegalStateException("unencodable tag " + tag);
        }
    }

    // ── Decode bodies ─────────────────────────────────────────────────────────────────────

    private static Effect readBody(int tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag) {
            case FACTION_CREATED ->
                    new Effect.FactionCreated(seq, origin, in.readInt(), readUuid(in), readString(in));
            case FACTION_DISBANDED ->
                    new Effect.FactionDisbanded(seq, origin, in.readInt(), readString(in));
            case FACTION_RENAMED ->
                    new Effect.FactionRenamed(seq, origin, in.readInt(), readString(in), readString(in));
            case DESCRIPTION_CHANGED ->
                    new Effect.DescriptionChanged(seq, origin, in.readInt(), readString(in));
            case MOTD_CHANGED ->
                    new Effect.MotdChanged(seq, origin, in.readInt(), readString(in));
            case OWNERSHIP_TRANSFERRED ->
                    new Effect.OwnershipTransferred(seq, origin, in.readInt(), readUuid(in), readUuid(in));
            case MERGE_REQUESTED ->
                    new Effect.MergeRequested(seq, origin, in.readInt(), in.readInt());
            case MERGE_COMPLETED ->
                    new Effect.MergeCompleted(seq, origin, in.readInt(), in.readInt(), in.readInt(),
                            in.readInt(), in.readDouble());
            case MEMBER_JOINED ->
                    new Effect.MemberJoined(seq, origin, in.readInt(), readUuid(in));
            case MEMBER_LEFT ->
                    new Effect.MemberLeft(seq, origin, in.readInt(), readUuid(in), in.readBoolean());
            case INVITE_CREATED ->
                    new Effect.InviteCreated(seq, origin, in.readInt(), readUuid(in), in.readLong());
            case INVITE_REMOVED ->
                    new Effect.InviteRemoved(seq, origin, in.readInt(), readUuid(in), in.readInt());
            case RANK_CHANGED ->
                    new Effect.RankChanged(seq, origin, in.readInt(), readUuid(in), in.readInt());
            case ROLE_CREATED ->
                    new Effect.RoleCreated(seq, origin, in.readInt(), readString(in), readString(in),
                            in.readInt());
            case ROLE_RENAMED ->
                    new Effect.RoleRenamed(seq, origin, in.readInt(), readString(in), readString(in),
                            readString(in));
            case ROLE_REPRIORITIZED ->
                    new Effect.RoleRePrioritized(seq, origin, in.readInt(), readString(in), in.readInt());
            case ROLE_PREFIX_SET ->
                    new Effect.RolePrefixSet(seq, origin, in.readInt(), readString(in), readString(in));
            case ROLE_DELETED ->
                    new Effect.RoleDeleted(seq, origin, in.readInt(), readString(in));
            case ROLE_ASSIGNED ->
                    new Effect.RoleAssigned(seq, origin, in.readInt(), readUuid(in), readString(in));
            case CLAIM_SET ->
                    new Effect.ClaimSet(seq, origin, in.readInt(), in.readLong(), in.readInt(),
                            in.readInt());
            case CLAIM_REMOVED ->
                    new Effect.ClaimRemoved(seq, origin, in.readInt(), in.readLong(), in.readInt());
            case ZONE_SET ->
                    new Effect.ZoneSet(seq, origin, in.readInt(), in.readInt(), in.readLong(),
                            in.readInt());
            case ZONE_REMOVED ->
                    new Effect.ZoneRemoved(seq, origin, in.readInt(), in.readInt(), in.readLong());
            case RELATION_DECLARED ->
                    new Effect.RelationDeclared(seq, origin, in.readInt(), in.readInt(), in.readInt());
            case RELATION_EFFECTIVE ->
                    new Effect.RelationEffective(seq, origin, in.readInt(), in.readInt(), in.readInt(),
                            in.readInt());
            case POWER_CHANGED ->
                    new Effect.PowerChanged(seq, origin, readUuid(in), in.readDouble(), in.readDouble(),
                            in.readInt(), readString(in));
            case POWER_FROZEN_CHANGED ->
                    new Effect.PowerFrozenChanged(seq, origin, readUuid(in), in.readBoolean());
            case DEATH_STREAK_ADVANCED ->
                    new Effect.DeathStreakAdvanced(seq, origin, readUuid(in), in.readInt());
            case RAIDABLE_CHANGED ->
                    new Effect.RaidableChanged(seq, origin, in.readInt(), in.readBoolean());
            case BANK_CHANGED ->
                    new Effect.BankChanged(seq, origin, in.readInt(), in.readDouble(), in.readDouble(),
                            in.readInt(), readUuid(in), in.readInt(), readString(in));
            case TAX_CHARGED ->
                    new Effect.TaxCharged(seq, origin, in.readInt(), in.readDouble(), in.readDouble());
            case HOME_SET -> new Effect.HomeSet(seq, origin, in.readInt());
            case HOME_CLEARED -> new Effect.HomeCleared(seq, origin, in.readInt());
            case WARP_SET -> new Effect.WarpSet(seq, origin, in.readInt(), readString(in));
            case WARP_DELETED -> new Effect.WarpDeleted(seq, origin, in.readInt(), readString(in));
            case WARP_PASSWORD_SET ->
                    new Effect.WarpPasswordSet(seq, origin, in.readInt(), readString(in),
                            in.readBoolean());
            case WARP_COST_SET ->
                    new Effect.WarpCostSet(seq, origin, in.readInt(), readString(in), in.readDouble());
            case CHEST_CREATED -> new Effect.ChestCreated(seq, origin, in.readInt(), readString(in));
            case CHEST_DELETED -> new Effect.ChestDeleted(seq, origin, in.readInt(), readString(in));
            case CHEST_CONTENTS_CHANGED ->
                    new Effect.ChestContentsChanged(seq, origin, in.readInt(), readString(in),
                            in.readLong());
            case FLAG_CHANGED ->
                    new Effect.FlagChanged(seq, origin, in.readInt(), in.readInt(), in.readBoolean());
            case PREF_CHANGED ->
                    new Effect.PrefChanged(seq, origin, readUuid(in), in.readInt(), in.readBoolean());
            case LOCALE_CHANGED ->
                    new Effect.LocaleChanged(seq, origin, readUuid(in), in.readInt());
            case AUTO_MODE_CHANGED ->
                    new Effect.AutoModeChanged(seq, origin, readUuid(in), in.readInt());
            case FLY_CHANGED -> new Effect.FlyChanged(seq, origin, readUuid(in), in.readBoolean());
            case OVERRIDE_CHANGED ->
                    new Effect.OverrideChanged(seq, origin, readUuid(in), in.readBoolean());
            case SHIELD_CHANGED ->
                    new Effect.ShieldChanged(seq, origin, in.readInt(), in.readInt(), in.readInt());
            case SESSION_STARTED ->
                    new Effect.SessionStarted(seq, origin, readUuid(in), in.readLong());
            case SESSION_ENDED ->
                    new Effect.SessionEnded(seq, origin, readUuid(in), in.readLong());
            case INBOX_QUEUED ->
                    new Effect.InboxQueued(seq, origin, readUuid(in), readMessageKey(in),
                            readStringArray(in));
            case INBOX_DELIVERED ->
                    new Effect.InboxDelivered(seq, origin, readUuid(in), readLongArray(in));
            case AUDIT_RECORDED ->
                    new Effect.AuditRecorded(seq, origin, in.readInt(), readUuid(in),
                            FactionAuditAction.fromId(readString(in)), readString(in));
            case CONFIG_SWAPPED -> new Effect.ConfigSwapped(seq, origin, readString(in));
            case NOTIFY ->
                    new Effect.Notify(seq, origin, readUuid(in), readMessageKey(in), readStringArray(in));
            case NOTIFY_FACTION ->
                    new Effect.NotifyFaction(seq, origin, in.readInt(), in.readInt(),
                            readMessageKey(in), readStringArray(in));
            case BROADCAST ->
                    new Effect.Broadcast(seq, origin, in.readInt(), readMessageKey(in),
                            readStringArray(in));
            case REJECTED ->
                    new Effect.Rejected(seq, origin, readReasonCode(in), readStringArray(in));
            case PAYOUT_REQUESTED ->
                    new Effect.PayoutRequested(seq, origin, in.readLong(), readUuid(in), in.readDouble());
            case ESCROW_REFUND ->
                    new Effect.EscrowRefund(seq, origin, in.readLong(), readUuid(in), in.readDouble());
            case WG_REGION_UPSERT ->
                    new Effect.WgRegionUpsert(seq, origin, in.readInt(), in.readLong(), in.readInt());
            case WG_REGION_REMOVE ->
                    new Effect.WgRegionRemove(seq, origin, in.readInt(), in.readLong());
            case LWC_PURGE_REQUESTED ->
                    new Effect.LwcPurgeRequested(seq, origin, in.readInt(), in.readLong(), in.readInt());
            default -> throw new IllegalStateException("undecodable tag " + tag);
        };
    }

    // ── Field primitives ──────────────────────────────────────────────────────────────────

    private static void writeString(DataOutputStream o, String s) throws IOException {
        if (s == null) {
            o.writeInt(-1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.writeInt(b.length);
        o.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            return null;
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeUuid(DataOutputStream o, UUID u) throws IOException {
        if (u == null) {
            o.writeBoolean(false);
            return;
        }
        o.writeBoolean(true);
        o.writeLong(u.getMostSignificantBits());
        o.writeLong(u.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        long msb = in.readLong();
        long lsb = in.readLong();
        return new UUID(msb, lsb);
    }

    private static void writeOrigin(DataOutputStream o, Origin origin) throws IOException {
        if (origin == null) {
            o.writeInt(Integer.MIN_VALUE);
            return;
        }
        o.writeInt(origin.channel());
        writeUuid(o, origin.actor());
    }

    private static Origin readOrigin(DataInputStream in) throws IOException {
        int channel = in.readInt();
        if (channel == Integer.MIN_VALUE) {
            return null;
        }
        UUID actor = readUuid(in);
        return new Origin(actor, channel);
    }

    private static void writeMessageKey(DataOutputStream o, MessageKey k) throws IOException {
        writeString(o, k == null ? null : k.key());
    }

    private static MessageKey readMessageKey(DataInputStream in) throws IOException {
        String key = readString(in);
        return key == null ? null : MessageKey.of(key);
    }

    private static void writeStringArray(DataOutputStream o, String[] a) throws IOException {
        if (a == null) {
            o.writeInt(-1);
            return;
        }
        o.writeInt(a.length);
        for (String s : a) {
            writeString(o, s);
        }
    }

    private static String[] readStringArray(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            return null;
        }
        String[] a = new String[len];
        for (int i = 0; i < len; i++) {
            a[i] = readString(in);
        }
        return a;
    }

    private static void writeLongArray(DataOutputStream o, long[] a) throws IOException {
        if (a == null) {
            o.writeInt(-1);
            return;
        }
        o.writeInt(a.length);
        for (long v : a) {
            o.writeLong(v);
        }
    }

    private static long[] readLongArray(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            return null;
        }
        long[] a = new long[len];
        for (int i = 0; i < len; i++) {
            a[i] = in.readLong();
        }
        return a;
    }

    private static ReasonCode readReasonCode(DataInputStream in) throws IOException {
        String name = readString(in);
        return name == null ? null : ReasonCode.valueOf(name);
    }

    private static Map<Class<? extends Effect>, Integer> buildTagTable() {
        Map<Class<? extends Effect>, Integer> m = new HashMap<>();
        m.put(Effect.FactionCreated.class, FACTION_CREATED);
        m.put(Effect.FactionDisbanded.class, FACTION_DISBANDED);
        m.put(Effect.FactionRenamed.class, FACTION_RENAMED);
        m.put(Effect.DescriptionChanged.class, DESCRIPTION_CHANGED);
        m.put(Effect.MotdChanged.class, MOTD_CHANGED);
        m.put(Effect.OwnershipTransferred.class, OWNERSHIP_TRANSFERRED);
        m.put(Effect.MergeRequested.class, MERGE_REQUESTED);
        m.put(Effect.MergeCompleted.class, MERGE_COMPLETED);
        m.put(Effect.MemberJoined.class, MEMBER_JOINED);
        m.put(Effect.MemberLeft.class, MEMBER_LEFT);
        m.put(Effect.InviteCreated.class, INVITE_CREATED);
        m.put(Effect.InviteRemoved.class, INVITE_REMOVED);
        m.put(Effect.RankChanged.class, RANK_CHANGED);
        m.put(Effect.RoleCreated.class, ROLE_CREATED);
        m.put(Effect.RoleRenamed.class, ROLE_RENAMED);
        m.put(Effect.RoleRePrioritized.class, ROLE_REPRIORITIZED);
        m.put(Effect.RolePrefixSet.class, ROLE_PREFIX_SET);
        m.put(Effect.RoleDeleted.class, ROLE_DELETED);
        m.put(Effect.RoleAssigned.class, ROLE_ASSIGNED);
        m.put(Effect.ClaimSet.class, CLAIM_SET);
        m.put(Effect.ClaimRemoved.class, CLAIM_REMOVED);
        m.put(Effect.ZoneSet.class, ZONE_SET);
        m.put(Effect.ZoneRemoved.class, ZONE_REMOVED);
        m.put(Effect.RelationDeclared.class, RELATION_DECLARED);
        m.put(Effect.RelationEffective.class, RELATION_EFFECTIVE);
        m.put(Effect.PowerChanged.class, POWER_CHANGED);
        m.put(Effect.PowerFrozenChanged.class, POWER_FROZEN_CHANGED);
        m.put(Effect.DeathStreakAdvanced.class, DEATH_STREAK_ADVANCED);
        m.put(Effect.RaidableChanged.class, RAIDABLE_CHANGED);
        m.put(Effect.BankChanged.class, BANK_CHANGED);
        m.put(Effect.TaxCharged.class, TAX_CHARGED);
        m.put(Effect.HomeSet.class, HOME_SET);
        m.put(Effect.HomeCleared.class, HOME_CLEARED);
        m.put(Effect.WarpSet.class, WARP_SET);
        m.put(Effect.WarpDeleted.class, WARP_DELETED);
        m.put(Effect.WarpPasswordSet.class, WARP_PASSWORD_SET);
        m.put(Effect.WarpCostSet.class, WARP_COST_SET);
        m.put(Effect.ChestCreated.class, CHEST_CREATED);
        m.put(Effect.ChestDeleted.class, CHEST_DELETED);
        m.put(Effect.ChestContentsChanged.class, CHEST_CONTENTS_CHANGED);
        m.put(Effect.FlagChanged.class, FLAG_CHANGED);
        m.put(Effect.PrefChanged.class, PREF_CHANGED);
        m.put(Effect.LocaleChanged.class, LOCALE_CHANGED);
        m.put(Effect.AutoModeChanged.class, AUTO_MODE_CHANGED);
        m.put(Effect.FlyChanged.class, FLY_CHANGED);
        m.put(Effect.OverrideChanged.class, OVERRIDE_CHANGED);
        m.put(Effect.ShieldChanged.class, SHIELD_CHANGED);
        m.put(Effect.SessionStarted.class, SESSION_STARTED);
        m.put(Effect.SessionEnded.class, SESSION_ENDED);
        m.put(Effect.InboxQueued.class, INBOX_QUEUED);
        m.put(Effect.InboxDelivered.class, INBOX_DELIVERED);
        m.put(Effect.AuditRecorded.class, AUDIT_RECORDED);
        m.put(Effect.ConfigSwapped.class, CONFIG_SWAPPED);
        m.put(Effect.Notify.class, NOTIFY);
        m.put(Effect.NotifyFaction.class, NOTIFY_FACTION);
        m.put(Effect.Broadcast.class, BROADCAST);
        m.put(Effect.Rejected.class, REJECTED);
        m.put(Effect.PayoutRequested.class, PAYOUT_REQUESTED);
        m.put(Effect.EscrowRefund.class, ESCROW_REFUND);
        m.put(Effect.WgRegionUpsert.class, WG_REGION_UPSERT);
        m.put(Effect.WgRegionRemove.class, WG_REGION_REMOVE);
        m.put(Effect.LwcPurgeRequested.class, LWC_PURGE_REQUESTED);
        return m;
    }
}
