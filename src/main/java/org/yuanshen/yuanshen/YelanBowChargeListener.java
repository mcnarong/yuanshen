package org.yuanshen.yuanshen;

import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class YelanBowChargeListener implements Listener {

    private final Yuanshen plugin;

    public YelanBowChargeListener(Yuanshen plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!plugin.hasActiveCharacter(player)) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() == EquipmentSlot.HAND && plugin.getYelanSkillHandler() != null) {
            plugin.getYelanSkillHandler().beginBowCharge(player, event.getItem());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onStopUsingItem(PlayerStopUsingItemEvent event) {
        if (plugin.getYelanSkillHandler() == null) {
            return;
        }

        ItemStack item = event.getItem();
        if (item != null && item.getType() == Material.BOW) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> plugin.getYelanSkillHandler().clearBowCharge(event.getPlayer()));
            return;
        }
        plugin.getYelanSkillHandler().clearBowCharge(event.getPlayer());
    }
}
