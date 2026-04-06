package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class AdminGuiListener implements Listener {

    private final Yuanshen plugin;

    public AdminGuiListener(Yuanshen plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!plugin.getAdminGuiManager().isAdminGui(top)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getRawSlot() >= top.getSize()) {
            return;
        }

        AdminGuiHolder holder = plugin.getAdminGuiManager().getHolder(top);
        if (holder == null) {
            return;
        }

        Player target = holder.getTargetId() == null ? null : Bukkit.getPlayer(holder.getTargetId());
        switch (holder.getViewType()) {
            case MAIN -> handleMain(player, event.getRawSlot());
            case PLAYER_LIST -> handlePlayerList(player, holder, event.getRawSlot());
            case PLAYER_ACTIONS -> handlePlayerActions(player, target, event.getRawSlot());
            case PLAYER_INFO -> handlePlayerInfo(player, target, event.getRawSlot());
            case WEAPON_LIST -> handleWeaponList(player, target, holder, event.getRawSlot(), event.getCurrentItem());
            case WEAPON_CONFIG -> handleWeaponConfig(player, target, holder, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (plugin.getAdminGuiManager().isAdminGui(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    private void handleMain(Player player, int rawSlot) {
        switch (rawSlot) {
            case 10 -> plugin.getAdminGuiManager().openPlayerList(player, 0);
            case 13 -> {
                plugin.reloadAllConfigs();
                player.sendMessage(color("&a配置已重载。"));
                plugin.getAdminGuiManager().openMainMenu(player);
            }
            case 16 -> player.closeInventory();
            default -> {
            }
        }
    }

    private void handlePlayerList(Player player, AdminGuiHolder holder, int rawSlot) {
        if (rawSlot == 45) {
            plugin.getAdminGuiManager().openPlayerList(player, holder.getPage() - 1);
            return;
        }
        if (rawSlot == 49) {
            plugin.getAdminGuiManager().openMainMenu(player);
            return;
        }
        if (rawSlot == 53) {
            plugin.getAdminGuiManager().openPlayerList(player, holder.getPage() + 1);
            return;
        }
        ItemStack clicked = holder.getInventory().getItem(rawSlot);
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        Player target = resolvePlayerFromHead(clicked);
        if (target == null) {
            return;
        }
        plugin.getAdminGuiManager().openPlayerActions(player, target);
    }

    private void handlePlayerActions(Player player, Player target, int rawSlot) {
        if (!ensureTargetOnline(player, target)) {
            return;
        }
        switch (rawSlot) {
            case 19 -> plugin.getCharacterSlotManager().openInventory(player, target);
            case 21 -> plugin.getAdminGuiManager().openWeaponList(player, target, 0);
            case 23 -> plugin.getAdminGuiManager().openPlayerInfo(player, target);
            case 25 -> {
                plugin.refreshPlayerStats(target);
                player.sendMessage(color("&a已刷新 " + target.getName() + " 的属性与侧边栏。"));
                plugin.getAdminGuiManager().openPlayerActions(player, target);
            }
            case 37 -> {
                plugin.setSidebarEnabled(target, !plugin.isSidebarEnabled(target));
                plugin.getPlayerDataStore().savePlayer(target);
                player.sendMessage(color("&a已切换 " + target.getName() + " 的侧边栏显示。"));
                plugin.getAdminGuiManager().openPlayerActions(player, target);
            }
            case 39 -> {
                plugin.setDamageDisplayEnabled(target, !plugin.isDamageDisplayEnabled(target));
                plugin.getPlayerDataStore().savePlayer(target);
                player.sendMessage(color("&a已切换 " + target.getName() + " 的伤害显示。"));
                plugin.getAdminGuiManager().openPlayerActions(player, target);
            }
            case 41 -> {
                plugin.clearActiveElement(target);
                plugin.getPlayerDataStore().savePlayer(target);
                player.sendMessage(color("&a已清除 " + target.getName() + " 的当前元素。"));
                plugin.getAdminGuiManager().openPlayerActions(player, target);
            }
            case 36 -> plugin.getAdminGuiManager().openPlayerList(player, 0);
            case 44 -> player.closeInventory();
            default -> {
            }
        }
    }

    private void handlePlayerInfo(Player player, Player target, int rawSlot) {
        if (!ensureTargetOnline(player, target)) {
            return;
        }
        switch (rawSlot) {
            case 36 -> plugin.getAdminGuiManager().openPlayerActions(player, target);
            case 44 -> player.closeInventory();
            default -> {
            }
        }
    }

    private void handleWeaponList(Player player, Player target, AdminGuiHolder holder, int rawSlot, ItemStack currentItem) {
        if (!ensureTargetOnline(player, target)) {
            return;
        }
        switch (rawSlot) {
            case 45 -> {
                plugin.getAdminGuiManager().openWeaponList(player, target, holder.getPage() - 1);
                return;
            }
            case 49 -> {
                plugin.getAdminGuiManager().openPlayerActions(player, target);
                return;
            }
            case 53 -> {
                plugin.getAdminGuiManager().openWeaponList(player, target, holder.getPage() + 1);
                return;
            }
            default -> {
            }
        }

        WeaponInstance instance = plugin.getWeaponManager().resolveWeaponInstance(currentItem);
        if (instance == null) {
            return;
        }
        plugin.getAdminGuiManager().openWeaponConfig(
                player,
                target,
                instance.definition().id(),
                instance.level(),
                instance.refinement()
        );
    }

    private void handleWeaponConfig(Player player, Player target, AdminGuiHolder holder, int rawSlot) {
        if (!ensureTargetOnline(player, target)) {
            return;
        }
        WeaponDefinition definition = plugin.getWeaponManager().get(holder.getWeaponId());
        if (definition == null) {
            player.closeInventory();
            return;
        }

        switch (rawSlot) {
            case 19 -> reopenWeaponConfig(player, target, definition.id(), 1, holder.getRefinement());
            case 20 -> reopenWeaponConfig(player, target, definition.id(), 20, holder.getRefinement());
            case 21 -> reopenWeaponConfig(player, target, definition.id(), 40, holder.getRefinement());
            case 22 -> reopenWeaponConfig(player, target, definition.id(), 60, holder.getRefinement());
            case 23 -> reopenWeaponConfig(player, target, definition.id(), 80, holder.getRefinement());
            case 24 -> reopenWeaponConfig(player, target, definition.id(), definition.maxLevel(), holder.getRefinement());
            case 29 -> reopenWeaponConfig(player, target, definition.id(), holder.getLevel(), 1);
            case 30 -> reopenWeaponConfig(player, target, definition.id(), holder.getLevel(), 2);
            case 31 -> reopenWeaponConfig(player, target, definition.id(), holder.getLevel(), 3);
            case 32 -> reopenWeaponConfig(player, target, definition.id(), holder.getLevel(), 4);
            case 33 -> reopenWeaponConfig(player, target, definition.id(), holder.getLevel(), 5);
            case 36 -> plugin.getAdminGuiManager().openWeaponList(player, target, 0);
            case 40 -> giveWeapon(player, target, definition.id(), holder.getLevel(), holder.getRefinement());
            case 44 -> player.closeInventory();
            default -> {
            }
        }
    }

    private void reopenWeaponConfig(Player viewer, Player target, String weaponId, int level, int refinement) {
        plugin.getAdminGuiManager().openWeaponConfig(viewer, target, weaponId, level, refinement);
    }

    private void giveWeapon(Player viewer, Player target, String weaponId, int level, int refinement) {
        ItemStack item = plugin.getWeaponManager().createWeaponItem(weaponId, level, refinement);
        WeaponInstance instance = plugin.getWeaponManager().resolveWeaponInstance(item);
        if (item == null || instance == null) {
            viewer.sendMessage(color("&c武器创建失败。"));
            return;
        }

        if (!target.getInventory().addItem(item).isEmpty()) {
            viewer.sendMessage(color("&c目标玩家背包空间不足。"));
            return;
        }

        viewer.sendMessage(color("&a已发放武器 &f" + instance.definition().displayName() + "&a 给 &f" + target.getName()
                + "&a，等级 &f" + instance.level() + "&a，精炼 &f" + instance.refinement()));
        if (!viewer.equals(target)) {
            target.sendMessage(color("&a你获得了武器 &f" + instance.definition().displayName()
                    + "&a，等级 &f" + instance.level() + "&a，精炼 &f" + instance.refinement()));
        }
        plugin.getAdminGuiManager().openPlayerActions(viewer, target);
    }

    private Player resolvePlayerFromHead(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        return name == null ? null : Bukkit.getPlayerExact(name);
    }

    private boolean ensureTargetOnline(Player viewer, Player target) {
        if (target != null) {
            return true;
        }
        viewer.sendMessage(color("&c目标玩家已离线。"));
        plugin.getAdminGuiManager().openPlayerList(viewer, 0);
        return false;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
