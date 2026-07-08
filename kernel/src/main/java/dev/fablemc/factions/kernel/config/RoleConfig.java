package dev.fablemc.factions.kernel.config;

/**
 * Typed {@code roles.*} configuration (roles.yml — ref-resources.md §5, ref-commands-admin.md §1).
 *
 * <p><b>Owning thread(s):</b> parsed in {@code :core}, read on any thread. <b>Mutability:</b>
 * immutable value. <b>Reducer rule:</b> swapped whole via {@code SwapConfig}.
 *
 * <p>Every role mutation requires {@code overridesEnabled}; create/rename/priority/delete also
 * require {@code customEnabled}; prefix ops also require {@code prefixesEnabled}. Custom priority
 * validity is {@code [minCustomPriority, maxCustomPriority]}, falling back to {@code [11,99]}
 * when misconfigured ({@code min > max}). A value of 0 for {@code maxCustomRolesPerFaction} or
 * {@code maxPrefixLength} means unlimited. Default prefixes seed each faction's built-in ranks.
 */
public record RoleConfig(
        boolean customEnabled,
        boolean overridesEnabled,
        int minCustomPriority,
        int maxCustomPriority,
        int maxCustomRolesPerFaction,
        boolean prefixesEnabled,
        int maxPrefixLength,
        String defaultOwnerPrefix,
        String defaultOfficerPrefix,
        String defaultMemberPrefix) {

    /**
     * The reference code-default role configuration. NOTE: the shipped {@code roles.yml}
     * overrides some of these (e.g. {@code max-per-faction: 8}, non-empty default prefixes);
     * these are the getter defaults used when a key is absent (ref-commands-admin.md §1).
     */
    public static RoleConfig defaults() {
        return new RoleConfig(
                true,   // customEnabled
                false,  // overridesEnabled
                11,     // minCustomPriority
                99,     // maxCustomPriority
                0,      // maxCustomRolesPerFaction (0 = unlimited)
                true,   // prefixesEnabled
                32,     // maxPrefixLength
                "",     // defaultOwnerPrefix
                "",     // defaultOfficerPrefix
                "");    // defaultMemberPrefix
    }
}
