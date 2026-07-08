package dev.fablemc.factions.kernel.config;

/**
 * Typed {@code factions.chat.*} configuration.
 *
 * <p><b>Owning thread(s):</b> parsed in {@code :core}, read on any thread. <b>Mutability:</b>
 * immutable value. <b>Reducer rule:</b> swapped whole via {@code SwapConfig}. When the tag
 * format changes, the reducer re-renders every faction's {@code tagLegacy}/{@code tagMini}
 * (proposal-C §5d) so the chat hot path never re-parses MiniMessage.
 *
 * <p>{@code tagFormat} is MiniMessage with placeholders {@code {faction_name}} and
 * {@code {rank_prefix}}; names are inserted as escaped literal text (never re-parsed), killing
 * the tag-injection bug.
 */
public record ChatConfig(boolean showTag, String tagFormat) {

    /** The reference-default chat configuration. */
    public static ChatConfig defaults() {
        return new ChatConfig(false, "<gray>[<gold>{faction_name}</gold>]</gray> ");
    }
}
