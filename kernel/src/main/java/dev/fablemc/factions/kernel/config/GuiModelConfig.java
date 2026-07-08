package dev.fablemc.factions.kernel.config;

/**
 * Typed top-level {@code gui.yml} configuration (pvp-resources.md §8).
 *
 * <p><b>Owning thread(s):</b> parsed in {@code :core}, read on any thread. <b>Mutability:</b>
 * immutable value. <b>Reducer rule:</b> swapped whole via {@code SwapConfig}.
 *
 * <p>The kernel carries only the GUI toggles and default-menu ids; the full parsed menu model
 * (items, slots, actions) is built platform-side ({@code MenuModel}) from the same file, since
 * it references Bukkit materials the kernel cannot see.
 */
public record GuiModelConfig(boolean enabled, String defaultMenu, String languageMenu) {

    /** The reference-default GUI configuration. */
    public static GuiModelConfig defaults() {
        return new GuiModelConfig(true, "main", "language");
    }
}
