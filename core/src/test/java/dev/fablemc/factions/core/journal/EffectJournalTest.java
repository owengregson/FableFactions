package dev.fablemc.factions.core.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.fablemc.factions.kernel.effect.Effect;
import dev.fablemc.factions.kernel.effect.LifecycleEffect;
import dev.fablemc.factions.kernel.intent.Origin;

/**
 * The write-ahead journal's crash-safety contract (work order W2b §3): a truncated tail replays
 * only its clean prefix, segments roll at the size bound while keeping every record whole, and a
 * CRC-corrupt record is rejected at exactly the clean prefix boundary (proposal-C §6.1).
 */
final class EffectJournalTest {

    private static final Origin ORIGIN = Origin.player(new UUID(1, 1));

    private static Effect sample(long seq) {
        return new LifecycleEffect.FactionCreated(seq, ORIGIN, (int) seq, new UUID(seq, seq), "Faction" + seq);
    }

    private static List<Long> replaySeqs(Path dir, JournalReplay.ReplayResult[] out) {
        List<Long> seqs = new ArrayList<>();
        JournalReplay.ReplayResult r = JournalReplay.replay(dir, e -> seqs.add(e.seq()));
        out[0] = r;
        return seqs;
    }

    @Test
    void truncatedTailReplaysCleanPrefix(@TempDir Path dir) throws IOException {
        List<Effect> batch = new ArrayList<>();
        for (long i = 0; i < 6; i++) {
            batch.add(sample(i));
        }
        try (EffectJournal journal = new EffectJournal(dir)) {
            journal.accept(batch, 5L);
        }

        Path seg = JournalReplay.segments(dir).get(0);
        long size = Files.size(seg);
        // Shear 3 bytes off the middle of the last record's payload → the tail is torn.
        try (FileChannel ch = FileChannel.open(seg, StandardOpenOption.WRITE)) {
            ch.truncate(size - 3);
        }

        JournalReplay.ReplayResult[] res = new JournalReplay.ReplayResult[1];
        List<Long> seqs = replaySeqs(dir, res);

        assertTrue(res[0].truncated(), "a sheared tail must be reported truncated");
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), seqs, "replay yields exactly the clean prefix");
        assertEquals(5, res[0].recordsReplayed());
        assertEquals(4L, res[0].lastSeq());
    }

    @Test
    void segmentsRollKeepingRecordsWhole(@TempDir Path dir) throws IOException {
        // Small cap forces several rolls; each record stays whole in its own segment.
        try (EffectJournal journal = new EffectJournal(dir, 96L)) {
            for (long i = 0; i < 8; i++) {
                journal.accept(List.of(sample(i)), i);
            }
            assertTrue(journal.currentSegmentIndex() > 0, "the log must have rolled at least once");
        }

        assertTrue(JournalReplay.segments(dir).size() >= 2, "expected multiple segment files");

        JournalReplay.ReplayResult[] res = new JournalReplay.ReplayResult[1];
        List<Long> seqs = replaySeqs(dir, res);

        assertFalse(res[0].truncated(), "a cleanly rolled log has no torn tail");
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L), seqs, "all records replay in order");
        assertEquals(8, res[0].recordsReplayed());
    }

    @Test
    void crcMismatchIsRejectedAtCleanPrefix(@TempDir Path dir) throws IOException {
        List<Effect> batch = new ArrayList<>();
        for (long i = 0; i < 4; i++) {
            batch.add(sample(i));
        }
        try (EffectJournal journal = new EffectJournal(dir)) {
            journal.accept(batch, 3L);
        }

        Path seg = JournalReplay.segments(dir).get(0);
        byte[] bytes = Files.readAllBytes(seg);
        // Flip a bit in the LAST record's payload (last byte) — length intact, CRC now wrong.
        bytes[bytes.length - 1] ^= 0x7F;
        Files.write(seg, bytes);

        JournalReplay.ReplayResult[] res = new JournalReplay.ReplayResult[1];
        List<Long> seqs = replaySeqs(dir, res);

        assertTrue(res[0].truncated(), "a CRC mismatch terminates replay at the clean prefix");
        assertEquals(List.of(0L, 1L, 2L), seqs, "records before the corrupt one still replay");
        assertEquals(3, res[0].recordsReplayed());
    }

    @Test
    void midStreamCorruptionInAnEarlierSegmentFailsLoudly(@TempDir Path dir) throws IOException {
        // Small cap → several segments. A torn record is only *expected* at the true tail; corruption
        // in an EARLIER segment must NOT silently drop the later (valid, committed) segments (finding #9).
        try (EffectJournal journal = new EffectJournal(dir, 96L)) {
            for (long i = 0; i < 8; i++) {
                journal.accept(List.of(sample(i)), i);
            }
        }
        List<Path> segs = JournalReplay.segments(dir);
        assertTrue(segs.size() >= 2, "need a non-final segment to corrupt");

        // Flip a byte in the FIRST segment's last record → CRC mismatch mid-stream.
        Path first = segs.get(0);
        byte[] bytes = Files.readAllBytes(first);
        bytes[bytes.length - 1] ^= 0x7F;
        Files.write(first, bytes);

        assertThrows(JournalReplay.CorruptTailException.class,
                () -> JournalReplay.replay(dir, e -> { }),
                "mid-stream corruption must fail loudly, never silently drop later segments");
    }

    @Test
    void tornTailOnTheLastSegmentTrimsCleanlyAcrossSegments(@TempDir Path dir) throws IOException {
        try (EffectJournal journal = new EffectJournal(dir, 96L)) {
            for (long i = 0; i < 8; i++) {
                journal.accept(List.of(sample(i)), i);
            }
        }
        List<Path> segs = JournalReplay.segments(dir);
        assertTrue(segs.size() >= 2, "expected multiple segments");

        // Shear the LAST segment's final record — the expected kill -9 boundary: trim, do not throw.
        Path last = segs.get(segs.size() - 1);
        long size = Files.size(last);
        try (FileChannel ch = FileChannel.open(last, StandardOpenOption.WRITE)) {
            ch.truncate(size - 3);
        }

        JournalReplay.ReplayResult[] res = new JournalReplay.ReplayResult[1];
        List<Long> seqs = replaySeqs(dir, res);
        assertTrue(res[0].truncated(), "a sheared true tail is reported truncated, not fatal");
        assertEquals(7, seqs.size(), "every record before the torn last one still replays");
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L), seqs);
    }

    @Test
    void fsyncBarrierAndRollDoNotLoseRecords(@TempDir Path dir) throws IOException {
        try (EffectJournal journal = new EffectJournal(dir)) {
            journal.accept(List.of(sample(0)), 0L);
            journal.fsyncBarrier();   // CRITICAL-tier barrier (AM-17) is a no-op for correctness here
            journal.accept(List.of(sample(1)), 1L);
        }
        JournalReplay.ReplayResult[] res = new JournalReplay.ReplayResult[1];
        List<Long> seqs = replaySeqs(dir, res);
        assertEquals(List.of(0L, 1L), seqs);
        assertFalse(res[0].truncated());
    }
}
