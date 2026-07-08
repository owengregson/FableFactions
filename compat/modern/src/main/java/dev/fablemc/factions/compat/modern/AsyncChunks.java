package dev.fablemc.factions.compat.modern;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * Async chunk access for home-safety checks (CONTRACTS §3, version-deltas §3.9). Wraps
 * Paper's {@code World#getChunkAtAsync}, which loads a chunk off the main thread and is
 * region-correct on Folia — needed only for the rare true chunk access (a {@code /f home}
 * safety probe into an unloaded chunk), never for a membership test (those are pure math).
 *
 * <p>Compiled against paper-api 1.20.6 and loaded by FQN string only when the
 * {@code asyncChunkGet} capability probes true.
 *
 * <p>Owning thread(s): callable from any thread; the returned future completes on the
 * chunk's owning region thread. Mutability class: stateless.
 */
public final class AsyncChunks {

    public AsyncChunks() {}

    /** Loads (or fetches) chunk {@code (chunkX, chunkZ)} in {@code world} asynchronously. */
    public @NotNull CompletableFuture<Chunk> getChunk(@NotNull World world, int chunkX, int chunkZ) {
        return world.getChunkAtAsync(chunkX, chunkZ);
    }

    /** Loads chunk {@code (chunkX, chunkZ)} without generating it when absent. */
    public @NotNull CompletableFuture<Chunk> getChunkIfGenerated(@NotNull World world, int chunkX, int chunkZ) {
        return world.getChunkAtAsync(chunkX, chunkZ, false);
    }
}
