package dev.fablemc.factions.core.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import dev.fablemc.factions.core.pipeline.EffectSink;
import dev.fablemc.factions.kernel.effect.Effect;

/**
 * The crash-safety authority: a segmented write-ahead log of every effect batch (proposal-C
 * §6.1). Each record is framed {@code [u32 len][u32 crc32c][u64 seq][u16 type][payload]} where
 * {@code len} is the payload length and the CRC-32C covers {@code seq || type || payload}. A
 * {@code kill -9} loses at most the last unsynced batch, never a torn row — a partial or
 * corrupt tail record is rejected by {@link JournalReplay} at the clean prefix.
 *
 * <p><b>Durability &amp; the writer (AM-17, finding #8).</b> {@link #accept} performs only the
 * (fast, buffered) record <em>writes</em> on the {@code fable-kernel} writer thread; the
 * {@code fsync} that makes them durable runs on a dedicated {@code journal-sync} executor — the
 * writer never blocks on {@code fsync}. Each completed {@code fsync} fires {@link FsyncAck} with the
 * durable seq, the seam a CRITICAL-tier confirmation waits on before it is shown to the user
 * (STATE-tier feedback releases immediately without waiting). {@link #fsyncBarrier()} forces an
 * immediate synchronous {@code fsync} for the ordered shutdown drain. Segments roll at
 * {@code maxSegmentBytes} (64&nbsp;MB in production) keeping every record whole.
 *
 * <p><b>Owning thread(s):</b> {@link #accept}/roll on the single writer thread; the {@code fsync}
 * on the {@code journal-sync} thread; {@link #fsyncBarrier()}/{@link #close()} on the boot/disable
 * thread. Roll (close+open of the channel) and every {@code fsync} are serialized on
 * {@link #syncLock} so an {@code fsync} can never race a segment close; normal record writes take no
 * lock, so the writer is never blocked by an in-flight {@code fsync} except at the rare (per-64&nbsp;MB)
 * roll boundary. <b>Mutability:</b> owns the current segment channel + the two seq watermarks.
 */
public final class EffectJournal implements EffectSink, AutoCloseable {

    /** Production segment roll size (64&nbsp;MB). */
    public static final long DEFAULT_SEGMENT_BYTES = 64L * 1024 * 1024;

    /** Fired on the {@code journal-sync} thread once records up to {@code durableSeq} are fsynced (AM-17). */
    @FunctionalInterface
    public interface FsyncAck {
        void onFsynced(long durableSeq);
    }

    // Segment file naming, shared with JournalReplay (the read side of this format).
    static final String SEGMENT_PREFIX = "seg-";
    static final String SEGMENT_SUFFIX = ".fj";

    /** Frame prefix ahead of the CRC-covered body: {@code len(4) + crc(4)}. */
    static final int FRAME_PREFIX_BYTES = 8;
    /** Body prefix ahead of the payload (CRC-covered): {@code seq(8) + type(2)}. */
    static final int BODY_PREFIX_BYTES = 10;

    private final Path dir;
    private final long maxSegmentBytes;
    private final FsyncAck ack;

    private final ReentrantLock syncLock = new ReentrantLock();
    private final ExecutorService syncExecutor;
    private final AtomicBoolean syncScheduled = new AtomicBoolean(false);
    private final AtomicLong writtenSeq = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong syncedSeq = new AtomicLong(Long.MIN_VALUE);

    private int segmentIndex;
    private volatile FileChannel channel;
    private long segmentBytes;

    public EffectJournal(Path dir, long maxSegmentBytes, FsyncAck ack) {
        this.dir = Objects.requireNonNull(dir, "dir");
        if (maxSegmentBytes < 64) {
            throw new IllegalArgumentException("maxSegmentBytes too small");
        }
        this.maxSegmentBytes = maxSegmentBytes;
        this.ack = ack;
        this.syncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "journal-sync");
            t.setDaemon(true);
            return t;
        });
        try {
            Files.createDirectories(dir);
            this.segmentIndex = nextFreshIndex(dir);
            openSegment();
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to open journal at " + dir, ex);
        }
    }

    public EffectJournal(Path dir, long maxSegmentBytes) {
        this(dir, maxSegmentBytes, null);
    }

    public EffectJournal(Path dir) {
        this(dir, DEFAULT_SEGMENT_BYTES, null);
    }

    /** The path a segment file with the given index lives at (also used by replay). */
    public static Path segmentPath(Path dir, int index) {
        return dir.resolve(String.format("%s%010d%s", SEGMENT_PREFIX, index, SEGMENT_SUFFIX));
    }

    /** The directory this journal writes into. */
    public Path directory() {
        return dir;
    }

    /** The index of the segment currently being appended to. */
    public int currentSegmentIndex() {
        return segmentIndex;
    }

    /** The highest seq durably fsynced so far, or {@link Long#MIN_VALUE} before the first sync. */
    public long durableSeq() {
        return syncedSeq.get();
    }

    @Override
    public void accept(List<Effect> batch, long lastSeq) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        // Records are WRITTEN on the writer thread (fast, buffered); the fsync is handed to the
        // journal-sync executor so the writer never blocks on durability (AM-17, finding #8).
        try {
            for (int i = 0; i < batch.size(); i++) {
                writeRecord(batch.get(i));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("journal append failed", ex);
        }
        writtenSeq.set(lastSeq);
        scheduleSync();
    }

    /** Coalescing hand-off: at most one pending sync task, which flushes to the latest written seq. */
    private void scheduleSync() {
        if (syncScheduled.compareAndSet(false, true)) {
            try {
                syncExecutor.execute(this::syncPending);
            } catch (RuntimeException rejected) {
                // Executor already shut down (close in progress): fold the sync inline so nothing is lost.
                syncScheduled.set(false);
                syncPending();
            }
        }
    }

    private void syncPending() {
        syncScheduled.set(false);
        long target = writtenSeq.get();
        if (syncedSeq.get() >= target) {
            return;
        }
        forceTo(target);
    }

    /** Forces the current segment and advances {@link #syncedSeq} to {@code target}, then acks. */
    private void forceTo(long target) {
        syncLock.lock();
        boolean flushed = false;
        try {
            FileChannel ch = channel;
            if (ch != null && ch.isOpen()) {
                ch.force(true);
            }
            flushed = true;
        } catch (ClosedChannelException rolled) {
            // A roll closed this segment after forcing it; the current channel holds the rest. The
            // next scheduled sync (or the roll's own force) covers target — safe to treat as flushed.
            flushed = true;
        } catch (IOException ex) {
            throw new UncheckedIOException("journal fsync failed", ex);
        } finally {
            // Advance the watermark ONLY for what was actually flushed (never on an fsync failure —
            // that would claim durability that did not happen), and ALWAYS release the lock.
            if (flushed) {
                long prev;
                do {
                    prev = syncedSeq.get();
                    if (prev >= target) {
                        break;
                    }
                } while (!syncedSeq.compareAndSet(prev, target));
            }
            syncLock.unlock();
        }
        if (ack != null) {
            ack.onFsynced(target);
        }
    }

    /**
     * Forces an immediate synchronous {@code fsync} up to the last written seq — the CRITICAL-tier /
     * ordered-shutdown durability barrier (AM-17). Runs on the CALLING thread (boot/disable), not the
     * writer.
     */
    public void fsyncBarrier() {
        forceTo(writtenSeq.get());
    }

    @Override
    public void close() {
        // Stop accepting new async syncs, drain the pending one, then a final synchronous force+close.
        syncExecutor.shutdown();
        try {
            syncExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        syncLock.lock();
        try {
            FileChannel ch = channel;
            if (ch != null && ch.isOpen()) {
                ch.force(true);
                ch.close();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("journal close failed", ex);
        } finally {
            syncLock.unlock();
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────

    private void writeRecord(Effect effect) throws IOException {
        byte[] payload = JournalCodec.encode(effect);
        int type = JournalCodec.tagOf(effect);
        long seq = effect.seq();

        byte[] body = new byte[BODY_PREFIX_BYTES + payload.length];
        ByteBuffer b = ByteBuffer.wrap(body);
        b.putLong(seq);
        b.putShort((short) type);
        b.put(payload);
        int crc = Crc32c.compute(body);

        int recordBytes = FRAME_PREFIX_BYTES + body.length;
        if (segmentBytes > 0 && segmentBytes + recordBytes > maxSegmentBytes) {
            roll();
        }

        ByteBuffer rec = ByteBuffer.allocate(recordBytes);
        rec.putInt(payload.length);
        rec.putInt(crc);
        rec.put(body);
        rec.flip();
        FileChannel ch = channel;
        while (rec.hasRemaining()) {
            ch.write(rec);
        }
        segmentBytes += recordBytes;
    }

    private void roll() throws IOException {
        // Serialized against the journal-sync fsync so a segment is never closed mid-force.
        syncLock.lock();
        try {
            channel.force(true);
            channel.close();
            segmentIndex++;
            openSegment();
        } finally {
            syncLock.unlock();
        }
    }

    private void openSegment() throws IOException {
        Path path = segmentPath(dir, segmentIndex);
        this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        this.segmentBytes = channel.size();
    }

    private static int nextFreshIndex(Path dir) throws IOException {
        int max = -1;
        try (var stream = Files.newDirectoryStream(dir, SEGMENT_PREFIX + "*" + SEGMENT_SUFFIX)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String mid = name.substring(SEGMENT_PREFIX.length(),
                        name.length() - SEGMENT_SUFFIX.length());
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
