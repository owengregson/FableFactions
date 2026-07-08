package dev.fablemc.factions.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.intent.SystemIntent;
import dev.fablemc.factions.platform.sched.Scheduling;

/**
 * Implements the {@link Reloads} seam behind {@code /fa reload}: it reparses every config file
 * off-thread and swaps the resulting image into the kernel via a {@code SwapConfig} intent
 * (ARCHITECTURE AM-14 — config is state, reload is an intent). The command {@code perform} calls
 * {@link #reload()} on the server thread and returns immediately; the returned stage completes on
 * the parse thread with the list of per-key issues.
 *
 * <p><b>Owning thread(s):</b> {@link #reload()} is invoked from a command handler (server/region
 * thread); the parse + submit run on the {@link Scheduling#runAsync} pool; the stage completes
 * there. No JDBC, no kernel-state construction — only a parse and an {@link IntentBus#submitSystem}
 * (CONTRACTS §4). <b>Mutability:</b> immutable holder of its injected collaborators.
 */
public final class ReloadsImpl implements Reloads {

    private final ConfigFiles files;
    private final IntentBus bus;
    private final Scheduling scheduling;
    @Nullable
    private final ToIntFunction<String> worldIndex;
    private final UnaryOperator<ConfigImage> finalizer;

    /**
     * @param files      the file loader (extract/load/overlay/migrate)
     * @param bus        the intent bus the {@code SwapConfig} is submitted onto (system lane)
     * @param scheduling supplies the off-thread parse pool ({@link Scheduling#runAsync})
     * @param worldIndex resolves world names to {@code worldIdx} for baked world multipliers /
     *                   predefined presets; {@code null} to parse world-blind
     * @param finalizer  fills the parse-blind {@link dev.fablemc.factions.kernel.config.BakedTables}
     *                   material bitsets (and any boot-known worlds) before the swap; identity when
     *                   the caller has nothing to add
     */
    public ReloadsImpl(ConfigFiles files, IntentBus bus, Scheduling scheduling,
                       @Nullable ToIntFunction<String> worldIndex, UnaryOperator<ConfigImage> finalizer) {
        this.files = Objects.requireNonNull(files, "files");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.worldIndex = worldIndex;
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
    }

    @Override
    public CompletionStage<List<String>> reload() {
        CompletableFuture<List<String>> result = new CompletableFuture<>();
        scheduling.runAsync(() -> result.complete(reloadNow()));
        return result;
    }

    /**
     * Performs the parse + swap synchronously on the CURRENT thread and returns the issue list. The
     * boot path calls this directly (there is no server thread to free yet); {@link #reload()} wraps
     * it in {@link Scheduling#runAsync}. Never throws — a parse failure becomes an issue string, so
     * a broken config never leaves the kernel without a config image.
     */
    public List<String> reloadNow() {
        List<String> issues = new ArrayList<>();
        try {
            ConfigParser.Sources sources = files.loadAll(issues);
            ConfigImage image = finalizer.apply(ConfigParser.parse(sources, worldIndex, issues));
            bus.submitSystem(new SystemIntent.SwapConfig(image));
        } catch (RuntimeException ex) {
            issues.add("reload failed: " + ex);
        }
        return issues;
    }
}
