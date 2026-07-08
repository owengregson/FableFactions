package dev.fablemc.factions.core.integration.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.Nullable;

import dev.fablemc.factions.core.pipeline.SnapshotHub;

/**
 * The typed {@code %fable_*%} PlaceholderAPI expansion (ref-integrations §3.2). Loaded ONLY by FQN
 * string from {@link PlaceholderHook} behind the PlaceholderAPI presence gate — never import this
 * class elsewhere: its supertype lives in the soft-dep jar and linking it without PlaceholderAPI
 * installed throws. Every parameter resolves through {@link PlaceholderData} as a wait-free
 * snapshot read.
 *
 * <p><b>Owning thread(s):</b> constructed on the boot thread; {@link #onRequest} runs on whatever
 * thread PlaceholderAPI resolves placeholders from (snapshot reads are thread-agnostic).
 * <b>Mutability:</b> immutable.
 */
public final class FableExpansion extends PlaceholderExpansion {

    private final SnapshotHub snapshots;
    private final String pluginVersion;

    /** Constructor invoked reflectively by {@link PlaceholderHook} (presence-gated). */
    public FableExpansion(SnapshotHub snapshots, String pluginVersion) {
        this.snapshots = snapshots;
        this.pluginVersion = pluginVersion;
    }

    @Override
    public String getIdentifier() {
        return PlaceholderHook.IDENTIFIER;
    }

    @Override
    public String getAuthor() {
        return "FableMC";
    }

    @Override
    public String getVersion() {
        return pluginVersion;
    }

    /** Survives PlaceholderAPI's own {@code /papi reload}; FableFactions owns the lifecycle. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    @Nullable
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return null;
        }
        return PlaceholderData.resolve(snapshots.current(), player.getUniqueId(), params);
    }
}
