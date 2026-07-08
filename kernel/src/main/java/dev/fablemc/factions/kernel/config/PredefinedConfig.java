package dev.fablemc.factions.kernel.config;

/**
 * Typed {@code pre-defined.yml} registry (pvp-resources.md §4).
 *
 * <p><b>Owning thread(s):</b> parsed in {@code :core}, read on any thread. <b>Mutability:</b>
 * immutable value. <b>Reducer rule:</b> swapped whole via {@code SwapConfig}; predefined seeding
 * is folded into {@code CreateFaction} (proposal-C §4.2).
 *
 * <p>When {@code enabled} is false, all {@code /fa predefined} ops and predefined lookups reject.
 * {@code blockDisband} protects predefined factions from disband. Presets are held as normalized
 * names plus their saved claims and home; {@code caseSensitive} governs name matching.
 */
public record PredefinedConfig(
        boolean enabled,
        boolean caseSensitive,
        boolean blockDisband,
        Preset[] presets) {

    /**
     * A single predefined faction template: a name, its saved chunk claims (packed keys, one
     * parallel worldIdx per key), and an optional home.
     */
    public record Preset(String name, int[] claimWorldIdx, long[] claimChunkKeys, Home home) {
    }

    /** A predefined home location. */
    public record Home(int worldIdx, double x, double y, double z, float yaw, float pitch) {
    }

    private static final Preset[] NO_PRESETS = new Preset[0];

    /** The reference-default predefined configuration (disabled, no presets). */
    public static PredefinedConfig defaults() {
        return new PredefinedConfig(false, false, true, NO_PRESETS);
    }

    /** Case-insensitive (or sensitive, per {@link #caseSensitive}) lookup of a preset by name. */
    public Preset find(String name) {
        if (name == null) {
            return null;
        }
        for (Preset p : presets) {
            if (caseSensitive ? p.name().equals(name) : p.name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    /** {@code true} when {@code name} matches a predefined preset. */
    public boolean isPredefinedName(String name) {
        return find(name) != null;
    }
}
