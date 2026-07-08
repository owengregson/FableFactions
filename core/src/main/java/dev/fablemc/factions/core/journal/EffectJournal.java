package dev.fablemc.factions.core.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

import dev.fablemc.factions.core.pipeline.EffectSink;
import dev.fablemc.factions.kernel.effect.Effect;

/**
 * The crash-safety authority: a segmented write-ahead log of every effect batch (proposal-C
 * §6.1). Each record is framed {@code [u32 len][u32 crc32c][u64 seq][u16 type][payload]} where
 * {@code len} is the payload length and the CRC-32C covers {@code seq || type || payload}. A
 * {@code kill -9} loses at most the last unsynced batch, never a torn row — a partial or
 * corrupt tail record is rejected by {@link JournalReplay} at the clean prefix.
 *
 * <p><b>Owning thread(s):</b> the single writer thread ({@link #accept}) and boot; not
 * thread-safe. <b>Mutability:</b> owns a mutable {@link FileChannel} for the current segment.
 *
 * <p>Durability: {@link #accept} performs a group-commit {@code fsync} at the end of each batch;
 * {@link #fsyncBarrier()} forces an immediate {@code fsync} for the CRITICAL tier (AM-17,
 * used to gate user-facing confirmations for bank/escrow/chest/disband). Segments roll at
 * {@code maxSegmentBytes} (64&nbsp;MB in production) keeping every record whole.
 */
public final class EffectJournal implements EffectSink, AutoCloseable {

    /** Production segment roll size (64&nbsp;MB). */
    public static final long DEFAULT_SEGMENT_BYTES = 64L * 1024 * 1024;

    private static final String PREFIX = "seg-";
    private static final String SUFFIX = ".fj";

    private final Path dir;
    private final long maxSegmentBytes;

    private int segmentIndex;
    private FileChannel channel;
    private long segmentBytes;

    public EffectJournal(Path dir, long maxSegmentBytes) {
        this.dir = Objects.requireNonNull(dir, "dir");
        if (maxSegmentBytes < 64) {
            throw new IllegalArgumentException("maxSegmentBytes too small");
        }
        this.maxSegmentBytes = maxSegmentBytes;
        try {
            Files.createDirectories(dir);
            this.segmentIndex = nextFreshIndex(dir);
            openSegment();
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to open journal at " + dir, ex);
        }
    }

    public EffectJournal(Path dir) {
        this(dir, DEFAULT_SEGMENT_BYTES);
    }

    /** The path a segment file with the given index lives at (also used by replay). */
    public static Path segmentPath(Path dir, int index) {
        return dir.resolve(String.format("%s%010d%s", PREFIX, index, SUFFIX));
    }

    /** The directory this journal writes into. */
    public Path directory() {
        return dir;
    }

    /** The index of the segment currently being appended to. */
    public int currentSegmentIndex() {
        return segmentIndex;
    }

    @Override
    public void accept(List<Effect> batch, long lastSeq) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        try {
            for (int i = 0; i < batch.size(); i++) {
                writeRecord(batch.get(i));
            }
            channel.force(true);   // group-commit fsync per batch
        } catch (IOException ex) {
            throw new UncheckedIOException("journal append failed", ex);
        }
    }

    /** Forces an immediate {@code fsync} — the CRITICAL-tier durability barrier (AM-17). */
    public void fsyncBarrier() {
        try {
            channel.force(true);
        } catch (IOException ex) {
            throw new UncheckedIOException("journal fsync failed", ex);
        }
    }

    @Override
    public void close() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.force(true);
                channel.close();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("journal close failed", ex);
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────

    private void writeRecord(Effect effect) throws IOException {
        byte[] payload = JournalCodec.encode(effect);
        int type = JournalCodec.tagOf(effect);
        long seq = effect.seq();

        byte[] body = new byte[10 + payload.length];
        ByteBuffer b = ByteBuffer.wrap(body);
        b.putLong(seq);
        b.putShort((short) type);
        b.put(payload);
        int crc = Crc32c.compute(body);

        int recordBytes = 8 + body.length;     // len(4) + crc(4) + body
        if (segmentBytes > 0 && segmentBytes + recordBytes > maxSegmentBytes) {
            roll();
        }

        ByteBuffer rec = ByteBuffer.allocate(recordBytes);
        rec.putInt(payload.length);
        rec.putInt(crc);
        rec.put(body);
        rec.flip();
        while (rec.hasRemaining()) {
            channel.write(rec);
        }
        segmentBytes += recordBytes;
    }

    private void roll() throws IOException {
        channel.force(true);
        channel.close();
        segmentIndex++;
        openSegment();
    }

    private void openSegment() throws IOException {
        Path path = segmentPath(dir, segmentIndex);
        this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        this.segmentBytes = channel.size();
    }

    private static int nextFreshIndex(Path dir) throws IOException {
        int max = -1;
        try (var stream = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String mid = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
                try {
                    max = Math.max(max, Integer.parseInt(mid));
                } catch (NumberFormatException ignored) {
                    // not one of our segments
                }
            }
        }
        return max + 1;   // always append into a fresh segment (torn tails stay read-only)
    }
}
