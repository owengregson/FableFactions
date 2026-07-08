package dev.fablemc.factions.core.journal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import dev.fablemc.factions.core.journal.codec.ChestCodec;
import dev.fablemc.factions.core.journal.codec.ClaimCodec;
import dev.fablemc.factions.core.journal.codec.EconomyCodec;
import dev.fablemc.factions.core.journal.codec.ExternalCodec;
import dev.fablemc.factions.core.journal.codec.FeedbackCodec;
import dev.fablemc.factions.core.journal.codec.LifecycleCodec;
import dev.fablemc.factions.core.journal.codec.MembershipCodec;
import dev.fablemc.factions.core.journal.codec.PowerCodec;
import dev.fablemc.factions.core.journal.codec.PrefCodec;
import dev.fablemc.factions.core.journal.codec.RelationCodec;
import dev.fablemc.factions.core.journal.codec.RoleCodec;
import dev.fablemc.factions.core.journal.codec.SessionCodec;
import dev.fablemc.factions.core.journal.codec.SystemCodec;
import dev.fablemc.factions.core.journal.codec.TravelCodec;
import dev.fablemc.factions.core.journal.codec.Wire;
import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.SystemEffect;
import dev.fablemc.factions.kernel.intent.Intent;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * The stable binary codec between {@link Effect} records and journal payloads (proposal-C §6.1).
 * Framing and dispatch only: every journaled effect record has an explicit {@code u16} tag
 * ({@link EffectTag}) grouped in a per-domain range, and this class routes encode/decode to the
 * per-domain codec for that range (in {@code journal.codec}). Field primitives live in
 * {@link Wire}. The {@link #verifyComplete()} boot check fails loudly if the sealed {@link Effect}
 * hierarchy ever grows a record without a tag.
 *
 * <p>{@link SystemEffect.ContinuationRequested} is the sole <b>control effect</b>: it carries an
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

    /**
     * The permitted {@link Effect} subtypes that are intentionally NOT journaled: pipeline
     * control effects stripped by the writer before fan-out. They carry no persistable domain
     * delta, so they have no tag and are excluded from {@link #verifyComplete()}.
     */
    static final Set<Class<? extends Effect>> CONTROL_EFFECTS =
            Set.of(SystemEffect.ContinuationRequested.class);

    private JournalCodec() {
    }

    /** The stable {@code u16} tag for an effect's record class. */
    public static int tagOf(Effect e) {
        return EffectTag.require(e).code();
    }

    /** Encodes an effect's payload (origin + fields, excluding seq). */
    public static byte[] encode(Effect e) {
        EffectTag tag = EffectTag.require(e);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        DataOutputStream out = new DataOutputStream(bos);
        try {
            Wire.writeOrigin(out, e.origin());
            encodeBody(out, tag, e);
            out.flush();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory encode failed", impossible);
        }
        return bos.toByteArray();
    }

    /** Decodes an effect from its tag code, framing seq, and payload bytes. */
    public static Effect decode(int tagCode, long seq, byte[] payload) {
        EffectTag tag = EffectTag.fromCode(tagCode);
        if (tag == null) {
            throw new IllegalStateException("undecodable tag 0x" + Integer.toHexString(tagCode));
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            Origin origin = Wire.readOrigin(in);
            return decodeBody(tag, seq, origin, in);
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory decode failed", impossible);
        }
    }

    private static void encodeBody(DataOutputStream out, EffectTag tag, Effect e) throws IOException {
        switch (tag.domain()) {
            case LIFECYCLE -> LifecycleCodec.encode(out, tag, e);
            case MEMBERSHIP -> MembershipCodec.encode(out, tag, e);
            case ROLE -> RoleCodec.encode(out, tag, e);
            case CLAIM -> ClaimCodec.encode(out, tag, e);
            case RELATION -> RelationCodec.encode(out, tag, e);
            case POWER -> PowerCodec.encode(out, tag, e);
            case ECONOMY -> EconomyCodec.encode(out, tag, e);
            case TRAVEL -> TravelCodec.encode(out, tag, e);
            case CHEST -> ChestCodec.encode(out, tag, e);
            case PREF -> PrefCodec.encode(out, tag, e);
            case SESSION -> SessionCodec.encode(out, tag, e);
            case SYSTEM -> SystemCodec.encode(out, tag, e);
            case FEEDBACK -> FeedbackCodec.encode(out, tag, e);
            case EXTERNAL -> ExternalCodec.encode(out, tag, e);
        }
    }

    private static Effect decodeBody(EffectTag tag, long seq, Origin origin, DataInputStream in)
            throws IOException {
        return switch (tag.domain()) {
            case LIFECYCLE -> LifecycleCodec.decode(tag, seq, origin, in);
            case MEMBERSHIP -> MembershipCodec.decode(tag, seq, origin, in);
            case ROLE -> RoleCodec.decode(tag, seq, origin, in);
            case CLAIM -> ClaimCodec.decode(tag, seq, origin, in);
            case RELATION -> RelationCodec.decode(tag, seq, origin, in);
            case POWER -> PowerCodec.decode(tag, seq, origin, in);
            case ECONOMY -> EconomyCodec.decode(tag, seq, origin, in);
            case TRAVEL -> TravelCodec.decode(tag, seq, origin, in);
            case CHEST -> ChestCodec.decode(tag, seq, origin, in);
            case PREF -> PrefCodec.decode(tag, seq, origin, in);
            case SESSION -> SessionCodec.decode(tag, seq, origin, in);
            case SYSTEM -> SystemCodec.decode(tag, seq, origin, in);
            case FEEDBACK -> FeedbackCodec.decode(tag, seq, origin, in);
            case EXTERNAL -> ExternalCodec.decode(tag, seq, origin, in);
        };
    }

    /**
     * Boot completeness gate: asserts every permitted {@link Effect} subtype has a tag (or is a
     * control effect) and every tag value is unique. Throws {@link IllegalStateException} loudly
     * if the vocabulary drifted. (Tag-code uniqueness is also enforced eagerly in {@link EffectTag}.)
     */
    public static void verifyComplete() {
        Set<Class<?>> leaves = leafEffectClasses();
        if (leaves.isEmpty()) {
            throw new IllegalStateException("Effect is not a sealed interface");
        }
        StringBuilder missing = new StringBuilder();
        for (Class<?> sub : leaves) {
            if (CONTROL_EFFECTS.contains(sub)) {
                continue;   // control effect: stripped before journaling, deliberately untagged
            }
            if (EffectTag.forClass(asEffectClass(sub)) == null) {
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
    }

    /** The set of effect record classes that carry a stable tag (package-visible for tests). */
    static Set<Class<? extends Effect>> registeredEffectClasses() {
        return Collections.unmodifiableSet(new HashSet<>(EffectTag.byClass().keySet()));
    }

    /**
     * Every leaf (record) effect class, flattening the sealed sub-interface hierarchy (W25-REORG
     * §P1): {@link Effect} permits per-domain sub-interfaces, each of which permits the leaf
     * records. Package-visible so the codec test can enumerate the real records rather than the
     * intermediate sub-interfaces.
     */
    static Set<Class<?>> leafEffectClasses() {
        LinkedHashSet<Class<?>> out = new LinkedHashSet<>();
        collectLeafEffects(Effect.class, out);
        return out;
    }

    private static void collectLeafEffects(Class<?> type, Set<Class<?>> out) {
        Class<?>[] permitted = type.getPermittedSubclasses();
        if (permitted == null || permitted.length == 0) {
            out.add(type);   // a non-sealed leaf: an effect record
            return;
        }
        for (Class<?> sub : permitted) {
            collectLeafEffects(sub, out);
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Effect> asEffectClass(Class<?> sub) {
        return (Class<? extends Effect>) sub;
    }
}
