package dev.fablemc.factions.core.config;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * The configuration-reload seam behind {@code /fa reload} (ref-commands-admin.md, ARCHITECTURE
 * AM-14: config is state, reload is an intent). The command layer never parses YAML on the
 * server thread; it calls {@link #reload()} and the implementation (W3e {@code ConfigParser})
 * parses off-thread, submits a {@code SwapConfig} intent so the new {@code ConfigImage} is
 * published atomically as part of the next snapshot, and returns any parse issues.
 *
 * <p><b>Owning thread(s):</b> {@link #reload()} is called from a command {@code perform} on the
 * server thread and returns immediately; the returned stage completes on the parse thread.
 * <b>Mutability:</b> the implementation is stateless per call; this interface is a pure seam.
 */
public interface Reloads {

    /**
     * Reparses every configuration file off-thread and swaps the resulting image into the kernel via
     * a {@code SwapConfig} intent. The returned stage completes with the list of human-readable parse
     * issues (empty when the reload was clean); the command renders a success or a warning summary
     * from it.
     */
    CompletionStage<List<String>> reload();
}
