package dev.fablemc.factions.core.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The boot report accumulator (proposal-C §7.1, mental-seam.md §8 — no silent degradation, B10).
 * Each subsystem that comes up (or is deliberately disabled) contributes exactly one line as it is
 * wired, so a server admin reading the console sees the whole assembled system at a glance and any
 * degraded feature is visible rather than silent.
 *
 * <p>Lines are emitted immediately through the injected sink (so ordering matches boot ordering) and
 * also retained so {@link #lines()} can be asserted in a headless boot test. The {@link #summary}
 * line is the single final fact line (version / backend / counts / journal seq, §7).
 *
 * <p><b>Owning thread(s):</b> the boot thread only. <b>Mutability:</b> confined, single-threaded.
 */
public final class BootReport {

    private final Consumer<String> sink;
    private final List<String> lines = new ArrayList<>();

    /** @param sink where each line is written as it is recorded (typically {@code logger::info}). */
    public BootReport(Consumer<String> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** Records one subsystem line ({@code "<subsystem>: <detail>"}) and emits it immediately. */
    public void line(String subsystem, String detail) {
        String rendered = "[boot] " + subsystem + ": " + detail;
        lines.add(rendered);
        sink.accept(rendered);
    }

    /** Records a raw line verbatim (used for the platform's own one-line reports). */
    public void raw(String line) {
        String rendered = "[boot] " + line;
        lines.add(rendered);
        sink.accept(rendered);
    }

    /** The final fact line (proposal-C §7, step 7): version / backends / counts / journal seq. */
    public void summary(String detail) {
        String rendered = "[boot] READY — " + detail;
        lines.add(rendered);
        sink.accept(rendered);
    }

    /** Every recorded line, in emission order (for the headless boot-order test). */
    public List<String> lines() {
        return List.copyOf(lines);
    }
}
