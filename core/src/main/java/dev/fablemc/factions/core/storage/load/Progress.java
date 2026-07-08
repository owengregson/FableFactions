package dev.fablemc.factions.core.storage.load;

import java.util.logging.Logger;

/**
 * Emits a {@code [baseline] N%} line each time cumulative rows cross a 10% boundary during a
 * baseline load. Shared by the {@link BaselineLoader} orchestrator and every per-table reader.
 *
 * <p><b>Owning thread(s):</b> the boot thread only. <b>Mutability:</b> mutable, single-thread
 * confined.
 */
final class Progress {

    private final long total;
    private final Logger log;
    private long done;
    private int lastDecile = -1;

    Progress(long total, Logger log) {
        this.total = total;
        this.log = log;
    }

    void tick() {
        done++;
        if (total <= 0) {
            return;
        }
        int decile = (int) (done * 10 / total);
        if (decile > lastDecile && decile <= 10) {
            lastDecile = decile;
            log.info("[baseline] " + (decile * 10) + "% (" + done + "/" + total + " rows)");
        }
    }
}
