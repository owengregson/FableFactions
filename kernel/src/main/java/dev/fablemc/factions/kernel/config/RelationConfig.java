package dev.fablemc.factions.kernel.config;

/**
 * Typed relation-limit configuration ({@code factions.max-allies}, {@code factions.max-truces}).
 *
 * <p><b>Owning thread(s):</b> parsed in {@code :core}, read on any thread. <b>Mutability:</b>
 * immutable value. <b>Reducer rule:</b> swapped whole via {@code SwapConfig}.
 *
 * <p>Limits apply only to ALLY/TRUCE and only when moving to a new value (ref-services.md §7.1
 * {@code withinRelationLimit}); ENEMY and NEUTRAL are unlimited and mirror instantly.
 */
public record RelationConfig(int maxAllies, int maxTruces) {

    /** The reference-default relation limits. */
    public static RelationConfig defaults() {
        return new RelationConfig(5, 5);
    }
}
