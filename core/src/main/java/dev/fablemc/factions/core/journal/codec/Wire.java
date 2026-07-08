package dev.fablemc.factions.core.journal.codec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.vocab.FactionAuditAction;

/**
 * Primitive read/write helpers shared by the per-domain journal codecs (W25-REORG §P2b). Every
 * field encoding lives here exactly once — always as a write/read PAIR, so a wire-format mismatch
 * is visible on adjacent lines — and the domain codecs read as a flat field list.
 *
 * <p>Strings are length-prefixed UTF-8 (nullable via a {@code -1} length), so {@code TEXT}-sized
 * descriptions are not bound by {@code DataOutputStream.writeUTF}'s 64&nbsp;KB limit. {@link UUID}s
 * and arrays carry an explicit presence/length prefix so {@code null} survives the round trip.
 *
 * <p><b>Owning thread(s):</b> stateless static helpers; encode on the writer, decode on the
 * storage/replay thread. <b>Mutability:</b> none.
 */
public final class Wire {

    private Wire() {
    }

    public static void writeString(DataOutputStream o, String s) throws IOException {
        if (s == null) {
            o.writeInt(-1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.writeInt(b.length);
        o.write(b);
    }

    public static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            return null;
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public static void writeUuid(DataOutputStream o, UUID u) throws IOException {
        if (u == null) {
            o.writeBoolean(false);
            return;
        }
        o.writeBoolean(true);
        o.writeLong(u.getMostSignificantBits());
        o.writeLong(u.getLeastSignificantBits());
    }

    public static UUID readUuid(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        long msb = in.readLong();
        long lsb = in.readLong();
        return new UUID(msb, lsb);
    }

    public static void writeOrigin(DataOutputStream o, Origin origin) throws IOException {
        if (origin == null) {
            o.writeInt(Integer.MIN_VALUE);
            return;
        }
        o.writeInt(origin.channel());
        writeUuid(o, origin.actor());
    }

    public static Origin readOrigin(DataInputStream in) throws IOException {
        int channel = in.readInt();
        if (channel == Integer.MIN_VALUE) {
            return null;
        }
        UUID actor = readUuid(in);
        return new Origin(actor, channel);
    }

    public static void writeMessageKey(DataOutputStream o, MessageKey k) throws IOException {
        writeString(o, k == null ? null : k.key());
    }

    public static MessageKey readMessageKey(DataInputStream in) throws IOException {
        String key = readString(in);
        return key == null ? null : MessageKey.of(key);
    }

    public static void writeStringArray(DataOutputStream o, String[] a) throws IOException {
        if (a == null) {
            o.writeInt(-1);
            return;
        }
        o.writeInt(a.length);
        for (String s : a) {
            writeString(o, s);
        }
    }

    public static String[] readStringArray(DataInputStream in) throws IOException {
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

    public static void writeLongArray(DataOutputStream o, long[] a) throws IOException {
        if (a == null) {
            o.writeInt(-1);
            return;
        }
        o.writeInt(a.length);
        for (long v : a) {
            o.writeLong(v);
        }
    }

    public static long[] readLongArray(DataInputStream in) throws IOException {
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

    public static void writeReasonCode(DataOutputStream o, ReasonCode r) throws IOException {
        writeString(o, r == null ? null : r.name());
    }

    public static ReasonCode readReasonCode(DataInputStream in) throws IOException {
        String name = readString(in);
        return name == null ? null : ReasonCode.valueOf(name);
    }

    public static void writeAuditAction(DataOutputStream o, FactionAuditAction a) throws IOException {
        writeString(o, a == null ? null : a.id());
    }

    public static FactionAuditAction readAuditAction(DataInputStream in) throws IOException {
        return FactionAuditAction.fromId(readString(in));
    }
}
