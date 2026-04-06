package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CharacterSlotManager {

    public static final int MAX_SLOTS = 4;

    private static final int DEFAULT_INVENTORY_SIZE = 27;
    private static final String DEFAULT_GUI_TITLE = "&3原神角色编队";
    private static final int[] DEFAULT_CHARACTER_GUI_SLOTS = {10, 12, 14, 16};
    private static final int[] DEFAULT_INDICATOR_GUI_SLOTS = {19, 21, 23, 25};
    private static final int[] DEFAULT_PRIMARY_ACCENT_SLOTS = {1, 2, 3, 5, 6, 7};
    private static final int[] DEFAULT_SECONDARY_ACCENT_SLOTS = {9, 11, 13, 15, 17, 18, 20, 24, 26};
    private static final int DEFAULT_HELP_SLOT = 0;
    private static final int DEFAULT_STATUS_SLOT = 4;
    private static final int DEFAULT_MODE_SLOT = 8;
    private static final int DEFAULT_SWITCH_GUIDE_SLOT = 22;

    private final Yuanshen plugin;
    private final Map<UUID, ItemStack[]> characterSlots = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> selectedSlots = new ConcurrentHashMap<>();

    public CharacterSlotManager(Yuanshen plugin) {
        this.plugin = plugin;
    }

    public void loadPlayer(Player player, ItemStack[] slots, int selectedSlot) {
        if (player == null) {
            return;
        }

        characterSlots.put(player.getUniqueId(), normalizeItems(slots));
        selectedSlots.put(player.getUniqueId(), normalizeSlot(selectedSlot));
        normalizeSelectedSlot(player);
    }

    public void clearPlayer(Player player) {
        if (player == null) {
            return;
        }
        characterSlots.remove(player.getUniqueId());
        selectedSlots.remove(player.getUniqueId());
    }

    public int getSelectedSlot(Player player) {
        if (player == null) {
            return 1;
        }
        return normalizeSlot(selectedSlots.getOrDefault(player.getUniqueId(), 1));
    }

    public void setSelectedSlot(Player player, int slot) {
        if (player == null) {
            return;
        }
        CharacterType previous = getSelectedCharacter(player);
        selectedSlots.put(player.getUniqueId(), normalizeSlot(slot));
        normalizeSelectedSlot(player);
        CharacterType current = getSelectedCharacter(player);
        plugin.handleSelectedCharacterChange(player, previous, current);
    }

    public ItemStack getSlotItem(Player player, int slot) {
        if (player == null) {
            return null;
        }
        ItemStack[] slots = characterSlots.computeIfAbsent(player.getUniqueId(), ignored -> emptySlots());
        return cloneItem(slots[normalizeSlot(slot) - 1]);
    }

    public void setSlotItem(Player player, int slot, ItemStack item) {
        if (player == null) {
            return;
        }
        ItemStack[] slots = characterSlots.computeIfAbsent(player.getUniqueId(), ignored -> emptySlots());
        slots[normalizeSlot(slot) - 1] = cloneItem(item);
    }

    public ItemStack[] getSlotItems(Player player) {
        if (player == null) {
            return emptySlots();
        }
        return cloneItems(characterSlots.computeIfAbsent(player.getUniqueId(), ignored -> emptySlots()));
    }

    public CharacterType getSelectedCharacter(Player player) {
        return plugin.getCharacterResolver().resolveCharacter(getSlotItem(player, getSelectedSlot(player)));
    }

    public boolean hasSelectedCharacter(Player player) {
        return getSelectedCharacter(player) != null;
    }

    public String getSelectedCharacterName(Player player) {
        CharacterType type = getSelectedCharacter(player);
        return type == null ? "无角色" : type.getDisplayName();
    }

    public int getSelectedCharacterLevel(Player player) {
        return plugin.getCharacterManager() == null ? 0 : plugin.getCharacterManager().getSelectedCharacterLevel(player);
    }

    public int getSelectedCharacterConstellation(Player player) {
        return plugin.getCharacterManager() == null ? 0 : plugin.getCharacterManager().getSelectedCharacterConstellation(player);
    }

    public Inventory createInventory(Player player) {
        return createInventory(player, player);
    }

    public Inventory createInventory(Player owner, Player viewer) {
        GuiLayout layout = resolveLayout();
        CharacterGuiHolder holder = new CharacterGuiHolder(
                owner.getUniqueId(),
                viewer == null ? owner.getUniqueId() : viewer.getUniqueId()
        );
        Inventory inventory = Bukkit.createInventory(holder, layout.size(), color(layout.title()));
        holder.setInventory(inventory);
        populateInventory(owner, inventory);
        return inventory;
    }

    public void openInventory(Player player) {
        openInventory(player, player);
    }

    public void openInventory(Player viewer, Player owner) {
        if (viewer == null || owner == null) {
            return;
        }
        viewer.openInventory(createInventory(owner, viewer));
    }

    public void openInventoryForOwner(Player owner) {
        if (owner == null) {
            return;
        }
        owner.openInventory(createInventory(owner, owner));
    }

    public void populateInventory(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }

        GuiLayout layout = resolveLayout();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, createConfiguredItem(
                    "frame",
                    Material.GRAY_STAINED_GLASS_PANE,
                    " ",
                    List.of(),
                    Map.of()
            ));
        }

        for (int slot : layout.primaryAccentSlots()) {
            inventory.setItem(slot, createConfiguredItem(
                    "accent-primary",
                    Material.CYAN_STAINED_GLASS_PANE,
                    " ",
                    List.of(),
                    Map.of()
            ));
        }
        for (int slot : layout.secondaryAccentSlots()) {
            inventory.setItem(slot, createConfiguredItem(
                    "accent-secondary",
                    Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                    " ",
                    List.of(),
                    Map.of()
            ));
        }

        inventory.setItem(layout.helpSlot(), createHelpCard());
        inventory.setItem(layout.statusSlot(), createStatusSummary(player));
        inventory.setItem(layout.modeSlot(), createModeCard(player));
        inventory.setItem(layout.switchGuideSlot(), createSwitchGuide());

        for (int i = 1; i <= MAX_SLOTS; i++) {
            inventory.setItem(toGuiSlot(i), getSlotItem(player, i));
            inventory.setItem(indicatorGuiSlot(i), createIndicator(i, i == getSelectedSlot(player), player));
        }
    }

    public void syncFromInventory(Player player, Inventory inventory) {
        if (player == null || inventory == null) {
            return;
        }

        CharacterType previous = getSelectedCharacter(player);
        for (int i = 1; i <= MAX_SLOTS; i++) {
            setSlotItem(player, i, inventory.getItem(toGuiSlot(i)));
        }
        normalizeSelectedSlot(player);
        CharacterType current = getSelectedCharacter(player);
        populateInventory(player, inventory);
        if (previous != current) {
            plugin.handleSelectedCharacterChange(player, previous, current);
        } else {
            plugin.refreshPlayerStats(player);
        }
    }

    public boolean isCharacterGui(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof CharacterGuiHolder;
    }

    public boolean isCharacterGuiSlot(int rawSlot) {
        for (int guiSlot : resolveLayout().characterSlots()) {
            if (guiSlot == rawSlot) {
                return true;
            }
        }
        return false;
    }

    public boolean isEditableGuiSlot(int rawSlot) {
        return isCharacterGuiSlot(rawSlot);
    }

    public boolean isIndicatorGuiSlot(int rawSlot) {
        for (int guiSlot : resolveLayout().indicatorSlots()) {
            if (guiSlot == rawSlot) {
                return true;
            }
        }
        return false;
    }

    public int toCharacterSlot(int rawSlot) {
        int[] slots = resolveLayout().characterSlots();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == rawSlot) {
                return i + 1;
            }
        }
        return -1;
    }

    public int toIndicatorSlot(int rawSlot) {
        int[] slots = resolveLayout().indicatorSlots();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == rawSlot) {
                return i + 1;
            }
        }
        return -1;
    }

    public int toGuiSlot(int slot) {
        return resolveLayout().characterSlots()[normalizeSlot(slot) - 1];
    }

    public int indicatorGuiSlot(int slot) {
        return resolveLayout().indicatorSlots()[normalizeSlot(slot) - 1];
    }

    public int findFirstEmptySlot(Player player) {
        for (int i = 1; i <= MAX_SLOTS; i++) {
            ItemStack item = getSlotItem(player, i);
            if (item == null || item.getType().isAir()) {
                return i;
            }
        }
        return -1;
    }

    public int findFirstValidCharacterSlot(Player player) {
        for (int i = 1; i <= MAX_SLOTS; i++) {
            if (plugin.getCharacterResolver().resolveCharacter(getSlotItem(player, i)) != null) {
                return i;
            }
        }
        return -1;
    }

    private void normalizeSelectedSlot(Player player) {
        int currentSlot = getSelectedSlot(player);
        if (plugin.getCharacterResolver().resolveCharacter(getSlotItem(player, currentSlot)) != null) {
            return;
        }

        int firstValidSlot = findFirstValidCharacterSlot(player);
        selectedSlots.put(player.getUniqueId(), firstValidSlot > 0 ? firstValidSlot : 1);
    }

    private ItemStack createHelpCard() {
        return createConfiguredItem(
                "help",
                Material.BOOK,
                "&b编队说明",
                List.of(
                        "&71. 本界面只放角色，不再存武器。",
                        "&72. 你手里拿着武器时，系统直接检测主手。",
                        "&73. 下方按钮切换当前出战角色。",
                        "&74. 切换后点击按钮可继续查看该角色命座。"
                ),
                Map.of()
        );
    }

    private ItemStack createStatusSummary(Player player) {
        CharacterInstance selectedCharacter = plugin.getCharacterManager() == null
                ? null
                : plugin.getCharacterManager().getSelectedCharacterInstance(player);
        WeaponInstance mainHandWeapon = resolveMainHandWeapon(player);

        String section;
        Material material;
        String name;
        if (selectedCharacter == null) {
            section = "status.no-character";
            material = Material.BARRIER;
            name = "&c当前未选择角色";
        } else if (plugin.isCharacterModeActive(player)) {
            section = "status.active";
            material = Material.EMERALD;
            name = "&a当前为角色战斗模式";
        } else {
            section = "status.inactive";
            material = Material.CLOCK;
            name = "&e当前为玩家模式";
        }

        return createConfiguredItem(
                section,
                material,
                name,
                List.of(
                        "&7当前槽位: &f{selected_slot}",
                        "&7当前角色: &f{character}",
                        "&7需求武器类型: &f{required_weapon_type}",
                        "{mode_line}"
                ),
                buildPlaceholders(player, selectedCharacter, mainHandWeapon, getSelectedSlot(player), true,
                        buildSelectedModeLine(selectedCharacter, mainHandWeapon))
        );
    }

    private ItemStack createModeCard(Player player) {
        CharacterInstance selectedCharacter = plugin.getCharacterManager() == null
                ? null
                : plugin.getCharacterManager().getSelectedCharacterInstance(player);
        WeaponInstance mainHandWeapon = resolveMainHandWeapon(player);

        String section;
        Material material;
        String name;
        if (mainHandWeapon == null) {
            section = "mode.no-weapon";
            material = Material.SPYGLASS;
            name = "&e主手未持已注册武器";
        } else if (selectedCharacter == null) {
            section = "mode.no-character";
            material = Material.COMPASS;
            name = "&e先选择一个角色";
        } else if (mainHandWeapon.definition().weaponType() == selectedCharacter.definition().weaponType()) {
            section = "mode.matched";
            material = Material.RESPAWN_ANCHOR;
            name = "&a主手检测通过";
        } else {
            section = "mode.mismatch";
            material = Material.GRINDSTONE;
            name = "&c主手武器类型不匹配";
        }

        return createConfiguredItem(
                section,
                material,
                name,
                List.of(
                        "&7检测方式: &f仅检测主手武器",
                        "&7主手状态: &f{main_hand_status}",
                        "&7角色要求: &f{required_weapon_type}",
                        "&8把正确类型的武器拿在手里即可战斗。"
                ),
                buildPlaceholders(player, selectedCharacter, mainHandWeapon, getSelectedSlot(player), true,
                        buildSelectedModeLine(selectedCharacter, mainHandWeapon))
        );
    }

    private ItemStack createSwitchGuide() {
        return createConfiguredItem(
                "switch-guide",
                Material.COMPARATOR,
                "&6出战切换",
                List.of(
                        "&7点击下方按钮切换出战角色。",
                        "&7如果槽位里有角色，会自动打开命座界面。",
                        "&7如果槽位为空，则只切换当前槽位。"
                ),
                Map.of()
        );
    }

    private ItemStack createIndicator(int slot, boolean selected, Player player) {
        CharacterInstance instance = plugin.getCharacterManager() == null
                ? null
                : plugin.getCharacterManager().resolveCharacterInstance(getSlotItem(player, slot));
        WeaponInstance mainHandWeapon = resolveMainHandWeapon(player);

        String section;
        Material material;
        if (selected) {
            section = "indicator.selected";
            material = Material.LIME_DYE;
        } else if (instance == null) {
            section = "indicator.empty";
            material = Material.GRAY_DYE;
        } else if (mainHandWeapon != null && mainHandWeapon.definition().weaponType() == instance.definition().weaponType()) {
            section = "indicator.ready";
            material = Material.YELLOW_DYE;
        } else {
            section = "indicator.normal";
            material = Material.WHITE_DYE;
        }

        return createConfiguredItem(
                section,
                material,
                (selected ? "&a槽位 " : "&e槽位 ") + slot,
                List.of(
                        selected ? "&a当前已选中此槽位。" : "&7点击切换到该槽位。",
                        "&7角色: &f{character}",
                        "{mode_line}",
                        "&8点击后可继续查看该槽位角色命座。"
                ),
                buildPlaceholders(player, instance, mainHandWeapon, slot, selected,
                        buildSlotModeLine(instance, mainHandWeapon, selected))
        );
    }

    private ItemStack createConfiguredItem(String itemPath,
                                           Material defaultMaterial,
                                           String defaultName,
                                           List<String> defaultLore,
                                           Map<String, String> placeholders) {
        FileConfiguration config = guiConfig();
        String basePath = "character-gui.items." + itemPath;
        String materialName = config.getString(basePath + ".material", defaultMaterial.name());
        Material material = Material.matchMaterial(materialName == null ? "" : materialName);
        if (material == null) {
            material = defaultMaterial;
        }

        int amount = Math.max(1, Math.min(64, config.getInt(basePath + ".amount", 1)));
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        String name = applyPlaceholders(config.getString(basePath + ".name", defaultName), placeholders);
        List<String> lore = config.contains(basePath + ".lore")
                ? config.getStringList(basePath + ".lore")
                : defaultLore;

        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(line -> color(applyPlaceholders(line, placeholders))).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        int cmd = resolveCustomModelData(config, basePath);
        if (cmd >= 0) {
            meta.setCustomModelData(cmd);
        }

        for (String flagName : config.getStringList(basePath + ".flags")) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(flagName.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid item flag names from config.
            }
        }

        item.setItemMeta(meta);
        return item;
    }

    private int resolveCustomModelData(FileConfiguration config, String basePath) {
        if (config.contains(basePath + ".cmd")) {
            return config.getInt(basePath + ".cmd", -1);
        }
        if (config.contains(basePath + ".custom-model-data")) {
            return config.getInt(basePath + ".custom-model-data", -1);
        }
        return -1;
    }

    private Map<String, String> buildPlaceholders(Player player,
                                                  CharacterInstance instance,
                                                  WeaponInstance mainHandWeapon,
                                                  int slot,
                                                  boolean selected,
                                                  String modeLine) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("slot", String.valueOf(slot));
        placeholders.put("selected_slot", String.valueOf(getSelectedSlot(player)));
        placeholders.put("character", formatCharacter(instance));
        placeholders.put("character_name", instance == null ? "无" : instance.definition().displayName());
        placeholders.put("character_level", instance == null ? "0" : String.valueOf(instance.level()));
        placeholders.put("character_constellation", instance == null ? "0" : String.valueOf(instance.constellation()));
        placeholders.put("required_weapon_type", instance == null ? "无" : instance.definition().weaponType().getDisplayName());
        placeholders.put("main_hand_status", buildMainHandStatus(mainHandWeapon));
        placeholders.put("main_hand_weapon", mainHandWeapon == null ? "无" : mainHandWeapon.definition().displayName());
        placeholders.put("selected", selected ? "true" : "false");
        placeholders.put("mode_line", modeLine == null ? "" : modeLine);
        return placeholders;
    }

    private WeaponInstance resolveMainHandWeapon(Player player) {
        return player == null || plugin.getWeaponManager() == null
                ? null
                : plugin.getWeaponManager().getEquippedWeaponInstance(player);
    }

    private String buildSelectedModeLine(CharacterInstance character, WeaponInstance mainHandWeapon) {
        if (character == null) {
            return "&c当前槽位没有角色，保持普通玩家模式。";
        }
        if (mainHandWeapon == null) {
            return "&e主手没有已注册武器，当前仍是玩家模式。";
        }
        if (mainHandWeapon.definition().weaponType() != character.definition().weaponType()) {
            return "&e主手武器类型不匹配，当前仍是玩家模式。";
        }
        return "&a主手武器类型匹配，当前技能与普攻按该角色生效。";
    }

    private String buildSlotModeLine(CharacterInstance character, WeaponInstance mainHandWeapon, boolean selected) {
        if (character == null) {
            return selected ? "&c该槽位为空。" : "&7该槽位没有角色。";
        }
        if (mainHandWeapon == null) {
            return selected ? "&e主手未持已注册武器。" : "&7切换后仍需把匹配武器拿在主手。";
        }
        if (mainHandWeapon.definition().weaponType() != character.definition().weaponType()) {
            return selected
                    ? "&e主手类型不匹配。"
                    : "&7切换后仍需换成 " + character.definition().weaponType().getDisplayName() + "。";
        }
        return selected ? "&a当前已满足战斗条件。" : "&a切换后可直接进入该角色战斗模式。";
    }

    private String buildMainHandStatus(WeaponInstance mainHandWeapon) {
        if (mainHandWeapon == null) {
            return "未持已注册武器";
        }
        return mainHandWeapon.definition().displayName() + " / " + mainHandWeapon.definition().weaponType().getDisplayName();
    }

    private String formatCharacter(CharacterInstance instance) {
        if (instance == null) {
            return "无";
        }
        return instance.definition().displayName() + " Lv." + instance.level() + " C" + instance.constellation();
    }

    private GuiLayout resolveLayout() {
        FileConfiguration config = guiConfig();
        int[] characterSlots = readSlotArray(config, "character-gui.layout.character-slots", DEFAULT_CHARACTER_GUI_SLOTS, MAX_SLOTS);
        int[] indicatorSlots = readSlotArray(config, "character-gui.layout.indicator-slots", DEFAULT_INDICATOR_GUI_SLOTS, MAX_SLOTS);
        int[] primaryAccents = readSlotArray(config, "character-gui.layout.primary-accent-slots", DEFAULT_PRIMARY_ACCENT_SLOTS, -1);
        int[] secondaryAccents = readSlotArray(config, "character-gui.layout.secondary-accent-slots", DEFAULT_SECONDARY_ACCENT_SLOTS, -1);

        int helpSlot = readSingleSlot(config, "character-gui.layout.help-slot", DEFAULT_HELP_SLOT);
        int statusSlot = readSingleSlot(config, "character-gui.layout.status-slot", DEFAULT_STATUS_SLOT);
        int modeSlot = readSingleSlot(config, "character-gui.layout.mode-slot", DEFAULT_MODE_SLOT);
        int switchGuideSlot = readSingleSlot(config, "character-gui.layout.switch-guide-slot", DEFAULT_SWITCH_GUIDE_SLOT);

        int requiredSize = maxSlot(characterSlots, indicatorSlots, primaryAccents, secondaryAccents,
                new int[]{helpSlot, statusSlot, modeSlot, switchGuideSlot}) + 1;
        int configuredSize = normalizeInventorySize(config.getInt("character-gui.size", DEFAULT_INVENTORY_SIZE));
        int size = normalizeInventorySize(Math.max(configuredSize, requiredSize));

        return new GuiLayout(
                size,
                config.getString("character-gui.title", DEFAULT_GUI_TITLE),
                characterSlots,
                indicatorSlots,
                helpSlot,
                statusSlot,
                modeSlot,
                switchGuideSlot,
                primaryAccents,
                secondaryAccents
        );
    }

    private int readSingleSlot(FileConfiguration config, String path, int defaultValue) {
        int value = config.getInt(path, defaultValue);
        return value >= 0 && value < 54 ? value : defaultValue;
    }

    private int[] readSlotArray(FileConfiguration config, String path, int[] defaults, int expectedSize) {
        List<Integer> values = config.getIntegerList(path);
        if (values.isEmpty()) {
            return defaults.clone();
        }
        if (expectedSize > 0 && values.size() != expectedSize) {
            return defaults.clone();
        }

        int[] resolved = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            int value = values.get(i);
            if (value < 0 || value >= 54) {
                return defaults.clone();
            }
            resolved[i] = value;
        }
        return resolved;
    }

    private int maxSlot(int[]... groups) {
        int max = DEFAULT_INVENTORY_SIZE - 1;
        if (groups == null) {
            return max;
        }
        for (int[] group : groups) {
            if (group == null) {
                continue;
            }
            for (int value : group) {
                max = Math.max(max, value);
            }
        }
        return max;
    }

    private int normalizeInventorySize(int size) {
        int bounded = Math.max(9, Math.min(54, size));
        if (bounded % 9 == 0) {
            return bounded;
        }
        return Math.min(54, ((bounded + 8) / 9) * 9);
    }

    private ItemStack[] normalizeItems(ItemStack[] items) {
        ItemStack[] normalized = emptySlots();
        if (items == null) {
            return normalized;
        }
        for (int i = 0; i < Math.min(items.length, MAX_SLOTS); i++) {
            normalized[i] = cloneItem(items[i]);
        }
        return normalized;
    }

    private ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] copy = emptySlots();
        for (int i = 0; i < MAX_SLOTS; i++) {
            copy[i] = cloneItem(items[i]);
        }
        return copy;
    }

    private ItemStack cloneItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return item.clone();
    }

    private ItemStack[] emptySlots() {
        return new ItemStack[MAX_SLOTS];
    }

    private int normalizeSlot(int slot) {
        if (slot < 1 || slot > MAX_SLOTS) {
            return 1;
        }
        return slot;
    }

    private FileConfiguration guiConfig() {
        FileConfiguration config = plugin.getCharacterGuiConfig();
        return config != null ? config : plugin.getConfig();
    }

    private String applyPlaceholders(String text, Map<String, String> placeholders) {
        String value = text == null ? "" : text;
        if (placeholders == null || placeholders.isEmpty()) {
            return value;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return value;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private record GuiLayout(
            int size,
            String title,
            int[] characterSlots,
            int[] indicatorSlots,
            int helpSlot,
            int statusSlot,
            int modeSlot,
            int switchGuideSlot,
            int[] primaryAccentSlots,
            int[] secondaryAccentSlots
    ) {
    }
}
