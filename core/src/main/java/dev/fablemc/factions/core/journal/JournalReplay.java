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
 * (proposal-C §6.1).
 *
 * <p><b>Torn record placement matters (finding #9).</b> A torn/corrupt frame is only <em>expected</em>
 * at the true tail — the last segment, where a {@code kill -9} sheared the final unsynced batch. A
 * torn frame in an <em>earlier</em> segment (one that has a valid successor) is genuine mid-stream
 * corruption: silently trimming there would discard every later valid segment — recoverable,
 * fsync-committed effects. That case throws {@link CorruptTailException} (a loud, boot-failing
 * error identifying the segment + byte offset) rather than dropping data; a clean {@code kill -9}
 * tail on the final segment is trimmed and reported {@code truncated}.
 *
 * <p><b>Owning thread(s):</b> boot thread, single-threaded, before the pipeline serves.
 * <b>Mutability:</b> stateless static helper.
 */
public final class JournalReplay {

    /**
     * Thrown when a torn/CRC-corrupt record is found in a journal segment that is <b>not</b> the
     * final one (mid-stream corruption). Boot fails loudly rather than silently dropping the valid
     * segments that follow it — never lose fsync-committed effects (finding #9).
     */
    public static final class CorruptTailException extends RuntimeException {
        public CorruptTailException(Path segment, long offset, int segmentIndex, int segmentCount) {
            super("journal corruption mid-stream: torn/CRC-bad record in segment " + segment
                    + " at byte offset " + offset + " (segment " + (segmentIndex + 1) + " of "
                    + segmentCount + " — later segments hold committed effects). Refusing to boot and "
                    + "silently drop them; inspect/repair the journal (proposal-C §6.1, AM-17).");
        }
    }

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
        List<Path> segments = segments(dir);
        long count = 0;
        long lastSeq = afterSeqExclusive;
        boolean truncated = false;
        for (int i = 0; i < segments.size(); i++) {
            Path segment = segments.get(i);
            SegmentResult r = replaySegment(segment, afterSeqExclusive, lastSeq, count, visitor);
            count = r.count;
            lastSeq = r.lastSeq;
            if (r.truncated) {
                boolean lastSegment = i == segments.size() - 1;
                if (!lastSegment) {
                    // Mid-stream corruption: a torn record with valid segments still to come. Trimming
                    // here would silently drop committed effects — fail loudly instead (finding #9).
                    throw new CorruptTailException(segment, r.tornOffset, i, segments.size());
                }
                truncated = true;   // the expected kill -9 boundary at the true tail
                break;
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
        try (var stream = Files.newDirectoryStream(dir,
                EffectJournal.SEGMENT_PREFIX + "*" + EffectJournal.SEGMENT_SUFFIX)) {
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
            int recordStart = bb.position();
            if (bb.remaining() < EffectJournal.FRAME_PREFIX_BYTES) {
                return new SegmentResult(count, lastSeq, true, recordStart);   // torn header
            }
            int len = bb.getInt();
            int crc = bb.getInt();
            if (len < 0 || len > MAX_PAYLOAD
                    || bb.remaining() < EffectJournal.BODY_PREFIX_BYTES + len) {
                return new SegmentResult(count, lastSeq, true, recordStart);   // torn / bogus record
            }
            byte[] body = new byte[EffectJournal.BODY_PREFIX_BYTES + len];
            bb.get(body);
            if (Crc32c.compute(body) != crc) {
                return new SegmentResult(count, lastSeq, true, recordStart);   // corrupt → clean prefix
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
        return new SegmentResult(count, lastSeq, false, bb.position());
    }

    private static long indexOf(Path segment) {
        String name = segment.getFileName().toString();
        String mid = name.substring(EffectJournal.SEGMENT_PREFIX.length(),
                name.length() - EffectJournal.SEGMENT_SUFFIX.length());
        try {
            return Long.parseLong(mid);
        } catch (NumberFormatException ex) {
            return Long.MAX_VALUE;
        }
    }

    private record SegmentResult(long count, long lastSeq, boolean truncated, long tornOffset) {
    }
}
