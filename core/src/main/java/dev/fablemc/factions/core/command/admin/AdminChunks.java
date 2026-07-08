package dev.fablemc.factions.core.command.admin;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import dev.fablemc.factions.kernel.ids.ChunkKeys;

/**
 * The admin claim/unclaim/zone chunk-shape collector (ref-commands-admin.md §2.5). Resolves a mode
 * word plus a center chunk and radius into a deduplicated, capped array of packed chunk keys the
 * command submits in a bulk intent — the {@code :core}-side analogue of the reference
 * {@code collectSquare}/{@code collectCircle} helpers, computed with the kernel {@link ChunkKeys}
 * packing so keys match the atlas exactly.
 *
 * <p><b>Owning thread(s):</b> pure static, called from an admin command {@code perform} on the
 * sender's region/main thread. <b>Mutability:</b> stateless.
 */
public final class AdminChunks {

    private AdminChunks() {
    }

    /** The fixed radius the reference {@code fill} mode uses (a 3×3 block). */
    private static final int FILL_RADIUS = 1;

    /** Parses a radius: {@code max(1, n)}, defaulting to {@code 1} on a blank/malformed value. */
    public static int parseRadius(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException malformed) {
            return 1;
        }
    }

    /**
     * Collects the chunk keys for {@code mode} around {@code (centerX, centerZ)}: {@code one}/blank →
     * the center only; {@code square} → a {@code radius}-square; {@code circle} → a {@code radius}-disc;
     * {@code fill} → a fixed 3×3 square. The result is deduplicated and capped at {@code max} keys
     * (ref-commands-admin.md §2.5).
     */
    public static long[] collect(String mode, int centerX, int centerZ, int radius, int max) {
        String normalized = mode == null ? "one" : mode.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            normalized = "one";
        }
        switch (normalized) {
            case "square":
                return square(centerX, centerZ, radius, max, false);
            case "circle":
                return square(centerX, centerZ, radius, max, true);
            case "fill":
                return square(centerX, centerZ, FILL_RADIUS, max, false);
            default: // "one" and any unknown mode collapse to the center chunk
                return new long[] {ChunkKeys.key(centerX, centerZ)};
        }
    }

    private static long[] square(int centerX, int centerZ, int radius, int max, boolean disc) {
        int cap = Math.max(1, max);
        int radiusSquared = radius * radius;
        Set<Long> keys = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (disc && dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                keys.add(ChunkKeys.key(centerX + dx, centerZ + dz));
                if (keys.size() >= cap) {
                    return toArray(keys);
                }
            }
        }
        return toArray(keys);
    }

    private static long[] toArray(Set<Long> keys) {
        long[] out = new long[keys.size()];
        int i = 0;
        for (Long key : keys) {
            out[i++] = key;
        }
        return out;
    }
}
