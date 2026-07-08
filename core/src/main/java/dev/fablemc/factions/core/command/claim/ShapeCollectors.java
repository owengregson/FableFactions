package dev.fablemc.factions.core.command.claim;

import dev.fablemc.factions.kernel.ids.ChunkKeys;
import dev.fablemc.factions.kernel.vocab.ClaimMode;

/**
 * Pure chunk-key geometry for the shaped claim / unclaim commands (ref-commands-core.md §7.21/§7.22)
 * — a square or Euclidean-circle collector around a centre chunk, capped at {@code max} keys
 * ({@code land.max-per-command}). Iteration is row-major ({@code dx} then {@code dz}, both ascending
 * from {@code -radius}), matching the reference collector order, and stops the moment the cap is hit.
 *
 * <p><b>Owning thread(s):</b> pure static, called from a claim command {@code perform} on the
 * player's region/main thread. <b>Mutability:</b> stateless; every method allocates and returns a
 * fresh {@code long[]} (no shared buffer).
 */
public final class ShapeCollectors {

    /** The smallest shaped radius (a radius below 1 clamps up to 1). */
    public static final int MIN_RADIUS = 1;
    /** The largest shaped radius the command layer accepts (a work-bound guard). */
    public static final int MAX_RADIUS = 100;

    private ShapeCollectors() {
    }

    /** Clamps {@code radius} into {@code [MIN_RADIUS, MAX_RADIUS]}. */
    public static int clampRadius(int radius) {
        if (radius < MIN_RADIUS) {
            return MIN_RADIUS;
        }
        return Math.min(MAX_RADIUS, radius);
    }

    /**
     * The chunk keys of the {@code (2·radius+1)²} square centred on {@code (centerX, centerZ)},
     * capped at {@code max} keys.
     */
    public static long[] square(int centerX, int centerZ, int radius, int max) {
        int r = clampRadius(radius);
        int span = 2 * r + 1;
        long[] out = new long[Math.min(Math.max(0, max), span * span)];
        int n = 0;
        for (int dx = -r; dx <= r && n < out.length; dx++) {
            for (int dz = -r; dz <= r && n < out.length; dz++) {
                out[n++] = ChunkKeys.key(centerX + dx, centerZ + dz);
            }
        }
        return trim(out, n);
    }

    /**
     * The chunk keys within Euclidean radius {@code radius} ({@code dx²+dz² ≤ r²}) of
     * {@code (centerX, centerZ)}, capped at {@code max} keys.
     */
    public static long[] circle(int centerX, int centerZ, int radius, int max) {
        int r = clampRadius(radius);
        int rSquared = r * r;
        int span = 2 * r + 1;
        long[] out = new long[Math.min(Math.max(0, max), span * span)];
        int n = 0;
        for (int dx = -r; dx <= r && n < out.length; dx++) {
            for (int dz = -r; dz <= r && n < out.length; dz++) {
                if (dx * dx + dz * dz <= rSquared) {
                    out[n++] = ChunkKeys.key(centerX + dx, centerZ + dz);
                }
            }
        }
        return trim(out, n);
    }

    /**
     * The keys for {@code mode} around {@code (centerX, centerZ)}: {@link ClaimMode#CIRCLE} is the
     * Euclidean disc, {@link ClaimMode#FILL} is the radius-1 square, and {@code SQUARE}/{@code NEARBY}
     * are the square of {@code radius}. Any other mode yields the single centre chunk.
     */
    public static long[] collect(ClaimMode mode, int centerX, int centerZ, int radius, int max) {
        return switch (mode) {
            case CIRCLE -> circle(centerX, centerZ, radius, max);
            case FILL -> square(centerX, centerZ, MIN_RADIUS, max);
            case SQUARE, NEARBY -> square(centerX, centerZ, radius, max);
            case SINGLE, AUTO, AT -> single(centerX, centerZ, max);
        };
    }

    private static long[] single(int centerX, int centerZ, int max) {
        return max <= 0 ? new long[0] : new long[] {ChunkKeys.key(centerX, centerZ)};
    }

    private static long[] trim(long[] out, int n) {
        if (n == out.length) {
            return out;
        }
        long[] exact = new long[n];
        System.arraycopy(out, 0, exact, 0, n);
        return exact;
    }
}
