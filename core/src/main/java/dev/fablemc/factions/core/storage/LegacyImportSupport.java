package dev.fablemc.factions.core.storage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.RelationKind;

/**
 * Dependency-free parsing + relation-folding helpers shared by {@code storage.load.BaselineLoader}
 * (loads FableFactions' own schema) and {@code storage.legacy.PvpIndexImporter} (imports the
 * reference PvPIndex schema).
 *
 * <p>Both loaders read the same JSON-ish text columns (a faction's {@code relations_json} and
 * {@code flags_json}) and the same {@code board} chunk keys, and both must fold declared relation
 * <em>wishes</em> into <em>effective</em> symmetric edges by the identical rule: an asymmetric
 * ALLY/TRUCE wish stays a wish (effective only when mutual), while ENEMY is symmetric-max (one
 * side's enmity makes both enemies). Keeping that rule in one place guarantees the two paths can
 * never diverge.
 *
 * <p><b>Owning thread(s):</b> boot / import thread. <b>Mutability:</b> stateless static helpers;
 * no new dependency (a tiny tolerant scanner, not a full JSON parser — these are one-shot,
 * off-hot-path migration reads).
 *
 * <p>W25-REORG §P2b: this shared parse/relation-fold helper (the "LegacyJsonBlobs" role) stays in
 * the {@code storage} root, public, rather than being duplicated into {@code storage.load} and
 * {@code storage.legacy} — the whole point of the class is that the two loaders can never diverge
 * on the fold rule.
 */
public final class LegacyImportSupport {

    // "uuid" : "KIND"  or  "uuid" : KIND
    private static final Pattern REL_SCALAR = Pattern.compile(
            "\"([0-9a-fA-F\\-]{32,36})\"\\s*:\\s*\"?([A-Za-z_]+)\"?");
    // "uuid" : { ... "type|kind|relation|status" : "KIND" ... }
    private static final Pattern REL_OBJECT = Pattern.compile(
            "\"([0-9a-fA-F\\-]{32,36})\"\\s*:\\s*\\{[^{}]*?"
                    + "\"(?:type|kind|relation|status)\"\\s*:\\s*\"([A-Za-z_]+)\"");
    // "key" : true|false
    private static final Pattern FLAG_BOOL = Pattern.compile(
            "\"([A-Za-z_]+)\"\\s*:\\s*(true|false)");

    private LegacyImportSupport() {
    }

    /** Maps a reference relation name (any case) to a {@link RelationKind} byte; NEUTRAL default. */
    public static byte kindFromName(String raw) {
        if (raw == null) {
            return RelationKind.NEUTRAL;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "member":
                return RelationKind.MEMBER;
            case "ally":
                return RelationKind.ALLY;
            case "truce":
                return RelationKind.TRUCE;
            case "enemy":
                return RelationKind.ENEMY;
            case "neutral":
            default:
                return RelationKind.NEUTRAL;
        }
    }

    /**
     * Parses {@code relations_json} into an insertion-ordered {@code otherFactionId → declaredKind}
     * map, ignoring NEUTRAL (the never-stored default). Handles both the scalar
     * ({@code "uuid":"ALLY"}) and object ({@code "uuid":{"type":"ALLY"}}) reference encodings.
     */
    public static Map<String, Byte> parseRelations(String relationsJson) {
        Map<String, Byte> out = new LinkedHashMap<>();
        if (relationsJson == null || relationsJson.isEmpty()) {
            return out;
        }
        Matcher obj = REL_OBJECT.matcher(relationsJson);
        while (obj.find()) {
            byte kind = kindFromName(obj.group(2));
            if (kind != RelationKind.NEUTRAL) {
                out.put(obj.group(1), kind);
            }
        }
        Matcher scalar = REL_SCALAR.matcher(relationsJson);
        while (scalar.find()) {
            String id = scalar.group(1);
            if (out.containsKey(id)) {
                continue;   // the object form already captured this edge
            }
            byte kind = kindFromName(scalar.group(2));
            if (kind != RelationKind.NEUTRAL) {
                out.put(id, kind);
            }
        }
        return out;
    }

    /**
     * The effective, symmetric relation between two factions from their two declared wishes
     * (proposal-C / critique rule): ENEMY if either side declares ENEMY; ALLY only if both declare
     * ALLY; TRUCE only if both declare a friendly-or-truce wish that is not ally-on-both; else
     * NEUTRAL. This makes an asymmetric ALLY wish ineffective until reciprocated.
     */
    public static byte effectiveKind(byte aToB, byte bToA) {
        if (aToB == RelationKind.ENEMY || bToA == RelationKind.ENEMY) {
            return RelationKind.ENEMY;
        }
        if (aToB == RelationKind.ALLY && bToA == RelationKind.ALLY) {
            return RelationKind.ALLY;
        }
        boolean aFriendly = aToB == RelationKind.ALLY || aToB == RelationKind.TRUCE;
        boolean bFriendly = bToA == RelationKind.ALLY || bToA == RelationKind.TRUCE;
        if (aFriendly && bFriendly) {
            return RelationKind.TRUCE;   // mutual friendliness that is not ally-on-both = truce
        }
        return RelationKind.NEUTRAL;
    }

    /**
     * Folds {@code flags_json} boolean pairs into a {@link Faction} flag-bits long, setting the
     * override + value bits for each recognised flag alias. Unknown keys are ignored (they fall
     * back to the config default at read time).
     */
    public static long parseFlags(String flagsJson, long base) {
        long bits = base;
        if (flagsJson == null || flagsJson.isEmpty()) {
            return bits;
        }
        Matcher m = FLAG_BOOL.matcher(flagsJson);
        while (m.find()) {
            int flag = flagOrdinal(m.group(1));
            if (flag >= 0) {
                bits = Faction.withFlag(bits, flag, Boolean.parseBoolean(m.group(2)));
            }
        }
        return bits;
    }

    private static int flagOrdinal(String rawKey) {
        String k = rawKey.toLowerCase(Locale.ROOT).replace("_", "");
        switch (k) {
            case "pvp":
                return Faction.FLAG_PVP;
            case "friendlyfire":
            case "ff":
                return Faction.FLAG_FRIENDLY_FIRE;
            case "explosions":
            case "explosion":
            case "tnt":
                return Faction.FLAG_EXPLOSIONS;
            case "firespread":
            case "fire":
                return Faction.FLAG_FIRE_SPREAD;
            case "open":
                return Faction.FLAG_OPEN;
            default:
                return -1;
        }
    }
}
