package org.yuanshen.yuanshen;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

public class ConstellationGuiListener implements Listener {

    private final Yuanshen plugin;

    public ConstellationGuiListener(Yuanshen plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!plugin.getConstellationGuiManager().isConstellationGui(top)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        if (!(top.getHolder() instanceof ConstellationGuiHolder holder)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) {
            return;
        }

        Player owner = Bukkit.getPlayer(holder.getOwnerId());
        if (owner == null) {
            viewer.closeInventory();
            viewer.sendMessage(color("&c角色所属玩家已离线。"));
            return;
        }

        if (event.getRawSlot() == 10) {
            plugin.getCharacterSlotManager().openInventory(viewer, owner);
            return;
        }

        if (event.getRawSlot() != 15) {
            return;
        }

        ConstellationManager.UpgradeResult result = plugin.getConstellationManager()
                .upgradeCharacterSlot(owner, holder.getSlot(), viewer);
        viewer.sendMessage(color((result.success() ? "&a" : "&c") + result.message()));
        if (result.success() && !viewer.equals(owner)) {
            owner.sendMessage(color("&a你的角色 " + result.instance().definition().displayName()
                    + " 已升级到 &f" + result.instance().constellation() + "&a 命。"));
        }

        plugin.getConstellationGuiManager().openInventory(viewer, owner, holder.getSlot());
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
