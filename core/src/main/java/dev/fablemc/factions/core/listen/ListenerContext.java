package dev.fablemc.factions.core.listen;

import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.core.session.SessionRegistry;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.platform.resolve.Worlds;
import dev.fablemc.factions.platform.sched.Scheduling;

/**
 * The immutable bundle of collaborators every protection / event listener is constructor-injected
 * with (CONTRACTS §4 — no singletons, Wave 4 wires the concrete instances).
 *
 * <p><b>Owning thread(s):</b> constructed once on the boot thread; its fields are read from every
 * region/main thread a listener fires on. <b>Mutability:</b> immutable value — the collaborators it
 * holds are each thread-safe for the way listeners use them ({@link SnapshotHub#current()} is
 * wait-free, {@link IntentBus} submit is lock-free, {@link Messages} marshals its own delivery,
 * {@link SessionRegistry} confines each session to its owner's region thread).
 *
 * <p>A listener body's whole allowed vocabulary lives here: a wait-free snapshot read
 * ({@link #snapshots}), an intent submission ({@link #bus}), user feedback ({@link #messages}), the
 * world-index registry ({@link #worlds}), region marshalling ({@link #scheduling}) and the confined
 * per-player tracking state ({@link #sessions}). No listener ever reaches past this bundle for JDBC,
 * the journal, or kernel-state construction (CONTRACTS §4 threading rules).
 */
public record ListenerContext(
        SnapshotHub snapshots,
        IntentBus bus,
        Messages messages,
        Worlds worlds,
        Scheduling scheduling,
        SessionRegistry sessions) {
}
