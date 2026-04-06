package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class AdminGuiManager {

    private static final List<Integer> PLAYER_LIST_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    );
    private static final List<Integer> WEAPON_LIST_SLOTS = PLAYER_LIST_SLOTS;

    private final Yuanshen plugin;

    public AdminGuiManager(Yuanshen plugin) {
        this.plugin = plugin;
    }

    public void openMainMenu(Player viewer) {
        if (viewer == null) {
            return;
        }
        AdminGuiHolder holder = createHolder(AdminGuiHolder.ViewType.MAIN, viewer, null, null, 0, 0, 0);
        Inventory inventory = Bukkit.createInventory(holder, 27, ChatColor.DARK_RED + "原神腐竹管理");
        holder.setInventory(inventory);

        fill(inventory);
        inventory.setItem(10, createButton(Material.PLAYER_HEAD, "&6玩家管理",
                "&7查看在线玩家",
                "&7进入玩家操作面板"));
        inventory.setItem(13, createButton(Material.BOOK, "&e配置重载",
                "&7点击重载插件配置"));
        inventory.setItem(16, createButton(Material.BARRIER, "&c关闭",
                "&7关闭当前菜单"));

        viewer.openInventory(inventory);
    }

    public void openPlayerList(Player viewer, int page) {
        if (viewer == null) {
            return;
        }
        List<Player> players = getOnlinePlayers();
        int maxPage = Math.max(0, (players.size() - 1) / PLAYER_LIST_SLOTS.size());
        int safePage = Math.max(0, Math.min(page, maxPage));

        AdminGuiHolder holder = createHolder(AdminGuiHolder.ViewType.PLAYER_LIST, viewer, null, null, 0, 0, safePage);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_RED + "选择玩家");
        holder.setInventory(inventory);

        fill(inventory);
        inventory.setItem(45, createButton(Material.ARROW, "&e上一页", "&7第 " + (safePage + 1) + " 页"));
        inventory.setItem(49, createButton(Material.COMPASS, "&6返回主菜单"));
        inventory.setItem(53, createButton(Material.ARROW, "&e下一页", "&7第 " + (safePage + 1) + " 页"));

        int start = safePage * PLAYER_LIST_SLOTS.size();
        for (int i = 0; i < PLAYER_LIST_SLOTS.size(); i++) {
            int index = start + i;
            if (index >= players.size()) {
                break;
            }
            Player target = players.get(index);
            inventory.setItem(PLAYER_LIST_SLOTS.get(i), createPlayerHead(target,
                    "&7点击打开玩家操作页",
                    "&7当前角色: &f" + plugin.getCharacterSlotManager().getSelectedCharacterName(target),
                    "&7当前武器: &f" + plugin.getWeaponManager().getSelectedWeaponName(target)));
        }

        viewer.openInventory(inventory);
    }

    public void openPlayerActions(Player viewer, Player target) {
        if (viewer == null || target == null) {
            return;
        }
        AdminGuiHolder holder = createHolder(AdminGuiHolder.ViewType.PLAYER_ACTIONS, viewer, target, null, 0, 0, 0);
        Inventory inventory = Bukkit.createInventory(holder, 45, ChatColor.DARK_RED + "管理 " + target.getName());
        holder.setInventory(inventory);

        fill(inventory);
        inventory.setItem(4, createPlayerHead(target,
                "&7当前元素: &f" + formatElement(plugin.getActiveElement(target)),
                "&7角色槽: &f" + plugin.getCharacterSlotManager().getSelectedSlot(target),
                "&7当前武器: &f" + plugin.getWeaponManager().getSelectedWeaponName(target)));
        inventory.setItem(19, createButton(Material.CHEST, "&6角色/武器编辑",
                "&7打开该玩家的角色武器槽 GUI"));
        inventory.setItem(21, createButton(Material.DIAMOND_SWORD, "&6发放武器",
                "&7进入武器发放菜单"));
        inventory.setItem(23, createButton(Material.PAPER, "&e玩家数据",
                "&7查看当前角色、武器、面板"));
        inventory.setItem(25, createButton(Material.SLIME_BALL, "&a刷新属性",
                "&7刷新该玩家属性与侧边栏"));
        inventory.setItem(37, createToggle(Material.MAP, "&b侧边栏显示", plugin.isSidebarEnabled(target)));
        inventory.setItem(39, createToggle(Material.NAME_TAG, "&d伤害显示", plugin.isDamageDisplayEnabled(target)));
        inventory.setItem(41, createButton(Material.WATER_BUCKET, "&c清除元素",
                "&7清空该玩家当前元素"));
        inventory.setItem(36, createButton(Material.ARROW, "&e返回玩家列表"));
        inventory.setItem(44, createButton(Material.BARRIER, "&c关闭"));

        viewer.openInventory(inventory);
    }

    public void openPlayerInfo(Player viewer, Player target) {
        if (viewer == null || target == null) {
            return;
        }
        PlayerStats stats = plugin.getPlayerStats(target);
        AdminGuiHolder holder = createHolder(AdminGuiHolder.ViewType.PLAYER_INFO, viewer, target, null, 0, 0, 0);
        Inventory inventory = Bukkit.createInventory(holder, 45, ChatColor.DARK_RED + "数据 " + target.getName());
        holder.setInventory(inventory);

        fill(inventory);
        inventory.setItem(4, createPlayerHead(target,
                "&7当前元素: &f" + formatElement(plugin.getActiveElement(target))));
        inventory.setItem(19, createInfo(Material.NETHER_STAR, "&6角色信息",
                "&7槽位: &f" + plugin.getCharacterSlotManager().getSelectedSlot(target),
                "&7角色: &f" + plugin.getCharacterSlotManager().getSelectedCharacterName(target)));
        inventory.setItem(21, createInfo(Material.DIAMOND_SWORD, "&6武器信息",
                "&7武器: &f" + plugin.getWeaponManager().getSelectedWeaponName(target),
                "&7等级: &f" + plugin.getWeaponManager().getSelectedWeaponLevel(target),
                "&7精炼: &f" + plugin.getWeaponManager().getSelectedWeaponRefinement(target)));
        inventory.setItem(23, createInfo(Material.BLAZE_POWDER, "&6开关状态",
                "&7侧边栏: &f" + boolText(plugin.isSidebarEnabled(target)),
                "&7伤害显示: &f" + boolText(plugin.isDamageDisplayEnabled(target))));
        inventory.setItem(25, createInfo(Material.ENDER_EYE, "&6属性面板",
                stats == null ? "&7暂无数据" : "&7攻击: &f" + format(stats.getAttackDamage()),
                stats == null ? "&7暂无数据" : "&7暴击率: &f" + format(stats.getCritRate() * 100) + "%",
                stats == null ? "&7暂无数据" : "&7暴伤: &f" + format(stats.getCritDamage() * 100) + "%",
                stats == null ? "&7暂无数据" : "&7元素精通: &f" + format(stats.getElementMastery())));
        inventory.setItem(36, createButton(Material.ARROW, "&e返回玩家操作"));
        inventory.setItem(44, createButton(Material.BARRIER, "&c关闭"));

        viewer.openInventory(inventory);
    }

    public void openWeaponList(Player viewer, Player target, int page) {
        if (viewer == null || target == null) {
            return;
        }
        List<String> weaponIds = plugin.getWeaponManager().getWeaponIds();
        int maxPage = Math.max(0, (weaponIds.size() - 1) / WEAPON_LIST_SLOTS.size());
        int safePage = Math.max(0, Math.min(page, maxPage));

        AdminGuiHolder holder = createHolder(AdminGuiHolder.ViewType.WEAPON_LIST, viewer, target, null, 0, 0, safePage);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_RED + "武器发放 - " + target.getName());
        holder.setInventory(inventory);

        fill(inventory);
        inventory.setItem(45, createButton(Material.ARROW, "&e上一页"));
        inventory.setItem(49, createPlayerHead(target, "&7返回该玩家操作页"));
        inventory.setItem(53, createButton(Material.ARROW, "&e下一页"));

        int start = safePage * WEAPON_LIST_SLOTS.size();
        for (int i = 0; i < WEAPON_LIST_SLOTS.size(); i++) {
            int index = start + i;
            if (index >= weaponIds.size()) {
                break;
            }
            String weaponId = weaponIds.get(index);
            WeaponDefinition definition = plugin.getWeaponManager().get(weaponId);
            ItemStack item = plugin.getWeaponManager().createWeaponItem(weaponId);
            if (item == null) {
                item = createButton(Material.STICK, "&f" + weaponId);
            }
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add(color("&e点击设置等级和精炼"));
                lore.add(color("&7ID: &f" + weaponId));
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            if (definition == null) {
                item = createButton(Material.STICK, "&f" + weaponId, "&e点击设置等级和精炼");
            }
            inventory.setItem(WEAPON_LIST_SLOTS.get(i), item);
        }

        viewer.openInventory(inventory);
    }

    public void openWeaponConfig(Player viewer, Player target, String weaponId, int level, int refinement) {
        if (viewer == null || target == null) {
            return;
        }
        WeaponDefinition definition = plugin.getWeaponManager().get(weaponId);
        if (definition == null) {
            return;
        }

        int safeLevel = definition.clampLevel(level <= 0 ? definition.defaultLevel() : level);
        int safeRefinement = definition.clampRefinement(refinement <= 0 ? definition.defaultRefinement() : refinement);
        AdminGuiHolder holder = createHolder(AdminGuiHolder.ViewType.WEAPON_CONFIG, viewer, target, weaponId, safeLevel, safeRefinement, 0);
        Inventory inventory = Bukkit.createInventory(holder, 45, ChatColor.DARK_RED + "发放 " + definition.displayName());
        holder.setInventory(inventory);

        fill(inventory);
        inventory.setItem(4, createPlayerHead(target,
                "&7目标玩家: &f" + target.getName(),
                "&7点击确认后发放武器"));
        inventory.setItem(13, plugin.getWeaponManager().createWeaponItem(weaponId, safeLevel, safeRefinement));
        inventory.setItem(19, createLevelButton(1, safeLevel));
        inventory.setItem(20, createLevelButton(20, safeLevel));
        inventory.setItem(21, createLevelButton(40, safeLevel));
        inventory.setItem(22, createLevelButton(60, safeLevel));
        inventory.setItem(23, createLevelButton(80, safeLevel));
        inventory.setItem(24, createLevelButton(definition.maxLevel(), safeLevel));
        inventory.setItem(29, createRefinementButton(1, safeRefinement));
        inventory.setItem(30, createRefinementButton(2, safeRefinement));
        inventory.setItem(31, createRefinementButton(3, safeRefinement));
        inventory.setItem(32, createRefinementButton(4, safeRefinement));
        inventory.setItem(33, createRefinementButton(5, safeRefinement));
        inventory.setItem(40, createButton(Material.EMERALD, "&a确认发放",
                "&7等级: &f" + safeLevel,
                "&7精炼: &f" + safeRefinement));
        inventory.setItem(36, createButton(Material.ARROW, "&e返回武器列表"));
        inventory.setItem(44, createButton(Material.BARRIER, "&c关闭"));

        viewer.openInventory(inventory);
    }

    public boolean isAdminGui(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof AdminGuiHolder;
    }

    public AdminGuiHolder getHolder(Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof AdminGuiHolder holder) {
            return holder;
        }
        return null;
    }

    private AdminGuiHolder createHolder(AdminGuiHolder.ViewType type, Player viewer, Player target,
                                        String weaponId, int level, int refinement, int page) {
        return new AdminGuiHolder(
                type,
                viewer == null ? null : viewer.getUniqueId(),
                target == null ? null : target.getUniqueId(),
                weaponId,
                level,
                refinement,
                page
        );
    }

    private void fill(Inventory inventory) {
        ItemStack filler = createButton(Material.BLACK_STAINED_GLASS_PANE, "&8 ");
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private ItemStack createPlayerHead(Player player, String... lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof SkullMeta meta)) {
            return createButton(Material.PAPER, "&f" + player.getName(), lore);
        }
        meta.setOwningPlayer(player);
        meta.setDisplayName(color("&f" + player.getName()));
        meta.setLore(colorLines(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createButton(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(colorLines(lore));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInfo(Material material, String name, String... lore) {
        return createButton(material, name, lore);
    }

    private ItemStack createToggle(Material material, String name, boolean enabled) {
        return createButton(material, name,
                enabled ? "&a当前: 开启" : "&c当前: 关闭",
                "&7点击切换");
    }

    private ItemStack createLevelButton(int level, int selectedLevel) {
        Material material = level == selectedLevel ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
        return createButton(material, "&e等级 " + level,
                level == selectedLevel ? "&a当前已选择" : "&7点击切换到该等级");
    }

    private ItemStack createRefinementButton(int refinement, int selectedRefinement) {
        Material material = refinement == selectedRefinement ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
        return createButton(material, "&6精炼 " + refinement,
                refinement == selectedRefinement ? "&a当前已选择" : "&7点击切换到该精炼");
    }

    private List<Player> getOnlinePlayers() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return players;
    }

    private List<String> colorLines(String... lines) {
        List<String> colored = new ArrayList<>();
        for (String line : lines) {
            colored.add(color(line));
        }
        return colored;
    }

    private String formatElement(String elementKey) {
        if (elementKey == null || elementKey.isBlank()) {
            return "无";
        }
        return switch (elementKey) {
            case ElementConstant.FIRE_KEY -> "火";
            case ElementConstant.WATER_KEY -> "水";
            case ElementConstant.ICE_KEY -> "冰";
            case ElementConstant.ELECTRO_KEY -> "雷";
            case ElementConstant.ANEMO_KEY -> "风";
            case ElementConstant.GEO_KEY -> "岩";
            case ElementConstant.DENDRO_KEY -> "草";
            default -> elementKey;
        };
    }

    private String boolText(boolean value) {
        return value ? "开启" : "关闭";
    }

    private String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
