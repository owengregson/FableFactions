package dev.fablemc.factions.core.pipeline;

/**
 * The callback the writer invokes when its thread dies of an unrecoverable {@link Throwable}
 * (an {@link Error} escaping the per-intent boundary) — a dead pipeline must never look healthy,
 * so the implementation disables the plugin loudly (AM-9). Injected at {@link WriterThread}
 * construction so the pipeline package carries <b>no</b> Bukkit dependency: Wave 4 supplies an
 * implementation that calls {@code PluginManager.disablePlugin} via {@code Scheduling.runGlobal}.
 *
 * <p><b>Owning thread(s):</b> invoked from the dying writer thread (and its uncaught-exception
 * handler). <b>Mutability:</b> the implementation is responsible for its own thread-safety.
 */
@FunctionalInterface
public interface FailureHandler {

    /** A handler that ignores the failure — used only in tests. */
    FailureHandler IGNORE = t -> { };

    /** Called once when the writer has failed fatally; {@code cause} is the escaping throwable. */
    void onWriterFailed(Throwable cause);
}
