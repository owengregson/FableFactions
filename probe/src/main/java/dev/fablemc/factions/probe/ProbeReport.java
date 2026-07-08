package dev.fablemc.factions.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Accumulates probe suite outcomes and writes them, nonce-tagged, to {@code probe-results.txt} in the
 * server working directory (the Mental tester pattern's integration hand-off, work order W3f). The
 * CI matrix greps the nonce line to confirm the probe actually ran on this boot.
 *
 * <p>Owning thread(s): the Bukkit plugin-lifecycle thread only (the probe runs its suites inline in
 * {@code onEnable}). Mutability class: confined — the result list is appended by one thread and
 * flushed once.
 */
final class ProbeReport {

    /** One suite outcome: its id, pass flag, and a human-readable detail line. */
    record Result(String suite, boolean pass, String detail) {
    }

    private static final String RESULTS_FILE = "probe-results.txt";

    private final String nonce;
    private final Logger logger;
    private final List<Result> results = new ArrayList<>();

    ProbeReport(String nonce, Logger logger) {
        this.nonce = nonce;
        this.logger = logger;
    }

    /** Records a suite outcome and echoes it to the console. */
    void add(String suite, boolean pass, String detail) {
        Result result = new Result(suite, pass, detail);
        results.add(result);
        logger.info(line(result));
    }

    /** {@code true} when every recorded suite passed. */
    boolean allPassed() {
        for (Result result : results) {
            if (!result.pass()) {
                return false;
            }
        }
        return true;
    }

    /** Flushes the nonce header and every result to {@code probe-results.txt}. */
    void flush() {
        File file = new File(RESULTS_FILE);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write("FableFactions probe results\n");
            writer.write("nonce=" + nonce + "\n");
            writer.write("allPassed=" + allPassed() + "\n");
            for (Result result : results) {
                writer.write(line(result) + "\n");
            }
        } catch (IOException failed) {
            logger.warning("failed to write " + file.getAbsolutePath() + ": " + failed.getMessage());
        }
    }

    private static String line(Result result) {
        return (result.pass() ? "[PASS] " : "[FAIL] ") + result.suite() + " - " + result.detail();
    }
}
