package dev.fablemc.factions.core.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.fablemc.factions.kernel.effect.Effect;

/**
 * Replays the journal tail into the projector at boot (proposal-C §6.3). Records are read in
 * segment-index order; within a segment, replay stops at the first truncated or CRC-mismatched
 * record — the <b>clean prefix</b> — because that is exactly the {@code kill -9} boundary
 * (proposal-C §6.1). Everything after a torn record is discarded (it was never fully synced).
 *
 * <p><b>Owning thread(s):</b> boot thread, single-threaded, before the pipeline serves.
 * <b>Mutability:</b> stateless static helper.
 */
public final class JournalReplay {

    /** A hard sanity cap on a single record's payload length (a larger {@code len} ⇒ corruption). */
    private static final int MAX_PAYLOAD = 128 * 1024 * 1024;

    private JournalReplay() {
    }

    /** Receives each decoded effect during replay, in commit order. */
    @FunctionalInterface
    public interface Visitor {
        void accept(Effect effect);
    }

    /** The outcome of a replay pass. */
    public record ReplayResult(long recordsReplayed, long lastSeq, boolean truncated) {
    }

    /** Replays every record in the journal directory. */
    public static ReplayResult replay(Path dir, Visitor visitor) {
        return replayFrom(dir, Long.MIN_VALUE, visitor);
    }

    /**
     * Replays only records whose {@code seq > afterSeqExclusive} (the storage checkpoint), in
     * order, feeding each to {@code visitor}. Returns the count, the last seq seen, and whether a
     * torn/corrupt tail was encountered.
     */
    public static ReplayResult replayFrom(Path dir, long afterSeqExclusive, Visitor visitor) {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(visitor, "visitor");
        long count = 0;
        long lastSeq = afterSeqExclusive;
        boolean truncated = false;
        for (Path segment : segments(dir)) {
            SegmentResult r = replaySegment(segment, afterSeqExclusive, lastSeq, count, visitor);
            count = r.count;
            lastSeq = r.lastSeq;
            if (r.truncated) {
                truncated = true;
                break;   // a torn tail terminates replay — nothing valid follows it
            }
        }
        return new ReplayResult(count, lastSeq, truncated);
    }

    /** The ordered list of segment files in a journal directory. */
    public static List<Path> segments(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var stream = Files.newDirectoryStream(dir, "seg-*.fj")) {
            for (Path p : stream) {
                out.add(p);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to list journal segments in " + dir, ex);
        }
        out.sort((a, b) -> Long.compare(indexOf(a), indexOf(b)));
        return out;
    }

    private static SegmentResult replaySegment(Path segment, long afterSeq, long lastSeq,
                                               long count, Visitor visitor) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(segment);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read segment " + segment, ex);
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        while (bb.remaining() > 0) {
            if (bb.remaining() < 8) {
                return new SegmentResult(count, lastSeq, true);   // torn header
            }
            int len = bb.getInt();
            int crc = bb.getInt();
            if (len < 0 || len > MAX_PAYLOAD || bb.remaining() < 10 + len) {
                return new SegmentResult(count, lastSeq, true);   // torn / bogus record
            }
            byte[] body = new byte[10 + len];
            bb.get(body);
            if (Crc32c.compute(body) != crc) {
                return new SegmentResult(count, lastSeq, true);   // corrupt → clean prefix
            }
            ByteBuffer bodyBuf = ByteBuffer.wrap(body);
            long seq = bodyBuf.getLong();
            int type = bodyBuf.getShort() & 0xFFFF;
            byte[] payload = new byte[len];
            bodyBuf.get(payload);
            Effect effect = JournalCodec.decode(type, seq, payload);
            if (seq > afterSeq) {
                visitor.accept(effect);
                count++;
                lastSeq = seq;
            }
        }
        return new SegmentResult(count, lastSeq, false);
    }

    private static long indexOf(Path segment) {
        String name = segment.getFileName().toString();
        String mid = name.substring("seg-".length(), name.length() - ".fj".length());
        try {
            return Long.parseLong(mid);
        } catch (NumberFormatException ex) {
            return Long.MAX_VALUE;
        }
    }

    private record SegmentResult(long count, long lastSeq, boolean truncated) {
    }
}
