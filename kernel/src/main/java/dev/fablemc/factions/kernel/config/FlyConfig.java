package dev.fablemc.factions.kernel.config;

/**
 * Typed {@code factions.fly.*} configuration.
 *
 * <p><b>Owning thread(s):</b> parsed in {@code :core}, read on any thread. <b>Mutability:</b>
 * immutable value. <b>Reducer rule:</b> swapped whole via {@code SwapConfig}.
 *
 * <p>{@code disableOnThreat} is a reference-dead knob wired here (D-11) with its
 * behavior-preserving default; {@code requireOwnTerritory} gates {@code /f fly} to own claims.
 */
public record FlyConfig(boolean enabled, boolean disableOnThreat, boolean requireOwnTerritory) {

    /** The reference-default fly configuration. */
    public static FlyConfig defaults() {
        return new FlyConfig(true, true, true);
    }
}
