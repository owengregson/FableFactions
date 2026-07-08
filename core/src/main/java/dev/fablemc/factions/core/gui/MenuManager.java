package dev.fablemc.factions.core.gui;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import dev.fablemc.factions.core.messages.MessageCatalog;
import dev.fablemc.factions.core.pipeline.IntentBus;
import dev.fablemc.factions.core.pipeline.SnapshotHub;
import dev.fablemc.factions.core.session.PlayerSession;
import dev.fablemc.factions.core.session.SessionRegistry;
import dev.fablemc.factions.core.text.Messages;
import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.kernel.config.GuiModelConfig;
import dev.fablemc.factions.kernel.ids.FactionHandle;
import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.intent.PrefIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;
import dev.fablemc.factions.kernel.state.PlayerLedger;
import dev.fablemc.factions.platform.gui.MenuHolder;
import dev.fablemc.factions.platform.gui.MenuItemModel;
import dev.fablemc.factions.platform.gui.MenuModel;
import dev.fablemc.factions.platform.resolve.LegacyMaterials;

/**
 * Renders the config-driven {@code gui.yml} menus behind {@code /f gui}, {@code /f language} and the
 * bare {@code /f} default-menu path (ref-commands-misc.md §7, proposal-C §7.5). Each menu is built
 * from an immutable {@link MenuModel} into a {@link MenuHolder}-owned inventory via the universal
 * {@code Bukkit.createInventory(holder, size, String)} overload — <b>zero {@code InventoryView}
 * calls</b>. Titles, item names and lore are per-viewer placeholder-substituted from the snapshot
 * and converted to legacy {@code §} strings in-house (this package may not touch {@code net.kyori},
 * AM-1). Click actions (RUN_COMMAND / SUGGEST_COMMAND / OPEN_MENU / LANGUAGE_SET / LANGUAGE_RESET /
 * CLOSE / REFRESH) are dispatched from {@link GuiListener} via {@link #handleClick}.
 *
 * <p><b>Owning thread(s):</b> every method runs on the target player's region/main thread (menus
 * are opened from a command {@code perform} and clicked from a region-thread event); the confined
 * {@link MenuSession} lives in the player's {@link PlayerSession}. <b>Mutability:</b> the parsed menu
 * catalog is a volatile map swapped whole on reload; everything else is immutable collaborators.
 */
public final class MenuManager implements Menus {

    private static final String DEFAULT_COMMAND = "f help";

    private final SnapshotHub snapshots;
    private final SessionRegistry sessions;
    private final Messages messages;
    private final IntentBus bus;
    private final MessageCatalog catalog;

    private volatile Map<String, MenuModel> menus;

    /**
     * Constructor injection (CONTRACTS §4): the snapshot source, session registry, text layer, intent
     * bus, message catalog (for locale-code resolution) and the parsed {@code gui.yml} menu catalog.
     */
    public MenuManager(SnapshotHub snapshots, SessionRegistry sessions, Messages messages, IntentBus bus,
                       MessageCatalog catalog, Map<String, MenuModel> menus) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.menus = Map.copyOf(menus);
    }

    /** Swaps the parsed menu catalog (config reload). */
    public void setMenus(Map<String, MenuModel> menus) {
        this.menus = Map.copyOf(menus);
    }

    @Override
    public boolean openDefault(Player player) {
        GuiModelConfig gui = snapshots.current().config().gui();
        return gui.enabled() && open(player, gui.defaultMenu());
    }

    @Override
    public boolean open(Player player, String menuId) {
        KernelSnapshot snapshot = snapshots.current();
        if (!snapshot.config().gui().enabled()) {
            return false;
        }
        MenuModel model = menuId == null ? null : menus.get(menuId);
        if (model == null) {
            return false;
        }
        render(player, snapshot, model);
        return true;
    }

    @Override
    public boolean openLanguage(Player player) {
        GuiModelConfig gui = snapshots.current().config().gui();
        return gui.enabled() && open(player, gui.languageMenu());
    }

    /**
     * Dispatches a click on {@code holder}'s menu at {@code rawSlot} (called by {@link GuiListener};
     * the click is already cancelled). A slot with no configured item, or an inert / unknown action,
     * is a no-op.
     */
    public void handleClick(Player player, MenuHolder holder, int rawSlot) {
        MenuModel model = menus.get(holder.menuId());
        if (model == null) {
            return;
        }
        MenuItemModel item = itemAt(model, rawSlot);
        if (item == null || item.action() == null) {
            return;
        }
        dispatch(player, holder.menuId(), item.action(), item.actionData());
    }

    /** Clears the tracked open-menu state for {@code player} (called on menu close). */
    public void handleClose(UUID playerId) {
        PlayerSession session = sessions.get(playerId);
        if (session != null && session.guiSession() instanceof MenuSession) {
            session.setGuiSession(null);
        }
    }

    // ── rendering ────────────────────────────────────────────────────────────────────────────

    private void render(Player player, KernelSnapshot snapshot, MenuModel model) {
        Placeholders data = Placeholders.of(player, snapshot);
        ConfigImage config = snapshot.config();
        UUID id = player.getUniqueId();
        MenuHolder holder = new MenuHolder(model.id(), id);
        String title = toLegacy(substitute(model.title(), data));
        Inventory inventory = Bukkit.createInventory(holder, model.size(), title);
        holder.setInventory(inventory);

        List<MenuItemModel> items = model.items();
        for (int i = 0; i < items.size(); i++) {
            MenuItemModel item = items.get(i);
            if (item.slot() < 0 || item.slot() >= model.size() || hidden(item, config)) {
                continue;
            }
            inventory.setItem(item.slot(), buildIcon(item, data));
        }

        player.openInventory(inventory);
        PlayerSession session = sessions.get(id);
        if (session != null) {
            if (session.guiSession() instanceof MenuSession open) {
                open.update(model.id(), holder);
            } else {
                session.setGuiSession(new MenuSession(id, model.id(), holder));
            }
        }
    }

    private ItemStack buildIcon(MenuItemModel item, Placeholders data) {
        ItemStack icon = LegacyMaterials.icon(item.material(), 1);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            if (!item.name().isEmpty()) {
                meta.setDisplayName(toLegacy(substitute(item.name(), data)));
            }
            List<String> lore = item.lore();
            if (!lore.isEmpty()) {
                java.util.List<String> rendered = new java.util.ArrayList<>(lore.size());
                for (int i = 0; i < lore.size(); i++) {
                    rendered.add(toLegacy(substitute(lore.get(i), data)));
                }
                meta.setLore(rendered);
            }
            if (item.glow()) {
                applyGlow(meta);
            }
            icon.setItemMeta(meta);
        }
        return icon;
    }

    /** Adds a glow (enchant) resolved by NAME — no post-floor getstatic (AM-13); hides it best-effort. */
    private static void applyGlow(ItemMeta meta) {
        try {
            Enchantment glow = Enchantment.getByName("DURABILITY");
            if (glow == null) {
                glow = Enchantment.getByName("UNBREAKING");
            }
            if (glow != null) {
                meta.addEnchant(glow, 1, true);
            }
        } catch (Throwable ignored) {
            // no enchantment on this server — skip the glow
        }
        hideEnchants(meta);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void hideEnchants(ItemMeta meta) {
        try {
            Class<?> flagClass = Class.forName("org.bukkit.inventory.ItemFlag");
            Object hideEnchants = Enum.valueOf((Class) flagClass, "HIDE_ENCHANTS");
            Object array = java.lang.reflect.Array.newInstance(flagClass, 1);
            java.lang.reflect.Array.set(array, 0, hideEnchants);
            meta.getClass().getMethod("addItemFlags", array.getClass()).invoke(meta, array);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // pre-1.8 (no ItemFlag) — the glow shows its enchant line, harmless
        }
    }

    // ── click dispatch ───────────────────────────────────────────────────────────────────────

    private void dispatch(Player player, String menuId, String action, String actionData) {
        switch (action.toUpperCase(Locale.ROOT)) {
            case "RUN_COMMAND" -> {
                player.closeInventory();
                player.performCommand(stripSlash(actionData == null ? DEFAULT_COMMAND : actionData));
            }
            case "SUGGEST_COMMAND" -> {
                player.closeInventory();
                messages.to(player, Text.SUGGESTED, actionData == null ? "" : actionData);
            }
            case "OPEN_MENU" -> {
                String target = actionData == null ? snapshots.current().config().gui().defaultMenu() : actionData;
                if (!open(player, target)) {
                    messages.to(player, Text.MENU_NOT_CONFIGURED, target);
                }
            }
            case "LANGUAGE_SET" -> setLanguage(player, actionData, menuId);
            case "LANGUAGE_RESET" -> resetLanguage(player, menuId);
            case "CLOSE" -> player.closeInventory();
            case "REFRESH" -> open(player, menuId);
            default -> {
                // NONE / unknown → inert icon, no action
            }
        }
    }

    private void setLanguage(Player player, String code, String menuId) {
        ConfigImage.Language language = snapshots.current().config().language();
        if (!language.allowPlayerOverride()) {
            messages.to(player, Text.OVERRIDE_DISABLED);
            return;
        }
        if (code == null || code.isEmpty() || !isVisible(language, code)) {
            messages.to(player, Text.INVALID_CODE, code == null ? "" : code);
            return;
        }
        bus.submit(new PrefIntent.SetLocale(player.getUniqueId(), catalog.localeIndex(code)),
                Origin.player(player.getUniqueId()));
        messages.to(player, Text.LANGUAGE_SET, code);
        open(player, menuId);
    }

    private void resetLanguage(Player player, String menuId) {
        ConfigImage.Language language = snapshots.current().config().language();
        if (!language.allowPlayerOverride()) {
            messages.to(player, Text.OVERRIDE_DISABLED);
            return;
        }
        bus.submit(new PrefIntent.SetLocale(player.getUniqueId(), catalog.defaultLocale()),
                Origin.player(player.getUniqueId()));
        messages.to(player, Text.LANGUAGE_RESET);
        open(player, menuId);
    }

    // ── visibility / helpers ────────────────────────────────────────────────────────────────────

    private static boolean hidden(MenuItemModel item, ConfigImage config) {
        String action = item.action();
        if (action == null) {
            return false;
        }
        String upper = action.toUpperCase(Locale.ROOT);
        boolean languageAction = upper.equals("LANGUAGE_SET") || upper.equals("LANGUAGE_RESET");
        if (!languageAction) {
            return false;
        }
        if (!config.language().allowPlayerOverride()) {
            return true;
        }
        if (upper.equals("LANGUAGE_SET")) {
            String code = item.actionData();
            return code == null || code.isEmpty() || !isVisible(config.language(), code);
        }
        return false;
    }

    private static boolean isVisible(ConfigImage.Language language, String code) {
        String[] visible = language.visibleLocales();
        if (visible == null || visible.length == 0) {
            return true; // no allow-list → every supported locale is visible
        }
        for (String tag : visible) {
            if (tag.equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    private static MenuItemModel itemAt(MenuModel model, int slot) {
        List<MenuItemModel> items = model.items();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).slot() == slot) {
                return items.get(i);
            }
        }
        return null;
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private static String substitute(String template, Placeholders data) {
        if (template.indexOf('{') < 0) {
            return template;
        }
        String out = template;
        out = out.replace("{player}", data.player);
        out = out.replace("{faction}", data.faction);
        out = out.replace("{faction_members}", data.factionMembers);
        out = out.replace("{faction_land}", data.factionLand);
        out = out.replace("{faction_bank}", data.factionBank);
        out = out.replace("{power}", data.power);
        out = out.replace("{max_power}", data.maxPower);
        out = out.replace("{language_current}", data.languageCurrent);
        out = out.replace("{language_default}", data.languageDefault);
        out = out.replace("{language_available}", data.languageAvailable);
        return out;
    }

    /**
     * Converts the MiniMessage subset used by {@code gui.yml} to a legacy {@code §} string in-house
     * (core.gui may not import {@code net.kyori}, AM-1). Named colours and decorations map to their
     * {@code §} code, {@code <#rrggbb>} to an {@code §x} hex sequence; gradients, closing and unknown
     * tags are stripped. This is deliberately lightweight — good enough for short GUI labels.
     */
    static String toLegacy(String mini) {
        StringBuilder out = new StringBuilder(mini.length());
        int i = 0;
        int n = mini.length();
        while (i < n) {
            char c = mini.charAt(i);
            if (c == '\\' && i + 1 < n && mini.charAt(i + 1) == '<') {
                out.append('<');
                i += 2;
                continue;
            }
            if (c != '<') {
                out.append(c);
                i++;
                continue;
            }
            int close = mini.indexOf('>', i + 1);
            if (close < 0) {
                out.append(c);
                i++;
                continue;
            }
            appendTag(out, mini.substring(i + 1, close));
            i = close + 1;
        }
        return out.toString();
    }

    private static void appendTag(StringBuilder out, String tag) {
        if (tag.startsWith("/")) {
            return; // closing tag → drop (legacy has no scoped close)
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        String code = colorCode(lower);
        if (code != null) {
            out.append(code);
            return;
        }
        if (lower.startsWith("#") && lower.length() == 7) {
            appendHex(out, lower.substring(1));
        }
        // gradient / hover / unknown tags are stripped
    }

    private static void appendHex(StringBuilder out, String hex) {
        for (int i = 0; i < hex.length(); i++) {
            char h = hex.charAt(i);
            boolean valid = (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f');
            if (!valid) {
                return; // not a real hex tag — drop
            }
        }
        out.append('§').append('x');
        for (int i = 0; i < 6; i++) {
            out.append('§').append(hex.charAt(i));
        }
    }

    private static String colorCode(String tag) {
        return switch (tag) {
            case "black" -> "§0";
            case "dark_blue" -> "§1";
            case "dark_green" -> "§2";
            case "dark_aqua" -> "§3";
            case "dark_red" -> "§4";
            case "dark_purple" -> "§5";
            case "gold" -> "§6";
            case "gray", "grey" -> "§7";
            case "dark_gray", "dark_grey" -> "§8";
            case "blue" -> "§9";
            case "green" -> "§a";
            case "aqua" -> "§b";
            case "red" -> "§c";
            case "light_purple" -> "§d";
            case "yellow" -> "§e";
            case "white" -> "§f";
            case "bold", "b" -> "§l";
            case "italic", "i", "em" -> "§o";
            case "underlined", "u" -> "§n";
            case "strikethrough", "st" -> "§m";
            case "obfuscated", "obf" -> "§k";
            case "reset" -> "§r";
            default -> null;
        };
    }

    /** The per-open, per-viewer placeholder values, computed once from the snapshot. */
    private record Placeholders(String player, String faction, String factionMembers, String factionLand,
                                String factionBank, String power, String maxPower, String languageCurrent,
                                String languageDefault, String languageAvailable) {

        static Placeholders of(Player player, KernelSnapshot snapshot) {
            ConfigImage config = snapshot.config();
            UUID id = player.getUniqueId();
            int ordinal = snapshot.memberOrdinal(id);
            Faction faction = null;
            if (ordinal >= 0) {
                MemberView member = snapshot.member(ordinal);
                if (member != null) {
                    Faction resolved = snapshot.faction(member.factionHandle());
                    if (resolved != null && resolved.isNormal()) {
                        faction = resolved;
                    }
                }
            }
            String factionName = faction != null ? faction.name() : "Wilderness";
            String land = faction != null ? Integer.toString(faction.landCount()) : "0";
            String bank = String.format(Locale.US, "%.2f", faction != null ? faction.bank() : 0.0);
            String members = Integer.toString(faction != null ? countMembers(snapshot, faction) : 0);
            double powerValue = ordinal >= 0 ? snapshot.powerAt(ordinal, snapshot.tick()) : 0.0;
            String power = String.format(Locale.US, "%.2f", powerValue);
            String maxPower = String.format(Locale.US, "%.2f", config.power().maxPower());
            String defaultLocale = config.language().defaultLocale();
            String available = availableLocales(config);
            return new Placeholders(player.getName(), factionName, members, land, bank, power, maxPower,
                    defaultLocale, defaultLocale, available);
        }

        private static int countMembers(KernelSnapshot snapshot, Faction faction) {
            PlayerLedger ledger = snapshot.state().ledger();
            int factionOrdinal = faction.idx();
            int count = 0;
            int highWater = ledger.highWater();
            for (int ord = 0; ord < highWater; ord++) {
                if (ledger.has(ord)
                        && FactionHandle.ordinal(ledger.factionHandle(ord)) == factionOrdinal) {
                    count++;
                }
            }
            return count;
        }

        private static String availableLocales(ConfigImage config) {
            String[] visible = config.language().visibleLocales();
            if (visible == null || visible.length == 0) {
                return config.language().defaultLocale();
            }
            return String.join(", ", visible);
        }
    }

    /** Interned GUI message keys (house style §8c). */
    private static final class Text {
        static final MessageKey SUGGESTED = MessageKey.of("custom.gui.suggested-command");
        static final MessageKey MENU_NOT_CONFIGURED = MessageKey.of("custom.gui.menu-not-configured");
        static final MessageKey OVERRIDE_DISABLED = MessageKey.of("language.override-disabled");
        static final MessageKey INVALID_CODE = MessageKey.of("language.invalid-code");
        static final MessageKey LANGUAGE_SET = MessageKey.of("language.set-success");
        static final MessageKey LANGUAGE_RESET = MessageKey.of("language.reset-success");

        private Text() {
        }
    }
}
